# app-updater

Изолированный модуль обновления APK. Он не зависит от UI, профилей, libbox или
реализации VPN в `app`.

- `ForgejoUpdateSource.kt` — HTTPS, Forgejo Releases и загрузка.
- `UpdateModels.kt` — release metadata, состояния и строгий JSON parser.
- `ApkUpdateVerifier.kt` — package/version/minSdk/signing policy.
- `UpdateController.kt` — одна проверка, загрузка, cache cleanup и один VPN retry.
- `UpdateVpnFallback.kt` — два узких callback-контракта для VPN lease и installer.

Реализация callback-ов находится в
`app/src/main/java/io/github/zapretkvn/android/updates/AppUpdateVpnFallback.kt`, потому
что только product-модуль владеет `VpnService` и `FileProvider`.
