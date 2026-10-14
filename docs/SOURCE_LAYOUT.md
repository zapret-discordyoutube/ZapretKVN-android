# Карта исходников

Раскладка Android и Windows выполнена 2026-09-05. Перенос файлов не изменяет
поведение соединения, формат профилей или статус готовности нового WG/AWG
runtime. Старые forwarding/shim-копии не оставлены.

## Android

```text
app/src/main/java/io/github/zapretkvn/android/
├── vpn/                 сервис, контроллер, состояние и recovery VPN
├── engines/
│   ├── singbox/         libbox setup, iterators, selector reconciliation
│   └── hysteria/        runtime/recovery contract
├── network/             Android DNS, underlying network, bootstrap
│   └── probes/          health, latency, ping, external IP
├── platform/            Android TUN/socket adapter и системная VPN policy
├── apps/                обнаружение/выбор приложений, per-app scope, ViewModel
├── diagnostics/         исходные ошибки, журнал, метрики и экспорт
├── profiles/            хранение и редактирование профилей
├── importer/            импорт ключей, QR и подписок
├── config/, routing/    native JSON и правила
├── updates/             обновления приложения
└── ui/                  AppRoot, ZapretApp (редактор JSON), nav/, UiSettingsStore и
                         адаптеры `*Route.kt`: связывают ViewModel/AppDependencies/
                         VpnController с экранами из :feature:*

core/
├── model/               типы значений, которые читает UI: состояние VPN, профили,
│                        маршруты, приложения, диагностика, настройки, обновления
│                        (пакеты `io.github.zapretkvn.android.*` сохранены); без Compose
├── ui-model/            чистые форматтеры и тексты (без Android; читает только :core:model)
└── designsystem/        тема, токены, общие Compose-компоненты, иконки, флаги,
                         release notes, PresetPicker, DiagnosticShareButton

feature/                 экраны, презентеры и тесты вкладок; зависят только от :core:*
├── home/                Главная + плашка обновления (`ui.home`, `ui.update`)
├── servers/             Серверы и Профили (`ui.servers`)
├── routing/             Маршруты и выбор приложений (`ui.routing`, `ui.apps`)
└── settings/            Настройки и их страницы (`ui.settings`)
```

`ZapretVpnService` и `ZapretQuickSettingsTileService` остаются в пакете `vpn`:
их имена используются Android, поэтому перенос внутренностей не меняет
системные ComponentName, applicationId, профили и сохранённые настройки.

Четыре движка сохраняют прежние границы: sing-box исполняет routing/DNS и владеет
единственным системным TUN; Xray, Hysteria и Amnezia WG/AWG обслуживают свой
transport. Встроенные Go-адаптеры изменяются через `core-patches`. Пустые
Kotlin-пакеты Xray/Amnezia не создаются. Для движков и рантайма отдельные
Gradle-модули тоже не создаются; исключения — `core/model`, UI-модули
`core/ui-model`, `core/designsystem` и `feature/*`: они не знают про сервис VPN,
сторы, ViewModel и `AppContainer`.

Существующие `app-updater`, `network-bootstrap`, `wireguard-import`
остаются независимыми библиотеками. Тесты перенесённых компонентов также
распределены по соответствующим пакетам.

## Windows

В `xray_fluent` оставлены только корневые `__init__.py` и `constants.py`.
Код сгруппирован по `application`, `engines/<core>`, `profiles`, `importer`,
`diagnostics`, `network`, `platform/windows`, `updates`, `ui`.
Go-реле — самостоятельный модуль `runtime/amnezia`.

## Проверка структуры

`scripts/verify-project.sh`, `:app:testDebugUnitTest`,
`:app:assembleDebugAndroidTest`. Windows выполняет свой полный host-набор.
ADB необязателен; сборка instrumentation APK не считается device-прогоном.
Публикация stable и функциональная приёмка нового WG/AWG — отдельные gates.

## Модули Compose-интерфейса

```text
:app ──► :feature:{home,servers,routing,settings} ──► :core:designsystem ──► :core:ui-model ──► :core:model
:app ──► :app-updater ──► :core:model
:app ──► :core:{designsystem,ui-model,model}, :network-bootstrap, :wireguard-import
```

- `:core:model` — только данные, enum, sealed-типы и тривиальные чистые функции
  (форматирование состояния, `VpnErrorExplainer`, каталог сервисов). Пакеты прежние,
  поэтому импорты в `:app` не менялись. Типы обновлений (`UpdateState`,
  `UpdateCandidate`, …) вынесены из `:app-updater`, он зависит от `:core:model`.
- Если тип смешивал данные и логику, данные вынесены в `core/model`, а логика осталась
  в `:app` расширением (`PersistedServerLatency.hint()`) или реализацией интерфейса
  (`ImportPreviewState : ImportPreview`).
- `:feature:*` содержат `*Screen`, презентеры/мапперы и их JVM-тесты. `*Route.kt` и
  всё, что требует ViewModel, `AppDependencies`, `VpnController`, `BuildConfig`
  (лаунчеры импорта QR, `AppPickerScreen`, `SettingsPageRoutes`), остаётся в `:app`.
  Сведения о сборке экранам настроек передаёт `AppInfo` (создаётся в `:app`).
- `scripts/verify-project.sh` запрещает `:feature:*` и `:core:*` зависеть от `:app`
  (проверка `build.gradle.kts`) и импортировать из `feature/**` рантайм VPN, ViewModel,
  `AppContainer`, `AppDependencies`, `BuildConfig`.
- JVM-тесты запускает `./gradlew testDebugUnitTest` (все модули); задача
  `:app:testDebugUnitTest` по-прежнему работает.
