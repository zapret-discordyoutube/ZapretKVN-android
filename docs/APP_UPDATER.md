# app-updater

Изолированный модуль обновления APK. Он не зависит от UI, профилей, libbox или
реализации VPN в `app`.

- `ForgejoUpdateSource.kt` — HTTPS-клиент одного маршрута (Range, нейтральный User-Agent, кэш редиректа) и разбор Forgejo Releases.
- `UpdateModels.kt` — release metadata, состояния (`Checking`, `Downloading` с экономией, `Ready`/`Installing`, `Failure`) и строгий JSON parser.
- `ZipDirectory.kt`, `DeltaPlan.kt` — чистый разбор ZIP/APK (EOCD, центральный каталог, APK Signing Block), план повторного использования записей и склейка диапазонов; без сети и Android.
- `ApkDownloader.kt` — дельта-загрузка по HTTP Range против установленного APK, полная загрузка с докачкой `.part`, проверка SHA-256.
- `UpdateRouter.kt` — выбор маршрута (прямой / через временный VPN) без промежуточных сообщений; гонка при включённом VPN; запомненный маршрут в `updater_prefs` (`UpdatePrefs.kt`).
- `ApkUpdateVerifier.kt` — package/version/minSdk/signing policy.
- `UpdateController.kt` — проверка, автоскачивание (Stable), политика мобильной сети (`UpdateAutoDownloadPolicy`), передача установщику, cache cleanup; состояние для плашки и страницы настроек.
- `UpdateContracts.kt`, `UpdateVpnFallback.kt` — узкие callback-контракты: установщик, уведомления, окружение (VPN/сеть/экран), VPN lease.

Реализация контрактов находится в `app/src/main/java/io/github/zapretkvn/android/updates/`
(`AppUpdateVpnFallback`, `PackageInstallerUpdateInstaller`, `UpdateInstallReceiver`, `UpdateCheckJob`,
`AndroidUpdateNotifier`), потому что только product-модуль владеет `VpnService`, `PackageInstaller`,
уведомлениями и `JobScheduler`.
