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
import io.github.zapretkvn.android.network.BrowserTransport
import io.github.zapretkvn.android.network.VpnNetworkProvider
import io.github.zapretkvn.android.network.geo.DohHostAddressResolver
import io.github.zapretkvn.android.network.geo.GeoIpCountry
import io.github.zapretkvn.android.network.geo.ServerCountryStore
import io.github.zapretkvn.android.network.probes.ProxyBootstrapper
import io.github.zapretkvn.android.network.probes.ServerLatencyStore
import io.github.zapretkvn.android.network.probes.VpnExternalIpProbe
import io.github.zapretkvn.android.network.probes.services.AndroidDirectNetworkSource
import io.github.zapretkvn.android.network.probes.services.AndroidServiceProbePaths
import io.github.zapretkvn.android.network.probes.services.DirectServiceChecker
import io.github.zapretkvn.android.network.probes.services.ServiceCatalog
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
import io.github.zapretkvn.android.updates.ReleaseWaitClient
import io.github.zapretkvn.android.updates.SharedUpdatePrefs
import io.github.zapretkvn.android.updates.UpdateChannel
import io.github.zapretkvn.android.updates.UpdateCheckJobService
import io.github.zapretkvn.android.profiles.AndroidImportInput
import io.github.zapretkvn.android.profiles.ImportService
import io.github.zapretkvn.android.profiles.SubscriptionAutoRefresher
import io.github.zapretkvn.android.profiles.UiSettingsStorePort
import io.github.zapretkvn.android.profiles.VpnControllerPort
import io.github.zapretkvn.android.updates.UpdateController
import io.github.zapretkvn.android.updates.UpdateDirectConnections
import io.github.zapretkvn.android.updates.UpdateEnvironment
import io.github.zapretkvn.android.network.DirectNetworkFacts
import io.github.zapretkvn.android.network.UnderlyingNetworkHint
import io.github.zapretkvn.android.network.chooseDirectNetwork
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnController
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
        tunnelConnection = { url -> vpnNetwork(appContext)?.openConnection(url) as? HttpURLConnection },
        // Напрямую стеком Chromium — выключено до исправления падения Cronet (c22):
        // browserDirect = { url, headers -> browserSubscriptionReply(url, headers) },
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
    val vpnHealthPipeline = VpnHealthPipeline(vpnNetworkProvider, vpnController.probeLane)
    val vpnExternalIpProbe = VpnExternalIpProbe(vpnNetworkProvider)
    val vpnThroughputProbe = VpnThroughputProbe(vpnNetworkProvider, vpnController.probeLane)
    val vpnLivenessProbe = VpnLivenessProbe(vpnNetworkProvider)
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

    /**
     * Проверка сервисов без VPN: пока VPN остановлен, карточка на Главной проверяет сервисы
     * прямым путём по нажатию. Результаты только в памяти и стираются, когда VPN подключается.
     */
    internal val directServiceChecker = DirectServiceChecker(
        scope = backgroundScope,
        lane = vpnController.probeLane,
        source = AndroidDirectNetworkSource(appContext),
        definitions = { ServiceCatalog.resolve(vpnController.enabledServices.value) },
    )

    init {
        // Выбор сервисов для проверки и состояние VPN доходят до проб без обращения к хранилищу.
        backgroundScope.launch {
            uiSettingsStore.settings.map { it.serviceChecks }.distinctUntilChanged()
                .collect(vpnController::setEnabledServices)
        }
        backgroundScope.launch {
            vpnController.state.map { it is VpnConnectionState.Stopped }.distinctUntilChanged()
                .collect(directServiceChecker::onVpnIdle)
        }
        // Фоновое задание не переживает перезагрузку (RECEIVE_BOOT_COMPLETED не запрашивается),
        // поэтому ставится при каждом старте процесса; уже стоящее с тем же периодом не трогается,
        // смена «Автообновления подписок» перестраивает период.
        backgroundScope.launch {
            uiSettingsStore.settings.map { it.subscriptionAutoUpdateHours }.distinctUntilChanged()
                .collect { hours -> UpdateCheckJobService.schedule(appContext, hours) }
        }
        // Слушатель службы очереди: пока VPN включён (процесс точно жив) и выбран канал Stable, новая
        // версия приходит за секунды после выхода, а не со следующей суточной проверкой.
        backgroundScope.launch {
            combine(
                vpnController.state.map { it is VpnConnectionState.Connected },
                uiSettingsStore.settings.map { it.updateChannel == UpdateChannel.Stable },
            ) { connected, stable -> connected && stable && STABLE_VERSION.matches(BuildConfig.VERSION_NAME) }
                .distinctUntilChanged()
                .collectLatest { listen -> if (listen) releaseWaitClient().run() }
        }
    }

    private fun releaseWaitClient() = ReleaseWaitClient(
        currentVersion = BuildConfig.VERSION_NAME,
        http = { url, readTimeout -> releaseWaitGet(url, readTimeout) },
        report = updateController::pendingServerReport,
        reportDelivered = updateController::serverReportDelivered,
        onGrant = { version -> updateController.onServerGrant(version) },
    )

    /**
     * Вопрос службе очереди напрямую, мимо VPN (как и все запросы обновлений). Если прямой путь не
     * сработал (у телефона бывает несколько сетей, и выбранная может не отвечать) — тот же крошечный
     * вопрос обычным путём. Служба держит ответ минутами, поэтому отмена закрывает соединение сразу.
     */
    private suspend fun releaseWaitGet(url: String, readTimeoutMillis: Int): String = withContext(Dispatchers.IO) {
        val target = URL(url)
        val direct = nonVpnNetwork(appContext)
        if (direct != null) {
            try {
                return@withContext releaseWaitRead(direct.openConnection(target) as HttpURLConnection, readTimeoutMillis)
            } catch (failure: IOException) {
                ensureActive()
                if (failure is ReleaseWaitAnswerException) throw failure
            }
        }
        releaseWaitRead(target.openConnection() as HttpURLConnection, readTimeoutMillis)
    }

    private suspend fun releaseWaitRead(connection: HttpURLConnection, readTimeoutMillis: Int): String {
        val closeOnCancel = coroutineContext.job.invokeOnCompletion { connection.disconnect() }
        try {
            connection.connectTimeout = RELEASE_WAIT_CONNECT_MILLIS
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "ZapretKVN-Android/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw ReleaseWaitAnswerException("HTTP $code")
            connection.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > RELEASE_WAIT_MAX_BYTES) throw ReleaseWaitAnswerException("ответ службы слишком большой")
                }
                return buffer.toString(Charsets.UTF_8.name())
            }
        } finally {
            closeOnCancel.dispose()
            connection.disconnect()
        }
    }

    /** Служба ответила, но не так: другим путём спрашивать бессмысленно. */
    private class ReleaseWaitAnswerException(message: String) : IOException(message)

    /** Обновление подписок суточным заданием [UpdateCheckJobService] тем же путём, что ручное «Обновить (без кэша)». */
    internal val subscriptionAutoRefresher: SubscriptionAutoRefresher by lazy {
        SubscriptionAutoRefresher(
            service = ImportService(
                store = profileStore,
                validator = configValidator,
                input = AndroidImportInput(importReader),
                subscriptionFetcher = subscriptionFetcher,
                subscriptionSourceStore = subscriptionSourceStore,
                settings = UiSettingsStorePort(uiSettingsStore),
                vpn = VpnControllerPort(vpnController),
                installRuleSets = { withContext(Dispatchers.IO) { ruleSetAssetManager.ensureInstalled() } },
            ),
            store = profileStore,
            subscriptionSourceStore = subscriptionSourceStore,
        )
    }

    private companion object {
        /** Служба очереди знает только Stable-версии вида 0.5.5; у Beta суффикс, их она не раздаёт. */
        val STABLE_VERSION = Regex("""^\d+\.\d+\.\d+$""")
        const val RELEASE_WAIT_CONNECT_MILLIS = 15_000
        const val RELEASE_WAIT_MAX_BYTES = 16 * 1024
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
/** Ответ подписки через браузерный транспорт; null — транспорта в сборке нет. */
private fun browserSubscriptionReply(url: String, headers: Map<String, String>): HttpSubscriptionFetcher.Reply? {
    val limit = HttpSubscriptionFetcher.MAX_IMPORT_BYTES
    val response = try {
        BrowserTransport.fetch(
            url = url,
            headers = headers.toList(),
            timeoutMillis = 15_000,
            maxBytes = limit + 1L,
        )
    } catch (_: BrowserTransport.UnavailableException) {
        return null
    }
    return HttpSubscriptionFetcher.Reply(
        status = response.status,
        headerNames = response.headers.mapTo(mutableSetOf()) { it.first },
        location = response.redirectLocation ?: response.header("location"),
        body = response.body.takeIf { !response.truncated && it.size <= limit },
    )
}

/** Поднятая VPN-сеть: соединение, привязанное к ней, идёт через туннель при любом выборе приложений. */
private fun vpnNetwork(context: Context): Network? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return connectivity.allNetworks.firstOrNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

private fun nonVpnNetwork(context: Context): Network? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val networks = connectivity.allNetworks
    val facts = networks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            return@mapNotNull null
        }
        DirectNetworkFacts(
            id = network.networkHandle,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            suspended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
            transportRank = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                else -> 0
            },
            notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
    // Сеть, через которую работает VPN, потом сеть системы по умолчанию (при выключенном VPN).
    val preferred = listOfNotNull(UnderlyingNetworkHint.network, connectivity.activeNetwork).map { it.networkHandle }
    val chosen = chooseDirectNetwork(facts, preferred) ?: return null
    return networks.firstOrNull { it.networkHandle == chosen }
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
