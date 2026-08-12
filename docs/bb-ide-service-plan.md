# BB IDE service: продуктовая идея, архитектура и CLI

Статус: draft
Связанное исследование: [JetBrains IDEA как backend для BB](./jetbrains-idea-backend-for-bb.md)
Связанная интеграция: [Change Review и BB IDE service](./change-review-ide-integration.md)

## Идея

Сделать в BB не LSP-клиент, а единый **IDE service**: слой семантических операций над кодом, доступный редактору, агентам, CLI и плагинам через один контракт.

Провайдеры скрыты за этим контрактом:

- Java/Kotlin — IntelliJ IDEA в фоне, PSI, refactoring, inspections, build и test APIs;
- TypeScript/JavaScript — `tsserver` или TypeScript language server, ESLint/Biome и адаптеры тестовых раннеров;
- в дальнейшем — другие специализированные backend-провайдеры.

LSP может быть одним из транспортов или адаптеров, но не границей продукта. Граница продукта — операции уровня IDE: безопасный rename, move, package rename, inspections, quick fixes, навигация, форматирование, поиск использований, запуск build и tests.

## Зачем это BB

Один и тот же сервис должен обслуживать четыре поверхности:

1. редактор BB — completion, hover, diagnostics и code actions;
2. агенты — структурированная навигация и изменения кода;
3. CLI — управление сервисом и воспроизводимые пакетные операции;
4. плагины — расширение провайдеров, inspections и test adapters.

BB при этом остаётся владельцем открытых буферов и записи файлов. Backend анализирует переданный snapshot и возвращает edits или план операции; BB проверяет версии файлов и применяет изменения транзакционно.

## Контракт возможностей

Базовые операции:

- `document.complete`, `document.hover`, `document.diagnostics`;
- `navigation.definition`, `navigation.references`, `navigation.implementations`;
- `refactor.renameSymbol`, `refactor.movePath`, `refactor.renamePackage`;
- `refactor.changeSignature`, `refactor.safeDelete`, `refactor.extract`;
- `inspection.runFile`, `inspection.runProject`, `inspection.applyFix`;
- `format.file`, `imports.organize`;
- `test.discover`, `test.run`, `test.debug`;
- `build.run`.

Наличие операции определяется capabilities конкретного провайдера и проекта. UI и агент не должны предполагать, что все языки поддерживают одинаковый набор.

### Java/Kotlin

IDEA даёт полноценную модель проекта и операции поверх PSI. Это основной backend для:

- symbol/package rename;
- move классов, файлов и пакетов с обновлением usages;
- inspections и quick fixes;
- hierarchy/navigation;
- обнаружения и запуска тестов;
- Gradle/Maven project model.

### TypeScript/JavaScript

TypeScript backend покрывает completion, diagnostics, symbol rename, references и обновление импортов при file rename. Дополнительные возможности собираются на стороне BB:

- directory/module move — композиция file moves и TypeScript edits;
- rename npm/workspace package — отдельная операция BB, обновляющая `package.json`, workspace config, lockfiles, `tsconfig`, imports, Turbo/CI-конфигурацию;
- inspections — TypeScript diagnostics плюс ESLint/Biome;
- tests — адаптеры Vitest, Jest и Playwright.

## Безопасная модель изменений

Любая составная или потенциально опасная операция сначала возвращает immutable plan:

```json
{
  "operationId": "op_...",
  "kind": "renamePackage",
  "fileMoves": [],
  "textEdits": [],
  "expectedFileHashes": {},
  "affectedFiles": [],
  "warnings": []
}
```

Применение плана — отдельное действие. Перед записью BB проверяет версии буферов и SHA файлов. При конфликте план не применяется частично. После успешной транзакции BB синхронизирует backend и может последовательно запустить diagnostics, build и выбранные tests.

## Процессная модель

- На одной host-машине работает общий pool управляемых backend-процессов. Совместимые environments переиспользуют один IDEA JVM; ключ совместимости включает версию IDE/backend и набор обязательных plugins.
- Один IDEA JVM может держать несколько проектов и worktree одновременно.
- TypeScript server разумно держать отдельно на проект или на совместимую workspace-группу: его модель и жизненный цикл дешевле IDEA JVM.
- Broker выбирает провайдер по файлу, проекту и capabilities.
- Неактивные проекты отсоединяются, а простаивающие backend-процессы завершаются по TTL.
- Gradle/npm caches общие на host, project indexes и system directories изолированы там, где этого требует IDE.

Эксперимент уже подтвердил, что два проекта в одном headless IDEA JVM работают и занимают около 1.69 GiB RSS против 2.73 GiB у двух отдельных JVM — экономия примерно 38% в измеренной конфигурации.

Для production одного plugin server недостаточно: он исполняется на BB server, тогда как IDEA и language servers должны работать на host, где находится environment. Нужен daemon RPC для managed services: запуск, attach/detach, status, stop и проксирование запросов к host-local loopback endpoint.

Если этот RPC меняет сообщения между server и host daemon, необходимо увеличить `HOST_DAEMON_PROTOCOL_VERSION`. Новые публичные plugin API сначала выпускаются с префиксом `experimental_` и записью в `docs/api_to_audit.md`.

### Владение и привязка

IDE service не прикрепляется напрямую к треду. Владение разделено на четыре уровня:

```text
Host
└── Service pool
    └── IDEA JVM / TypeScript server
        └── Environment attachment (workspace/worktree)
            ├── Thread lease
            ├── Editor lease
            └── Operation lease
```

- **Host** определяет машину, доступные runtimes и общий бюджет ресурсов.
- **Service** — физический процесс IDEA JVM или TypeScript server, который может переиспользоваться совместимыми environments.
- **Environment attachment** — открытый workspace/worktree и его project model. Именно environment является постоянной BB-привязкой, потому что содержит host и canonical workspace path.
- **Lease** — временное доказательство того, что проект нужен consumer-у: треду, открытому редактору или выполняющейся CLI/agent-операции.

Тред не владеет JVM. Вызов `bb ide ensure` внутри треда автоматически использует текущие `threadId` и `environmentId` и создаёт lease этого треда. Вызов извне требует `--environment`. Несколько тредов одного environment используют один attachment и один индекс; разные worktree одного репозитория являются разными attachments, но могут жить в одном совместимом IDEA JVM.

Закрытие одного треда снимает только его lease и не ломает других consumers. Attachment закрывается лишь после исчезновения последнего lease и окончания idle TTL. Явный pin считается долгоживущим lease уровня environment.

### Lifecycle и вытеснение

Рекомендуемая политика по умолчанию:

- CLI-операция держит lease до своего завершения;
- активный тред держит lease во время работы и короткий grace period после неё;
- редактор держит lease, пока соответствующий environment открыт;
- attachment без consumers остаётся прогретым, например 30 минут;
- пустой backend-процесс завершается после дополнительного service idle TTL, например 10 минут;
- при memory pressure idle-объекты могут быть вытеснены раньше TTL;
- активный или pinned attachment автоматически не вытесняется.

Порядок освобождения ресурсов:

1. idle TypeScript servers;
2. самые старые idle environment attachments в IDEA;
3. пустые IDEA JVM;
4. если бюджета всё ещё недостаточно — новый `ensure` ждёт в очереди либо возвращает структурированную resource-limit ошибку.

`stop` без `--force` не должен останавливать service с чужими активными leases. `--force` — административная операция с явным предупреждением о затрагиваемых consumers.

### Бюджеты ресурсов

Политика задаётся на host, а не на отдельный тред. Предварительный набор настроек:

```yaml
ide:
  maxIdeaJvmCount: 1
  maxProjectsPerIdeaJvm: 6
  maxIdeaMemoryMb: 4096
  maxConcurrentIndexing: 1
  projectIdleTtlMinutes: 30
  serviceIdleTtlMinutes: 10
  typescript:
    maxServers: 8
    idleTtlMinutes: 15
```

Это пока форма продуктового контракта, а не утверждённые имена settings. Ограничение JVM heap задаётся процессу при старте; scheduler дополнительно наблюдает RSS, число attachments, indexing load и доступную память host. Server/plugin определяет политику приоритетов и вытеснения, host daemon возвращает сырые метрики и безопасно исполняет lifecycle-команды.

## CLI

### Название и граница

Один namespace: `bb ide`.

CLI нужен для control plane, диагностики и воспроизводимых пакетных операций. Высокочастотные запросы редактора — completion, hover, diagnostics по каждому изменению — идут напрямую через SDK/RPC, а не через запуск CLI-процесса.

Все команды должны:

- принимать `--environment <id>` как основной selector; из него BB получает host и workspace path;
- при необходимости принимать `--machine <id-or-name>`;
- поддерживать `--json` со стабильной машинной схемой;
- не требовать знания PID, port или внутренних endpoint;
- использовать общие server/domain-функции с UI и agent tools;
- быть идемпотентными там, где это возможно.

### MVP: управление backend

```sh
bb ide ensure [--environment <id>] [--wait ready|smart] [--json]
bb ide status [--environment <id>] [--machine <id>] [--json]
bb ide pin [--environment <id>] [--json]
bb ide unpin [--environment <id>] [--json]
bb ide release [--environment <id>] [--json]
bb ide stop [--environment <id>] [--provider idea|typescript|all] [--force]
bb ide restart --environment <id> [--provider idea|typescript|all]
bb ide logs [--environment <id>] [--provider idea|typescript] [--tail 200] [--follow]
bb ide doctor [--environment <id>] [--machine <id>] [--json]
```

Семантика:

- `ensure` — главная команда desired state: запускает нужные providers, создаёт или переиспользует environment attachment, выдаёт lease текущему consumer-у и ждёт запрошенного уровня готовности; повторный вызов безопасен;
- `status` — показывает services, attachments, leases/consumers, indexing state, capabilities, uptime, RSS, idle deadline и последнюю ошибку;
- `pin` — удерживает environment прогретым независимо от тредов и редакторов;
- `unpin` — снимает долгоживущий pin и возвращает environment под обычную TTL-политику;
- `release` — снимает lease текущего треда/consumer-а, не останавливая общий service и не затрагивая чужие leases;
- `stop` — отсоединяет environment и останавливает процесс, только если у него не осталось потребителей; `--force` требует явного намерения;
- `restart` — управляемый stop/start с повторным attach проектов;
- `logs` — объединяет supervisor и provider logs;
- `doctor` — проверяет наличие совместимой IDEA/JBR, starter JAR, Node/TypeScript tools, доступ к cache, loopback/RPC и права на workspace.

Отдельные публичные `start` и `attach` в MVP не нужны: `ensure` выражает пользовательское намерение и не заставляет знать внутреннюю процессную модель. Низкоуровневые lifecycle-операции остаются внутри SDK.

Пример `status --json`:

```json
{
  "environmentId": "env_...",
  "hostId": "host_...",
  "state": "smart",
  "providers": [
    {
      "kind": "idea",
      "state": "ready",
      "pid": 12345,
      "uptimeMs": 420000,
      "rssBytes": 1814623680,
      "projects": ["/workspace/a", "/workspace/b"],
      "indexing": "smart",
      "version": "2025.3",
      "lastError": null
    }
  ]
}
```

### Discovery

```sh
bb ide providers [--machine <id>] [--json]
bb ide capabilities --environment <id> [--file <path>] [--json]
bb ide projects [--machine <id>] [--json]
bb ide metrics [--machine <id>] [--json]
bb ide gc [--machine <id>] [--idle-for 30m] [--dry-run]
```

`gc` отсоединяет потерянные environments и завершает только процессы без активных consumers. По умолчанию команда должна показывать план; реальное удаление cache не входит в её ответственность.

Пример человекочитаемого resource status:

```text
Host: Mac mini
IDEA JVM: ready, 1.69 GiB RSS, 2 projects

Environment env_a  /worktrees/backend
  Consumers: thread:thr_1, editor
  Idle: no

Environment env_b  /worktrees/feature
  Consumers: none
  Idle: 8m
  Eviction: in 22m
```

### Пакетные IDE-операции

Эти команды добавляются после стабилизации control plane:

```sh
bb ide inspect file <path> --environment <id> [--json]
bb ide inspect project --environment <id> [--profile <name>] [--json]
bb ide test list --environment <id> [--file <path>] [--json]
bb ide test run --environment <id> [--test <id>] [--file <path>] [--json]
bb ide build --environment <id> [--module <name>] [--json]

bb ide refactor rename-symbol --environment <id> --file <path> \
  --line <n> --column <n> --to <name> [--json]
bb ide refactor move-path --environment <id> --from <path> --to <path> [--json]
bb ide refactor rename-package --environment <id> --from <name> --to <name> [--json]
bb ide refactor apply <operation-id> [--json]
```

Refactor-команды по умолчанию только создают и печатают plan. Запись выполняет отдельный `refactor apply`. Это делает команду безопасной для агентов, даёт UI тот же preview и позволяет обнаружить stale buffers до изменения файлов.

### Коды завершения

- `0` — команда выполнена, desired state достигнут;
- `1` — операция или health check завершились ошибкой;
- `2` — неверные аргументы;
- `3` — backend не настроен или capability отсутствует;
- `4` — host/environment недоступен;
- `5` — timeout ожидания готовности;
- `6` — конфликт версий или устаревший operation plan.

Человекочитаемый вывод может меняться, JSON-контракт — versioned. При `--json` ошибки также возвращаются структурированно в stdout/stderr с `code`, `message`, `retryable` и доступным контекстом.

## Plugin и SDK

Первый вариант оформляется как BB plugin:

```text
bb-ide/
  .bb-plugin/plugin.json
  src/server.ts
  src/app.tsx
  src/broker/
  src/providers/idea/
  src/providers/typescript/
  assets/idea-starter-252.jar
  assets/idea-starter-253.jar
  skills/ide-service/SKILL.md
```

Плагин регистрирует `bb ide` через CLI API, панель состояния и agent-facing tools. Все они вызывают один service layer. Java starter распространяется заранее собранным по поддерживаемым major-версиям IDEA; runtime compilation остаётся только dev-инструментом.

Change Review и другие UI-плагины обращаются к тому же service layer через versioned JSON-контракт `fileReference`. В первом инкременте межплагиновый transport — `bb.sdk.plugins.callRpc`; CLI остаётся отдельным адаптером для человека и агента, а не внутренней шиной редактора. Переходы возвращают `DocumentRef`, поэтому могут открывать как workspace-файлы, так и read-only документы из `node_modules`, Maven/Gradle caches, source JAR и IDEA virtual/decompiled files. Полное решение описано в [заметке об интеграции](./change-review-ide-integration.md).

Нужный host primitive ориентировочно выглядит так:

- `experimental_managedServices.ensure(spec)`;
- `experimental_managedServices.status(selector)`;
- `experimental_managedServices.stop(selector)`;
- `experimental_managedServices.request(serviceId, request)`;
- stream логов и событий состояния.

Конкретный API надо спроектировать вместе с daemon wire contract, не протаскивая продуктовую политику IDE service в host daemon. Daemon управляет host-local процессами и транспортом; server/plugin определяет providers, TTL, routing и пользовательское поведение.

## Этапы реализации

### 0. Исследование — выполнено

- [x] headless IDEA 2025.3 запускается как background JVM;
- [x] один JVM открывает несколько проектов;
- [x] добавлены lifecycle lock и проверка reuse;
- [x] измерена память shared и separate JVM;
- [x] подтверждены Java/Kotlin semantic operations;
- [x] зафиксировано ограничение IDEA FREE для JS/TS Ultimate features.

### 1. Локальный plugin MVP

- [ ] scaffold `bb-ide` plugin;
- [ ] упаковать starter JAR вместо runtime compilation;
- [ ] реализовать service state store и supervisor;
- [ ] добавить `bb ide ensure/status/stop/logs/doctor`;
- [ ] ввести environment attachments, consumer leases и `pin/unpin/release`;
- [ ] добавить TTL, безопасное вытеснение и resource status;
- [ ] вернуть capabilities и indexing state;
- [ ] добавить минимальные agent tools: diagnostics, definition, references, completion;
- [ ] покрыть lifecycle и JSON CLI contract интеграционными тестами.

### 2. Host-managed service

- [ ] добавить сырой managed-service primitive в host daemon;
- [ ] добавить server proxy и lifecycle events;
- [ ] увеличить daemon protocol version;
- [ ] оформить experimental plugin API и audit note;
- [ ] проверить local, remote host и несколько environments на одном host.

### 3. TypeScript provider и broker

- [ ] подключить tsserver/typescript-language-server;
- [ ] маршрутизировать операции по file/language/capabilities;
- [ ] добавить ESLint/Biome diagnostics;
- [ ] реализовать общий contract buffer snapshots и edits.

### 4. Безопасные refactorings

- [ ] ввести operation plan, hashes и transactional apply;
- [ ] Java/Kotlin symbol/package rename и move;
- [ ] TypeScript symbol/file rename;
- [ ] BB-specific npm/workspace package rename;
- [ ] CLI preview/apply и UI diff preview.

### 5. Inspections, build и tests

- [ ] project inspections и quick fixes;
- [ ] Gradle/Maven build и test discovery/run;
- [ ] Vitest/Jest/Playwright adapters;
- [ ] post-refactor pipeline: sync → diagnostics → build → selected tests.

### 6. Редактор BB

- [ ] buffer synchronization и cancellation;
- [ ] completion/hover/diagnostics/code actions;
- [ ] rename/move UI с preview;
- [ ] test explorer и inspection results;
- [ ] telemetry: latency, memory, indexing time, crash/restart rate.

## Решения, которые ещё нужно принять

- какой минимум IDEA editions/major versions поддерживать;
- распространять ли starter JAR внутри plugin или отдельным versioned artifact;
- где хранить operation plans и как долго они живут;
- политика TTL и memory pressure для shared IDEA JVM;
- точные host resource defaults и поведение очереди при исчерпании бюджета;
- нужен ли низкоуровневый `attach/detach` только для диагностики, либо достаточно `ensure/release`;
- какие CLI JSON schemas стабилизировать в первом релизе;
- какие operations должны быть доступны агентам автоматически, а какие требуют явного подтверждения пользователя.
