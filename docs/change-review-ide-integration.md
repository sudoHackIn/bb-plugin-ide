# Интеграция Change Review и BB IDE service

Статус: proposed

Дата решения: 2026-08-13

Связанный план: [BB IDE service](./bb-ide-service-plan.md)

## Контекст

Плагин Change Review владеет пользовательской поверхностью просмотра файлов и
diff: вкладками, подсветкой строк, комментариями и переходами между открытыми
документами. Плагин IDE service владеет семантикой кода и жизненным циклом
провайдеров: IDEA, TypeScript server и будущих language-specific backend.

Review не должен знать адрес MCP endpoint, PID, порт IDEA или расположение
TypeScript server. IDE service не должен владеть вкладками review и напрямую
рисовать его UI. Граница между плагинами — versioned JSON-контракт семантической
операции над документом.

## Решение

Ввести одну доменную операцию `fileReference`, которую реализует общий service
layer IDE-плагина. В MVP она публикуется через два адаптера:

- plugin RPC `fileReference` — для Change Review и других UI-плагинов;
- CLI `bb ide file-reference --json` — для человека, агента, диагностики и
  воспроизводимых пакетных вызовов.

RPC и CLI не содержат отдельной бизнес-логики: они валидируют JSON, вызывают
один `ideService.fileReference(input)` и возвращают один формат результата.

```text
Change Review frontend
        │ useRpc
        ▼
Change Review backend
        │ bb.sdk.plugins.callRpc
        ▼
IDE plugin: fileReference RPC
        │
        ▼
ideService.fileReference(input)
        │ broker
        ├── IDEA provider
        └── TypeScript provider
             │
             ▼
        host daemon / host-local backend
```

На текущем Plugin SDK `bb.sdk.plugins.callRpc` делает loopback HTTP-запрос к
RPC route второго плагина. Это допустимый MVP: запрос не выходит с BB server,
а основная задержка всё равно приходится на host daemon и IDE provider.

В публичном SDK сейчас нет вызываемого межплагинового `bb.services` registry.
`bb.background.service` — только lifecycle долгоживущего фонового процесса и
не является RPC/service-dispatch механизмом.

CLI не используется как транспорт между UI-плагинами. Запуск `bb ide` из
backend Change Review добавил бы отдельный процесс, ещё один HTTP-вызов обратно
в BB server, argv/stdin serialization и CLI-лимиты. CLI остаётся адаптером для
людей и агентов, а не внутренней шиной редактора.

## JSON-контракт

MVP использует один endpoint и различает действия полем `operation`:

```ts
type FileReferenceOperation =
  | "capabilities"
  | "diagnostics"
  | "definition"
  | "references"
  | "implementations"
  | "readDocument";

type FileReferenceRequest = {
  version: 1;
  operation: FileReferenceOperation;
  environmentId: string;
  document: DocumentRef;
  position?: Position;
  expectedRevision?: string;
  limit?: number;
};
```

`position` задаётся как zero-based line и UTF-16 character, чтобы контракт без
пересчётов совпадал с TypeScript/LSP-style координатами:

```ts
type Position = {
  line: number;
  character: number;
};

type Range = {
  start: Position;
  end: Position;
};
```

Общий ответ сообщает готовность provider и revision проанализированного
документа:

```ts
type FileReferenceResult = {
  version: 1;
  state: "ready" | "indexing" | "unavailable" | "stale" | "unsupported";
  provider: "idea" | "typescript" | null;
  documentRevision: string | null;
  capabilities?: FileReferenceOperation[];
  diagnostics?: Diagnostic[];
  locations?: Location[];
  content?: {
    text: string;
    languageId: string | null;
    readOnly: boolean;
  };
  message: string | null;
};
```

Каждый RPC input и output проходит runtime schema validation. Схемы и
TypeScript-типы должны жить в небольшом общем protocol package, чтобы IDE и
Change Review компилировались против одной версии контракта.

## Ссылки на документы

Переход может вести не только в файл текущего workspace. Поэтому `Location`
содержит `DocumentRef`, а не строковый path:

```ts
type Location = {
  document: DocumentRef;
  range: Range;
};

type DocumentRef =
  | {
      kind: "workspace";
      environmentId: string;
      path: string;
      revision: string | null;
    }
  | {
      kind: "external";
      environmentId: string;
      provider: "idea" | "typescript";
      documentId: string;
      displayPath: string;
      source: "filesystem" | "archive" | "decompiled" | "virtual";
      origin: DependencyOrigin | null;
      readOnly: true;
    };

type DependencyOrigin = {
  ecosystem: "npm" | "maven" | "gradle" | "jdk" | "other";
  coordinate: string | null;
};
```

Для workspace-документа `path` всегда relative к environment root. `revision`
обычно является SHA-256 содержимого или provider document version. Change
Review отклоняет результат, если открытый файл уже имеет другую revision.

External document выдаётся только IDE-плагином и всегда read-only.
`documentId` scoped к environment attachment и не является произвольным путём,
который frontend может заставить сервер прочитать. При каждом повторном вызове
IDE-плагин проверяет принадлежность document ID attachment и dependency roots.

### JavaScript и TypeScript

Definition может указывать на `.js`, `.ts` или `.d.ts` в `node_modules`, pnpm
store, TypeScript lib либо другом package-manager cache. TypeScript provider
получает обычный host path, но наружу возвращает `external` document ref с
понятным package display path. Чтение физического файла выполняется на host,
которому принадлежит environment.

### IDEA, Maven и Gradle

Definition может указывать на:

- source file из Maven или Gradle cache;
- entry внутри `*-sources.jar`;
- IDEA `jar://...!/` VirtualFile;
- декомпилированный class, если source JAR отсутствует;
- JDK или Kotlin library source.

Физический source file можно прочитать через host files API. Archive entry,
VirtualFile и декомпилированный PSI читает IDEA provider через
`readDocument`: Change Review не распаковывает JAR и не воспроизводит логику
IDEA самостоятельно.

В UI внешние документы открываются как обычные отдельные вкладки, но получают
read-only badge и dependency breadcrumb, например:

```text
External Libraries › Maven › com.google.guava:guava:33.0.0 › ImmutableList.java
External Libraries › npm › react@19 › index.d.ts
```

`node_modules`, `~/.m2` и Gradle cache не добавляются в обычное дерево файлов.
Внешний документ появляется только как результат семантического перехода.
Переходы из него работают дальше, потому что следующий запрос использует тот
же `DocumentRef`, а не workspace path.

## Поведение Change Review

При открытии workspace-файла Change Review:

1. получает environment и текущую revision файла;
2. запрашивает capabilities и diagnostics;
3. рисует diagnostics только для совпадающей revision;
4. при переходе передаёт позицию в `fileReference`;
5. открывает `Location.document` в новой или существующей вкладке и reveal-ит
   `Location.range`.

При открытии external document Change Review получает содержимое через
`readDocument`. Вкладка не предлагает сохранение, rename или другие write
actions. Комментарий к внешнему коду может быть добавлен в чат, но не означает
изменение dependency source.

Для незакоммиченного diff правая сторона может ссылаться на текущий workspace
document. Base-side, deleted files и исторические snapshots не считаются
текущими файлами. Их семантическая навигация появится после поддержки virtual
document snapshots; до этого она возвращает `unsupported`.

## Lifecycle и обновления

Открытая IDE-aware вкладка удерживает editor lease соответствующего
environment attachment. Закрытие последней вкладки снимает lease, а TTL
страхует потерянный release при crash или reload UI.

Для MVP diagnostics запрашиваются при открытии файла, ручном refresh и после
известного изменения файла. Высокочастотные сценарии — hover, completion и
diagnostics на каждый edit — требуют cancellation, debouncing и push-сигналов
и не входят в первый read-only review increment.

## Эволюция транспорта

Первый этап использует `bb.sdk.plugins.callRpc`. JSON-контракт не зависит от
этого транспорта.

Если loopback HTTP станет измеримой проблемой, предпочтительный следующий шаг
в ядре BB — оптимизировать server-side `plugins.callRpc` до прямого in-process
dispatch при сохранении тех же validation, error envelope и plugin lifecycle.
Альтернатива — отдельный experimental callable service registry, если кроме
request/response понадобятся streams, subscriptions или shared cancellation.

В обоих случаях Change Review продолжает вызывать тот же `fileReference`, а
IDE provider routing и host daemon transport остаются скрытыми внутри
IDE-плагина.

## Порядок реализации

1. Вынести versioned schemas `DocumentRef`, `Position`, `Location`,
   `Diagnostic`, request и result в общий protocol package.
2. Реализовать чистый `ideService.fileReference` и TypeScript/IDEA adapters.
3. Зарегистрировать один RPC `fileReference` и CLI
   `bb ide file-reference --json` поверх service layer.
4. Добавить в Change Review backend RPC proxy через
   `bb.sdk.plugins.callRpc`.
5. Научить файловую вкладку принимать `{ document, reveal }`, отображать
   diagnostics и открывать переходы.
6. Добавить read-only external tabs для filesystem, archive, decompiled и
   virtual documents.
7. Ввести editor leases, stale-result rejection, cancellation и telemetry.
8. После измерений решить, нужен ли in-process dispatch в ядре BB.

## Зафиксированные ограничения MVP

- Один JSON-контракт и одна реализация используются RPC и CLI.
- Review UI не обращается напрямую к MCP или host-local портам.
- CLI не используется как межплагиновый транспорт.
- Внешние dependencies доступны только read-only.
- Произвольные абсолютные пути из frontend не считаются доверенными.
- Результаты для устаревшей revision не отображаются.
- Запись файлов и refactoring остаются отдельными preview/apply операциями и
  не добавляются в `fileReference`.

## Открытые вопросы

- Должен ли `documentId` переживать restart provider или достаточно повторного
  semantic resolve после восстановления attachment?
- Какой лимит locations и размер external document использовать по умолчанию?
- Нужно ли стабилизировать одно имя `fileReference` или после MVP разделить
  transport methods, сохранив общий внутренний request union?
- Достаточно ли оптимизации существующего `plugins.callRpc`, или streaming
  сценарии потребуют отдельного plugin services API?
