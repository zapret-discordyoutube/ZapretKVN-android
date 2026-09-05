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
└── ui/                  Compose-интерфейс и presentation
```

`ZapretVpnService` и `ZapretQuickSettingsTileService` остаются в пакете `vpn`:
их имена используются Android, поэтому перенос внутренностей не меняет
системные ComponentName, applicationId, профили и сохранённые настройки.

Четыре движка сохраняют прежние границы: sing-box исполняет routing/DNS и владеет
единственным системным TUN; Xray, Hysteria и Amnezia WG/AWG обслуживают свой
transport. Встроенные Go-адаптеры изменяются через `core-patches`. Пустые
Kotlin-пакеты Xray/Amnezia и дополнительные Gradle-модули не создаются.

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
