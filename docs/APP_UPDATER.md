# app-updater

An isolated module for APK updates. It does not depend on the UI, profiles, libbox or the VPN
implementation in `app`.

- `ForgejoUpdateSource.kt` — an HTTPS client for a single route (Range, a neutral User-Agent, a redirect cache) and the parsing of Forgejo Releases.
- `UpdateModels.kt` — release metadata, the states (`Checking`, `Downloading` with the savings, `Ready`/`Installing`, `Failure`) and a strict JSON parser.
- `ZipDirectory.kt`, `DeltaPlan.kt` — pure parsing of ZIP/APK (EOCD, the central directory, the APK Signing Block), the plan for reusing entries and range merging; no network and no Android.
- `ApkDownloader.kt` — a delta download over HTTP Range against the installed APK, a full download that resumes `.part`, SHA-256 verification.
- `UpdateRouter.kt` — route selection (direct / through a temporary VPN) without intermediate messages; a race when the VPN is on; the remembered route in `updater_prefs` (`UpdatePrefs.kt`).
- `ApkUpdateVerifier.kt` — the package/version/minSdk/signing policy.
- `UpdateController.kt` — the check, the automatic download (Stable), the mobile network policy (`UpdateAutoDownloadPolicy`), the hand-over to the installer, the cache cleanup; the state for the banner and the settings page.
- `UpdateContracts.kt`, `UpdateVpnFallback.kt` — narrow callback contracts: the installer, notifications, the environment (VPN/network/screen), the VPN lease.

The contracts are implemented in `app/src/main/java/io/github/zapretkvn/android/updates/`
(`AppUpdateVpnFallback`, `PackageInstallerUpdateInstaller`, `UpdateInstallReceiver`, `UpdateCheckJob`,
`AndroidUpdateNotifier`), because only the product module owns `VpnService`, `PackageInstaller`,
notifications and `JobScheduler`.
