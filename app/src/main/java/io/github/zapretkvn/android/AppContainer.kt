package io.github.zapretkvn.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.zapretkvn.android.apps.AndroidPackageAvailability
import io.github.zapretkvn.android.apps.AppCatalog
import io.github.zapretkvn.android.apps.AppSelectionStore
import io.github.zapretkvn.android.apps.AppsViewModel
import io.github.zapretkvn.android.apps.VpnAppScopePreflight
import io.github.zapretkvn.android.config.LibboxConfigValidator
import io.github.zapretkvn.android.diagnostics.AppCrashStore
import io.github.zapretkvn.android.diagnostics.DiagnosticExporter
import io.github.zapretkvn.android.engines.singbox.LibboxRuntime
import io.github.zapretkvn.android.importer.AndroidImportReader
import io.github.zapretkvn.android.importer.DeviceIdentity
import io.github.zapretkvn.android.importer.HttpSubscriptionFetcher
import io.github.zapretkvn.android.importer.SubscriptionIdentity
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import io.github.zapretkvn.android.network.BootstrapCache
import io.github.zapretkvn.android.network.BootstrapResolver
import io.github.zapretkvn.android.network.VpnNetworkProvider
import io.github.zapretkvn.android.network.geo.DohHostAddressResolver
import io.github.zapretkvn.android.network.geo.GeoIpCountry
import io.github.zapretkvn.android.network.geo.ServerCountryStore
import io.github.zapretkvn.android.network.probes.IcmpPingProbe
import io.github.zapretkvn.android.network.probes.ProxyBootstrapper
import io.github.zapretkvn.android.network.probes.ServerLatencyStore
import io.github.zapretkvn.android.network.probes.VpnExternalIpProbe
import io.github.zapretkvn.android.network.probes.services.AndroidServiceProbePaths
import io.github.zapretkvn.android.network.probes.VpnHealthPipeline
import io.github.zapretkvn.android.network.probes.VpnLivenessProbe
import io.github.zapretkvn.android.network.probes.VpnThroughputProbe
import io.github.zapretkvn.android.profiles.ProfileStore
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.routing.RoutingPolicyStore
import io.github.zapretkvn.android.routing.RoutingViewModel
import io.github.zapretkvn.android.routing.RuleSetAssetManager
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.updates.AndroidApkUpdateVerifier
import io.github.zapretkvn.android.updates.AndroidUpdateNotifier
import io.github.zapretkvn.android.updates.AppForeground
import io.github.zapretkvn.android.updates.AppUpdateVpnFallback
import io.github.zapretkvn.android.updates.ForgejoHttpsClient
import io.github.zapretkvn.android.updates.ForgejoUpdateSource
import io.github.zapretkvn.android.updates.PackageInstallerUpdateInstaller
import io.github.zapretkvn.android.updates.SharedUpdatePrefs
import io.github.zapretkvn.android.updates.UpdateCheckJobService
import io.github.zapretkvn.android.updates.UpdateController
import io.github.zapretkvn.android.updates.UpdateDirectConnections
import io.github.zapretkvn.android.updates.UpdateEnvironment
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnController
import java.io.File
import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(
    context: Context,
    val appCrashStore: AppCrashStore,
) {
    val appContext: Context = context.applicationContext
    val libboxRuntime = LibboxRuntime(appContext)
    val configValidator = LibboxConfigValidator()
    val profileStore = ProfileStore(
        root = File(appContext.filesDir, "profiles"),
        validator = configValidator,
    )
    val uiSettingsStore = UiSettingsStore(appContext)
    val importReader = AndroidImportReader(appContext)
    val subscriptionFetcher = HttpSubscriptionFetcher(
        device = DeviceIdentity(
            os = "Android",
            // Отдельные OEM пишут в эти поля не-ASCII, а заголовок такое не принимает.
            osVersion = SubscriptionIdentity.headerSafeValue(Build.VERSION.RELEASE.orEmpty(), "Android"),
            model = SubscriptionIdentity.headerSafeValue(Build.MODEL.orEmpty(), "Android"),
            locale = SubscriptionIdentity.headerSafeValue(
                java.util.Locale.getDefault().toLanguageTag(),
                "ru-RU",
            ),
        ),
        appVersion = BuildConfig.VERSION_NAME,
        // Fetch the subscription over a non-VPN network first, even while the
        // tunnel is up: the subscription host answers the same directly, and a
        // degraded tunnel egress must not turn a working link into a failure.
        // Falls back to the default (VPN) path only when the direct attempt
        // returns no answer.
        openConnection = { url, direct ->
            val connection =
                if (direct) nonVpnNetwork(appContext)?.openConnection(url) else url.openConnection()
            connection as? HttpURLConnection
        },
        directAvailable = { nonVpnNetwork(appContext) != null },
    )
    val subscriptionSourceStore = SubscriptionSourceStore(
        File(appContext.noBackupFilesDir, "subscriptions"),
    )
    val appSelectionStore = AppSelectionStore(appContext)
    val appCatalog = AppCatalog(appContext)
    val vpnAppScopePreflight = VpnAppScopePreflight(
        ownPackageName = appContext.packageName,
        packageAvailability = AndroidPackageAvailability(appContext.packageManager),
    )
    val vpnController = VpnController(appContext, appCrashStore.read())
    val diagnosticExporter = DiagnosticExporter(
        context = appContext,
        settingsStore = uiSettingsStore,
        vpnController = vpnController,
        crashStore = appCrashStore,
    ).also(DiagnosticExporter::cleanupStaleFiles)
    val appForeground = AppForeground(appContext as Application)
    // Обновления идут напрямую даже при включённом VPN; через VPN — только если прямой путь не сработал.
    private val updateDirectConnections =
        UpdateDirectConnections { url -> nonVpnNetwork(appContext)?.openConnection(url) }
    val updateController = UpdateController(
        updatesRoot = File(appContext.cacheDir, "updates"),
        currentVersionName = BuildConfig.VERSION_NAME,
        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
        clientFactory = { route -> ForgejoHttpsClient(updateDirectConnections, route) },
        sourceFactory = { client ->
            ForgejoUpdateSource(BuildConfig.UPDATE_REPOSITORY, appContext.packageName, client)
        },
        verifier = AndroidApkUpdateVerifier(appContext),
        vpnFallback = AppUpdateVpnFallback(appContext, uiSettingsStore, vpnController),
        installer = PackageInstallerUpdateInstaller(appContext),
        environment = UpdateEnvironment(
            vpnActive = { vpnController.state.value is VpnConnectionState.Connected },
            isMetered = { isMeteredNetwork(appContext) },
            canInstallUnattended = { canInstallUpdateUnattended(appContext, appForeground, vpnController) },
            appInForeground = { appForeground.isForeground },
            installedApk = File(appContext.applicationInfo.sourceDir),
            archiveVersionCode = { file -> archiveVersionCode(appContext, file) },
        ),
        notifier = AndroidUpdateNotifier(appContext),
        prefs = SharedUpdatePrefs(appContext),
    )
    val bootstrapCache = BootstrapCache(File(appContext.noBackupFilesDir, "network"))
    val ruleSetAssetManager = RuleSetAssetManager(appContext)
    val routingPolicyStore = RoutingPolicyStore(appContext)
    val proxyBootstrapper = ProxyBootstrapper(BootstrapResolver(), bootstrapCache)
    private val vpnNetworkProvider = VpnNetworkProvider(appContext)
    val vpnHealthPipeline = VpnHealthPipeline(vpnNetworkProvider)
    val vpnExternalIpProbe = VpnExternalIpProbe(vpnNetworkProvider)
    val vpnThroughputProbe = VpnThroughputProbe(vpnNetworkProvider)
    val vpnLivenessProbe = VpnLivenessProbe(vpnNetworkProvider)
    val icmpPingProbe = IcmpPingProbe()
    internal val serviceProbePaths = AndroidServiceProbePaths(vpnNetworkProvider)

    /** Фоновые записи хранилищ, живущие вместе с процессом. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val serverLatencyStore = ServerLatencyStore(
        File(appContext.noBackupFilesDir, "server-latency.json"),
        backgroundScope,
    )

    /**
     * Страна сервера по его адресу: офлайн-таблица GeoIP из ассетов плюс разовое разрешение
     * имён по DoH (раз в 7 суток на хост). VPN-рантайм не затрагивается.
     */
    val serverCountryStore = ServerCountryStore(
        file = File(appContext.noBackupFilesDir, "network/server-countries.json"),
        geo = GeoIpCountry { appContext.assets.open("geoip/country.bin") },
        resolver = DohHostAddressResolver(physicalNetwork = { nonVpnNetwork(appContext) }),
        scope = backgroundScope,
    )

    init {
        // Суточная проверка обновлений не переживает перезагрузку (RECEIVE_BOOT_COMPLETED не запрашивается),
        // поэтому ставится при каждом старте процесса; уже стоящее задание не трогается.
        backgroundScope.launch { UpdateCheckJobService.schedule(appContext) }
    }

    val profilesViewModelFactory: ProfilesViewModel.Factory
        get() = ProfilesViewModel.Factory(
            profileStore,
            uiSettingsStore,
            configValidator,
            importReader,
            subscriptionFetcher,
            subscriptionSourceStore,
            vpnController,
            bootstrapCache,
            ruleSetAssetManager,
            serverLatencyStore,
            serverCountryStore,
        )

    val appsViewModelFactory: AppsViewModel.Factory
        get() = AppsViewModel.Factory(appSelectionStore, appCatalog)

    val routingViewModelFactory: RoutingViewModel.Factory
        get() = RoutingViewModel.Factory(
            profileStore,
            uiSettingsStore,
            ruleSetAssetManager,
            routingPolicyStore,
            vpnController,
        )
}

/**
 * The current non-VPN network with internet, or null when only a VPN (or no
 * usable network) is present. `allNetworks` keeps listing the underlying
 * physical transports while a VPN is active, so filtering out TRANSPORT_VPN
 * yields the path that bypasses the tunnel for a direct subscription fetch or
 * updater request. A validated network wins: with Wi-Fi and cellular both up,
 * the first listed one may have no working internet.
 */
@Suppress("DEPRECATION") // allNetworks is the only synchronous way to see the
// underlying physical transports while a VPN holds the default network.
private fun nonVpnNetwork(context: Context): Network? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val physical = connectivity.allNetworks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            return@mapNotNull null
        }
        network to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    return (physical.firstOrNull { it.second } ?: physical.firstOrNull())?.first
}

/** Сеть с лимитом трафика. Физическая сеть важнее VPN: именно по ней пойдёт загрузка. */
private fun isMeteredNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val network = nonVpnNetwork(context) ?: connectivity.activeNetwork ?: return true
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return true
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/**
 * Обновление можно ставить без участия пользователя, только если это не оборвёт его: приложение не на экране,
 * VPN выключен, Android 12+ (USER_ACTION_NOT_REQUIRED), есть разрешение на установку и установщик по записи — мы.
 */
private fun canInstallUpdateUnattended(
    context: Context,
    foreground: AppForeground,
    vpn: VpnController,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || foreground.isForeground) return false
    val vpnIdle = vpn.state.value.let { it is VpnConnectionState.Stopped || it is VpnConnectionState.Error }
    if (!vpnIdle || !context.packageManager.canRequestPackageInstalls()) return false
    return runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName == context.packageName
    }.getOrDefault(false)
}

private fun archiveVersionCode(context: Context, file: File): Long? =
    context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.let(PackageInfoCompat::getLongVersionCode)
