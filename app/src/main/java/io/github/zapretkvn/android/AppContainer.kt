package io.github.zapretkvn.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
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
import io.github.zapretkvn.android.network.probes.IcmpPingProbe
import io.github.zapretkvn.android.network.probes.ProxyBootstrapper
import io.github.zapretkvn.android.network.probes.ServerLatencyStore
import io.github.zapretkvn.android.network.probes.VpnExternalIpProbe
import io.github.zapretkvn.android.network.probes.VpnHealthPipeline
import io.github.zapretkvn.android.network.probes.VpnThroughputProbe
import io.github.zapretkvn.android.profiles.ProfileStore
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.routing.RoutingPolicyStore
import io.github.zapretkvn.android.routing.RoutingViewModel
import io.github.zapretkvn.android.routing.RuleSetAssetManager
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.updates.AndroidUpdateInstallIntentFactory
import io.github.zapretkvn.android.updates.AppUpdateVpnFallback
import io.github.zapretkvn.android.updates.UpdateController
import io.github.zapretkvn.android.vpn.VpnController
import java.io.File
import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val updateController = UpdateController(
        context = appContext,
        repository = BuildConfig.UPDATE_REPOSITORY,
        currentVersionName = BuildConfig.VERSION_NAME,
        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
        vpnFallback = AppUpdateVpnFallback(appContext, uiSettingsStore, vpnController),
        installIntentFactory = AndroidUpdateInstallIntentFactory(appContext),
    )
    val bootstrapCache = BootstrapCache(File(appContext.noBackupFilesDir, "network"))
    val ruleSetAssetManager = RuleSetAssetManager(appContext)
    val routingPolicyStore = RoutingPolicyStore(appContext)
    val proxyBootstrapper = ProxyBootstrapper(BootstrapResolver(), bootstrapCache)
    private val vpnNetworkProvider = VpnNetworkProvider(appContext)
    val vpnHealthPipeline = VpnHealthPipeline(vpnNetworkProvider)
    val vpnExternalIpProbe = VpnExternalIpProbe(vpnNetworkProvider)
    val vpnThroughputProbe = VpnThroughputProbe(vpnNetworkProvider)
    val icmpPingProbe = IcmpPingProbe()

    /** Фоновые записи хранилищ, живущие вместе с процессом. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val serverLatencyStore = ServerLatencyStore(
        File(appContext.noBackupFilesDir, "server-latency.json"),
        backgroundScope,
    )

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
 * yields the path that bypasses the tunnel for one direct subscription fetch.
 */
@Suppress("DEPRECATION") // allNetworks is the only synchronous way to see the
// underlying physical transports while a VPN holds the default network.
private fun nonVpnNetwork(context: Context): Network? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return connectivity.allNetworks.firstOrNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
