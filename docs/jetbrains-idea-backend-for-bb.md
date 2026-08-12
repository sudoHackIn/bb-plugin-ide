# JetBrains IDEA как backend для BB: результаты исследования

## Вывод

IDEA можно запустить локальным фоновым Java-процессом, открыть в нём проект и
дать BB семантический доступ к проекту через MCP. Это подходит для отдельного
первого прототипа **IDE Review** в BB. Не требуется пытаться делать из IDEA
удалённый UI или заменять ей BB-редактор.

Для первых задач целевая поверхность — review: код/diff, диагностика,
навигация по символам и комментарии человеку/агенту. Редактирование и
рефакторинги можно добавить позднее как явные, подтверждаемые действия.

## Что было проверено

### Headless Community IDEA 2025.2

- Загружена IntelliJ IDEA Community 2025.2 для Apple Silicon: build
  `IC-252.23892.409`.
- Обычный launcher `remote-dev-server` в Community-дистрибутиве отсутствует.
  Это не мешает фоновому запуску: IDEA — JVM-приложение, и стартовый Java-класс
  можно вызвать напрямую через bundled JBR.
- В тесте AppStarter `bbBackend` был запущен с
  `-Djava.awt.headless=true` и project path `/Users/vladislav/projects/idea-ai`.
  Проект успешно открылся и дошёл до состояния smart mode
  (`BB_BACKEND_SMART`): PSI и индекс доступны без UI.
- Нужны отдельные config/system/log/cache директории на каждый backend-инстанс;
  это изолирует его от обычной GUI IDEA. При запуске обычная IDEA не должна
  получать команду как external command: иначе она перехватывает запуск вместо
  создания backend-процесса.

### IDE Index MCP Server

Проверялся плагин [jetbrains-index-mcp-plugin](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin).

- Он поднял streamable HTTP MCP сервер в фоне на loopback:
  `http://127.0.0.1:29171/index-mcp/streamable-http`.
- Прошли реальные вызовы `initialize`, `tools/list`, `ide_index_status`,
  `ide_find_definition` и `ide_find_references` на проекте `idea-ai`.
- Индекс к моменту вызова был готов: `isDumbMode: false`, `isIndexing: false`.
- `ide_find_definition` корректно нашёл определение `AcpMcpAvailability`;
  `ide_find_references` вернул пять реальных usages с file/line/column/context.

## Доступные сейчас MCP-возможности

Плагин в стандартной безопасной конфигурации выдал следующие инструменты:

- `ide_find_definition`, `ide_find_references`, `ide_find_implementations`;
- `ide_find_class`, `ide_find_file`, `ide_search_text`;
- `ide_diagnostics`, `ide_index_status`, `ide_project_status`;
- `ide_type_hierarchy`, `ide_call_hierarchy`, `ide_find_super_methods`;
- `ide_sync_files`;
- потенциально изменяющие проект `ide_refactor_rename`, `ide_move_file`,
  `ide_refactor_safe_delete`.

Плагин также заявляет build/test, format, import organization, создание файла
и более широкие refactoring-инструменты, но существенная часть выключена
дефолтной политикой. Их не следует открывать агенту без отдельных разрешений
и UX с preview/подтверждением.

## Проблемы и ограничения

### Совместимость версии

Последний проверенный релиз плагина `5.5.0` объявляет `since-build=253`, то
есть официально рассчитан на IDEA 2025.3, а не Community 2025.2/build 252.

Для исследования был сделан только локальный compatibility probe: у zip
плагина переписан `since-build` на 252, из-за чего подпись стала недействительна.
В этом виде сервер и базовые navigation-инструменты заработали, но это **не
производственный вариант**.

Попытка собрать текущий source плагина против 2025.2 упёрлась в несовпадение
Kotlin serialization/runtime платформы. Следствие: для продукта либо
использовать поддерживаемую IDEA 2025.3+, либо поддерживать отдельный fork и
его совместимость с 2025.2.

### Безопасность и сеть

- MCP endpoint слушает loopback и не имеет аутентификации. Его нельзя
  публиковать через `bb connect expose` и нельзя открывать в сеть.
- Backend/host daemon BB на той же машине должен проксировать или вызывать
  endpoint локально. Для multi-host потребуется host-daemon primitive для
  запуска, health-check, остановки и локального proxy к IDE backend.
- В тесте порт 29170 оказался занят GUI IDEA, поэтому backend использовал
  29171. Порт должен быть конфигурируемым и выделяться на экземпляр.

### Практическая эксплуатация

- Первый импорт Gradle в sandbox дал ошибку доступа к lock-файлу в `~/.gradle`.
  Это ограничение тестовой sandbox-среды, а не PSI/MCP: навигация и индекс
  продолжили работать. Рабочий backend должен иметь доступный Gradle cache либо
  свой выделенный cache.
- IDEA дорогая по ресурсам: тестовый процесс занимал примерно 1.5 GB. Его
  lifecycle надо связать с BB thread/environment: start при необходимости,
  reuse на проект, graceful stop/idle timeout.
- Нужны exclusions для больших директорий (`node_modules`, build outputs,
  generated caches). Ранее проект с миллионами файлов мог сделать индексацию
  очень долгой и бесполезной.

## О чём договорились: BB IDE Review

Это не попытка перенести полноценную IDEA в BB. Первый инкремент —
read-only семантическая review-поверхность в правой панели BB:

1. Исходник и diff (например, Monaco в read-only режиме).
2. Diagnostics с inline-маркерами и переходом к месту ошибки.
3. Definition, usages, implementations, type/call hierarchy.
4. Структура файла и поиск символов/файлов.
5. Статус IDEA index и кнопка refresh/sync после агентских изменений.
6. Build/test summary с переходом из ошибки к коду. Буквальные Gradle-команды
   можно пока запускать обычным BB terminal; IDE build модель — отдельное
   улучшение.

### Комментарии как основной цикл

Пользователь выделяет диапазон или символ в review-панели и создаёт обычный
комментарий BB, который приходит агенту как задача. Комментарий хранит:

- file path и line range;
- diff hunk/revision, если замечание относится к изменению;
- semantic anchor (qualified symbol / элемент PSI), когда он доступен;
- статус `open`, `addressed` или `resolved`.

Нужны три формы: inline-комментарий к строкам, комментарий к symbol и общий
review-комментарий к файлу/изменению. Semantic anchor позволит восстанавливать
цель после сдвига строк. После изменения агентом BB показывает новый diff,
пользователь закрывает или переоткрывает замечание.

## Что потребуется реализовать вокруг текущего MCP

1. **Lifecycle helper на host:** `start`, `status`, `restart`, `stop`, лог,
   port allocation, отдельные IDEA dirs и ожидание smart mode.
2. **BB integration:** backend RPC/agent tools, клиент streamable-HTTP MCP,
   project-to-backend registry и обработка offline/indexing состояний.
3. **Review UI:** правый tab, read-only code/diff viewer, navigation results,
   diagnostics decorations и создание thread comments с anchors.
4. **Multi-host contract:** сервер BB не должен напрямую обращаться к
   localhost чужой машины. Нужна команда host daemon и, при изменении её
   контракта, bump `HOST_DAEMON_PROTOCOL_VERSION`.
5. **File freshness:** после правок агента вызвать `ide_sync_files`; при
   необходимости пересчитать diagnostics/anchors.
6. **Политика разрешений:** read-only tools по умолчанию; будущие rename/move/
   format/delete — только по явному user action, с preview затронутых файлов и
   подтверждением.

## Чего не хватает в текущем проверенном MCP-наборе

- Стабильно поддерживаемого релиза плагина для Community 2025.2.
- Контроля lifecycle IDEA и health API — это ответственность BB wrapper, не
  MCP плагина.
- Аутентификации и безопасного межмашинного транспорта.
- Прямого протокола для review comments, diff anchors и их reconciliation:
  это должен реализовать BB.
- Полного file-structure/symbol API в фактически выданном списке инструментов
  (в README он заявлен шире, чем включённый default tool set).
- Нормализованных build/test results в UI-формате; часть операций зависит от
  импортированной IDE Gradle/Maven/JPS модели.
- Надёжного API для semantic diff и stable PSI IDs между перезапусками; для
  начала можно хранить qualified symbol + signature + file/range и делать
  best-effort re-resolution через definition/search.

## Предлагаемый порядок

1. Взять поддерживаемую версию IDEA/плагина (предпочтительно 2025.3+), а
   эксперимент с 2025.2 оставить только диагностическим доказательством.
2. Собрать отдельный локальный `jb-backend` lifecycle helper.
3. Сделать BB plugin с read-only **IDE Review** tab и inline comments агенту.
4. Добавить diagnostics/navigation/refs и refresh after agent edit.
5. Лишь затем оценить спрос на форматирование и безопасные write-refactorings.
