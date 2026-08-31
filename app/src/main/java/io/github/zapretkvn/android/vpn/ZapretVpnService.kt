package io.github.zapretkvn.android.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.R
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.config.BootstrapConfig
import io.github.zapretkvn.android.config.RuntimeConfigOptions
import io.github.zapretkvn.android.config.RuntimeConfigBuilder
import io.github.zapretkvn.android.config.SelectorGroup
import io.github.zapretkvn.android.diagnostics.EffectiveOverlaySummary
import io.github.zapretkvn.android.diagnostics.CoreDiagnosticBatchCollector
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.diagnostics.DiagnosticRuntimeMap
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.config.RuntimeConfigResult
import io.github.zapretkvn.android.hardening.VpnRuntimeHardening
import io.github.zapretkvn.android.routing.GlobalRoutingPolicy
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.networkbootstrap.BootstrapFailureException
import io.github.zapretkvn.networkbootstrap.CodedFailure
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.RelayDelayProbeHandler
import io.nekohasekai.libbox.RelayDelayProbeResult
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object AutomaticDnsFallbackPolicy {
    /**
     * При strict Private DNS автоматический режим сужается до «DNS Android»:
     * это единственный кандидат, уважающий системный DoT. Профильный DNS и
     * managed DoH подменяли бы выбранный пользователем резолвер, а fail-close
     * заставлял пользователя чинить настройки вручную.
     */
    fun candidates(
        configuredMode: DnsMode,
        hasProfileDns: Boolean,
        strictPrivateDns: Boolean = false,
    ): List<DnsMode> = when {
        configuredMode != DnsMode.Automatic -> listOf(configuredMode)
        strictPrivateDns -> listOf(DnsMode.Android)
        else -> buildList {
            if (hasProfileDns) add(DnsMode.FromJson)
            add(DnsMode.Android)
            add(DnsMode.Secure)
        }
    }

    fun label(mode: DnsMode): String = when (mode) {
        DnsMode.FromJson -> "DNS профиля"
        DnsMode.Android -> "DNS Android"
        DnsMode.Secure -> "защищённый DoH"
        DnsMode.Automatic -> "автоматический DNS"
    }

    suspend fun <T> run(
        candidates: List<DnsMode>,
        onFallback: (from: DnsMode, to: DnsMode, failure: VpnDnsHealthException) -> Unit,
        attempt: suspend (DnsMode) -> T,
    ): T {
        require(candidates.isNotEmpty())
        for ((index, candidate) in candidates.withIndex()) {
            try {
                return attempt(candidate)
            } catch (error: VpnDnsHealthException) {
                if (index == candidates.lastIndex) throw error
                onFallback(candidate, candidates[index + 1], error)
            }
        }
        error("DNS fallback завершился без результата.")
    }
}

class ZapretVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceLock = Mutex()
    private val foregroundActive = AtomicBoolean(false)
    private val stopInProgress = AtomicBoolean(false)
    private val sessionStateLock = Any()
    private val lifecycleJobLock = Any()
    private val lifecycleCommandLock = Any()

    private val container by lazy { (application as ZapretApplication).container }
    private val controller by lazy { container.vpnController }
    @Volatile
    private var activeSession: ActiveSession? = null
    @Volatile
    private var pendingSession: ActiveSession? = null
    private var lifecycleJob: Job? = null
    private var terminalError = false
    private val restartScheduleLock = Any()
    private var networkRestartJob: Job? = null
    private var networkChangeSinceElapsed = 0L
    @Volatile
    private var automationSettings = NetworkAutomationSettings()
    @Volatile
    private var automationOverrideIdentity: String? = null
    @Volatile
    private var pausedAutomation: PausedAutomationSession? = null
    private val pausedAutomationLock = Any()
    private var pausedNetworkObserver: AutoCloseable? = null
    private var automationSettingsJob: Job? = null
    private val currentWifiSsidReader by lazy { CurrentWifiSsidReader(this) }

    /**
     * Монитор сети живёт со сервисом, а не с сессией. Иначе восстановление после
     * отказа было невозможно: сессия закрывалась вместе с callback, и появление
     * рабочей сети через несколько секунд уже некому было увидеть. Заодно
     * перезапуск больше не ждёт повторной доставки capabilities/linkProperties
     * для только что зарегистрированного callback.
     *
     * Экземпляр пересоздаётся, потому что закрытие терминально: между закрытием
     * перед публикацией ошибки и фактическим `onDestroy` пользователь успевает
     * нажать «Подключить».
     */
    private val networkMonitorLock = Any()
    @Volatile
    private var networkMonitorInstance: DefaultNetworkMonitor? = null
    private val networkMonitor: DefaultNetworkMonitor
        get() = synchronized(networkMonitorLock) {
            networkMonitorInstance ?: DefaultNetworkMonitor(this).also { networkMonitorInstance = it }
        }
    private val recoveryLock = Any()
    private var recoveryJob: Job? = null

    /** Подряд идущие автоматические попытки на текущей физической сети. */
    @Volatile
    private var recoveryAttempt = 0

    /** Все автоматические попытки с последнего успешного подключения. */
    @Volatile
    private var recoveryTotalAttempts = 0
    @Volatile
    private var attemptNetworkIdentity: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching { networkMonitor.start() }
        automationSettingsJob = serviceScope.launch {
            container.uiSettingsStore.settings
                .map { it.networkAutomation }
                .distinctUntilChanged()
                .collect { settings ->
                    val changed = automationSettings != settings
                    automationSettings = settings
                    if (changed) reevaluateNetworkAutomation()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundActive.get()) showForeground(ForegroundNotificationState.Preparing)
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
                requestStart(
                    profileId = profileId,
                    startId = startId,
                    updaterRouting = intent.getBooleanExtra(EXTRA_UPDATER_ROUTING, false),
                )
            }
            ACTION_SELECT -> requestSelect(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                groupTag = intent.getStringExtra(EXTRA_GROUP_TAG).orEmpty(),
                outboundTag = intent.getStringExtra(EXTRA_OUTBOUND_TAG).orEmpty(),
                startId = startId,
            )
            ACTION_RESTART -> requestRestart(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                reason = intent.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "Перезапуск VPN" },
                startId = startId,
                noCacheLookup = false,
                updaterRouting = intent.takeIf { it.hasExtra(EXTRA_UPDATER_ROUTING) }
                    ?.getBooleanExtra(EXTRA_UPDATER_ROUTING, false),
            )
            ACTION_CLEAR_DNS_CACHE -> requestClearDnsCache(startId)
            ACTION_PING_GROUP -> requestGroupPing(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                groupTag = intent.getStringExtra(EXTRA_GROUP_TAG).orEmpty(),
                startId = startId,
            )
            ACTION_STOP -> requestStop(startId, null)
            ACTION_RESUME -> requestResumePaused(startId, manualOverride = true)
            else -> {
                val policy = VpnSystemPolicyDetector.detect(this)
                requestStop(startId, policy.blockingMessage, policy)
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        requestStop(
            startId = 0,
            errorMessage = "Разрешение Android VPN отозвано.",
            trigger = "permission_revoked",
        )
        super.onRevoke()
    }

    private fun closeNetworkMonitor() {
        val current = synchronized(networkMonitorLock) {
            networkMonitorInstance.also { networkMonitorInstance = null }
        }
        runCatching { current?.close() }
    }

    override fun onDestroy() {
        cancelScheduledNetworkRestart()
        cancelRecovery()
        clearPausedAutomation()
        automationSettingsJob?.cancel()
        automationSettingsJob = null
        controller.cancelCurrentConnectionDiagnostic()
        cancelLifecycleJob()
        val remaining = detachSessions()
        remaining.forEach(ActiveSession::closeTun)
        runBlocking(Dispatchers.IO) { remaining.forEach(ActiveSession::close) }
        serviceScope.cancel()
        closeNetworkMonitor()
        finishForeground()
        if (!terminalError) {
            controller.publish(controller.currentGeneration(), VpnConnectionState.Stopped)
        }
        super.onDestroy()
    }

    private fun requestStart(profileId: String, startId: Int, updaterRouting: Boolean) {
        val token = synchronized(lifecycleCommandLock) {
            stopInProgress.set(false)
            controller.nextGeneration()
        }
        cancelRecovery()
        resetRecoveryCounters()
        terminalError = false
        controller.beginConnectionDiagnostic(token, "user_start", profileId)
        controller.startConnectionDiagnosticStage(token, "profile", "Профиль и область приложений")
        controller.publish(
            token,
            VpnConnectionState.Starting(profileId, "Проверка профиля", updaterRouting),
        )
        showForeground(ForegroundNotificationState.ValidatingProfile)
        trackLifecycleJob(serviceScope.launch {
            serviceLock.withLock {
                automationOverrideIdentity = null
                clearPausedAutomation()
                detachSessions().forEach(ActiveSession::close)
                if (token != controller.currentGeneration()) return@withLock
                try {
                    awaitConnectableNetwork(token, profileId, updaterRouting)
                    if (!pauseForNetworkAutomation(token, profileId, updaterRouting)) {
                        startWithDeadline(token, profileId, updaterRouting = updaterRouting)
                    }
                } catch (error: Throwable) {
                    if (token == controller.currentGeneration()) {
                        failLocked(token, profileId, error, startId, updaterRouting)
                    }
                }
            }
        })
    }

    /**
     * Ожидание физической сети вынесено за пределы [startWithDeadline]: бюджет
     * подключения не должен тратиться на то, что Android ещё поднимает Wi‑Fi.
     * Сначала ждём зрелую сеть, затем соглашаемся на любую пригодную, и только
     * после этого отказ становится `NET-101` — уже восстановимым.
     */
    private suspend fun awaitConnectableNetwork(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
    ) {
        attemptNetworkIdentity = null
        networkMonitor.start()
        if (!networkMonitor.current.isSettledForConnect()) {
            controller.publish(
                token,
                VpnConnectionState.Starting(profileId, "Ожидание сети Android", updaterRouting),
            )
            showForeground(ForegroundNotificationState.CheckingNetwork)
            val settled = try {
                networkMonitor.awaitUnderlying(NETWORK_SETTLE_WAIT_MILLIS) { it.isSettledForConnect() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: BootstrapFailureException) {
                null
            }
            if (settled == null) {
                networkMonitor.awaitUnderlying(NETWORK_USABLE_WAIT_MILLIS) { it.isUsableForConnect() }
            }
        }
        attemptNetworkIdentity = networkMonitor.current.identity
    }

    private suspend fun startLocked(
        token: Long,
        profileId: String,
        noCacheLookup: Boolean = false,
        updaterRouting: Boolean = false,
        runtimeDnsMode: DnsMode? = null,
    ) {
        require(profileId.isNotBlank()) { "Профиль не выбран." }
        val systemPolicy = VpnSystemPolicyDetector.detect(this)
        controller.publishVpnSystemPolicy(token, systemPolicy)
        systemPolicy.blockingMessage?.let(::error)
        container.libboxRuntime.initialize().getOrThrow()
        container.profileStore.initialize()
        var profile = container.profileStore.read(profileId)
        if (RoutingConfigEditor.usesManagedLocalRuleSets(profile.json)) {
            val installed = container.ruleSetAssetManager.ensureInstalled()
            val rebound = RoutingConfigEditor.rebindManagedRuleSetPaths(profile.json, installed)
            if (rebound != profile.json) {
                container.profileStore.update(profileId, rebound)
                profile = container.profileStore.read(profileId)
            }
        }
        val storedRouting = withContext(Dispatchers.Default) {
            RoutingConfigEditor.inspect(profile.json)
        }
        val policy = container.routingPolicyStore.getOrInitialize(
            GlobalRoutingPolicy(
                preset = storedRouting.preset,
                rules = storedRouting.rules,
            ),
        )
        val installed = container.ruleSetAssetManager.ensureInstalled()
        val effectiveRouting = withContext(Dispatchers.Default) {
            RoutingConfigEditor.apply(
                profile.json,
                policy.preset,
                policy.rules,
                installed,
            ).json
        }
        profile = profile.copy(json = VpnTestHooks.transformEffectiveRouting(effectiveRouting))
        val uiSettings = container.uiSettingsStore.settings.first()
        val configuredDnsMode = uiSettings.dnsMode
        val dnsMode = runtimeDnsMode ?: configuredDnsMode
        check(dnsMode != DnsMode.Automatic) {
            "Автоматический DNS должен быть разрешён в один runtime-кандидат до запуска core."
        }
        val vpnHiding = uiSettings.vpnHiding
        if (dnsMode == DnsMode.FromJson) {
            ConfigAnalyzer.dnsWarnings(profile.json).forEach(controller::publishDiagnosticWarning)
        }
        val appSelection = container.appSelectionStore.selection.first()
        val selectedPackages = appSelection.selectedPackagesForTunBoundary()
        val preflight = container.vpnAppScopePreflight.apply(
            selectedPackages = selectedPackages,
            mode = appSelection.mode,
            allowedSink = AllowedApplicationSink { },
            disallowedSink = DisallowedApplicationSink { },
        )
        val effectivePackages = when (preflight) {
            is VpnAppScopeResult.Ready -> {
                if (preflight.skippedPackages.isNotEmpty()) {
                    controller.publishDiagnosticWarning(
                        "Пропущены недоступные приложения: " +
                            preflight.skippedPackages.joinToString(),
                    )
                }
                preflight.effectivePackages
            }
            VpnAppScopeResult.EmptyAllowlist -> error(
                if (appSelection.mode == AppScopeMode.Include) {
                    "Выберите хотя бы одно приложение для VPN."
                } else {
                    "Выберите хотя бы одно приложение для прямого доступа вне VPN; пустой список заблокирован."
                },
            )
            is VpnAppScopeResult.MissingApplications -> error(
                "Не осталось доступных выбранных приложений. Выберите хотя бы одно.",
            )
            is VpnAppScopeResult.BuilderFailure -> error(
                "Android отклонил приложение ${preflight.packageName}: ${preflight.reason}",
            )
        }

        controller.startConnectionDiagnosticStage(token, "android_network", "Сеть и политика Android")
        controller.publish(
            token,
            VpnConnectionState.Starting(profileId, "Проверка сети Android", updaterRouting),
        )
        showForeground(ForegroundNotificationState.CheckingNetwork)
        networkMonitor.start()
        controller.startConnectionDiagnosticStage(token, "bootstrap", "Bootstrap DNS и доступность сервера")
        val networkBootstrap = networkMonitor.runOnStableNetwork(
            maxNetworkChanges = BOOTSTRAP_MAX_NETWORK_CHANGES,
            timeoutMillis = NETWORK_USABLE_WAIT_MILLIS,
            accept = { it.isUsableForConnect() },
        ) { candidate ->
            val underlying = if (VpnTestHooks.consumeCaptivePortalOverride()) {
                candidate.copy(captivePortal = true, validated = false)
            } else {
                candidate
            }
            controller.publishDiagnosticNetwork(token, underlying)
            if (underlying.captivePortal) {
                throw CaptivePortalException()
            }
            if (underlying.privateDnsMode == PrivateDnsMode.Strict &&
                (configuredDnsMode == DnsMode.Secure ||
                    (configuredDnsMode == DnsMode.Automatic && dnsMode != DnsMode.Android))
            ) {
                throw StrictPrivateDnsException(
                    "Strict Private DNS несовместим с этим режимом. " +
                        "Выберите «DNS Android» или «Из JSON».",
                )
            }
            if (underlying.privateDnsMode == PrivateDnsMode.Strict &&
                dnsMode == DnsMode.Android &&
                (!underlying.privateDnsActive || !underlying.validated)
            ) {
                throw StrictPrivateDnsException(
                    "Strict Private DNS не отвечает. " +
                        "Исправьте системную настройку или выберите «Из JSON».",
                )
            }
            container.proxyBootstrapper.prepare(
                profileId = profileId,
                rawJson = profile.json,
                underlying = checkNotNull(underlying.network),
                noCacheLookup = noCacheLookup,
            )
        }
        val underlying = networkBootstrap.network
        val preparedBootstrap = networkBootstrap.value

        controller.startConnectionDiagnosticStage(token, "runtime_config", "Runtime overlay")
        val runtimeJson =
            when (
                val runtime = RuntimeConfigBuilder.build(
                    profile.json,
                    enableTrafficStats = true,
                    options = RuntimeConfigOptions(
                        dnsMode = dnsMode,
                        proxyIpv4Only = uiSettings.proxyIpv4Only,
                        dnsOverride = uiSettings.dnsOverride,
                        bootstrapHost = preparedBootstrap.overlay,
                        vpnHiding = vpnHiding,
                        healthCheckPackageName = packageName,
                        updaterPackageName = packageName.takeIf { updaterRouting },
                        blockedPackages = appSelection.blockedPackages,
                    ),
                )
            ) {
                is RuntimeConfigResult.Ready -> runtime.json
                is RuntimeConfigResult.Invalid -> error(runtime.message)
            }
        controller.publishEffectiveOverlay(
            token,
            EffectiveOverlaySummary.create(runtimeJson, dnsMode),
        )
        controller.startConnectionDiagnosticStage(token, "check_config", "Проверка конфигурации ядром")
        controller.publish(
            token,
            VpnConnectionState.Starting(profileId, "Проверка sing-box", updaterRouting),
        )
        showForeground(ForegroundNotificationState.ValidatingCore)
        Libbox.checkConfig(runtimeJson)
        check(token == controller.currentGeneration()) { "Запуск отменён." }

        controller.startConnectionDiagnosticStage(token, "platform_adapter", "Подготовка Android VPN adapter")
        controller.publish(
            token,
            VpnConnectionState.Starting(profileId, "Создание TUN", updaterRouting),
        )
        showForeground(ForegroundNotificationState.CreatingTun)
        val outboundDescriptions = ConfigAnalyzer.outboundDescriptions(profile.json)
        val selectorGroups = ConfigAnalyzer.selectorGroups(profile.json)
        val primaryGroupTag = BootstrapConfig.selectedProxyTag(runtimeJson)
        val selectedOutboundTag = selectorGroups
            .firstOrNull { it.tag == primaryGroupTag }
            ?.default
            ?.takeIf(outboundDescriptions::containsKey)
        controller.attachDiagnosticRuntimeMap(
            token,
            DiagnosticRuntimeMap.create(
                profileId = profileId,
                profileName = profile.metadata.name,
                descriptions = outboundDescriptions,
                selectedRawTag = selectedOutboundTag,
            ),
        )
        val resources = ActiveSession(
            profileId = profileId,
            profileName = profile.metadata.name,
            generation = token,
            networkMonitor = networkMonitor,
            networkPolicyKey = underlying.policyKey(),
            outboundDescriptions = outboundDescriptions,
            selectorGroups = selectorGroups,
            primaryGroupTag = primaryGroupTag,
            updaterRouting = updaterRouting,
            controller = controller,
            scope = serviceScope,
            icmpPingProbe = container.icmpPingProbe,
        )
        if (!registerPendingSession(resources, token)) {
            resources.close()
            throw CancellationException("Запуск отменён.")
        }
        try {
            resources.attachPlatform(AndroidPlatformAdapter(
                service = this,
                selectedPackages = selectedPackages,
                scopeMode = appSelection.mode,
                expectedPackages = effectivePackages,
                scopePreflight = container.vpnAppScopePreflight,
                networkMonitor = networkMonitor,
                sessionName = VpnRuntimeHardening.sessionName(vpnHiding),
            ))
            controller.startConnectionDiagnosticStage(token, "command_server", "Запуск локального command server")
            val commandServer = Libbox.newCommandServer(
                ServerHandler(this),
                resources.platform(),
            )
            resources.attachServer(commandServer)
            commandServer.start()
            controller.startConnectionDiagnosticStage(token, "core_service", "Запуск sing-box и создание TUN")
            commandServer.startOrReloadService(
                runtimeJson,
                OverrideOptions().apply {
                    includePackage = ListStringIterator(
                        if (appSelection.mode == AppScopeMode.Include) effectivePackages else emptyList(),
                    )
                    excludePackage = ListStringIterator(
                        if (appSelection.mode == AppScopeMode.Exclude) effectivePackages else emptyList(),
                    )
                    autoRedirect = false
                },
            )
            resources.markLibboxStarted()
            check(token == controller.currentGeneration()) { "Запуск отменён." }

            // Subscribe before any startup probe. The command server retains a bounded
            // backlog, so handshake, transport and DNS failures remain available even
            // when health verification fails and the session is closed immediately.
            controller.startConnectionDiagnosticStage(token, "core_log", "Снимок bounded core-лога")
            resources.openLogClient(controller)
            controller.startConnectionDiagnosticStage(token, "group_client", "Чтение selector-групп")
            val groupClient = Libbox.newCommandClient(
                GroupClientHandler(
                    controller,
                    token,
                    resources.outboundDescriptions,
                    resources.primaryGroupTag,
                ),
                CommandClientOptions().apply {
                    addCommand(Libbox.CommandGroup)
                },
            )
            resources.attachClient(groupClient)
            groupClient.connect()
            val selectorClient = Libbox.newCommandClient(
                object : BaseClientHandler() {},
                CommandClientOptions().apply { addCommand(Libbox.CommandGroup) },
            )
            resources.attachSelectorClient(selectorClient)
            selectorClient.connect()
            check(token == controller.currentGeneration()) { "Запуск отменён." }
            reconcileSelectorSelection(groupClient, runtimeJson)
            check(token == controller.currentGeneration()) { "Запуск отменён." }
            controller.publish(
                token,
                VpnConnectionState.Starting(profileId, "Проверка DNS и HTTPS", updaterRouting),
            )
            showForeground(ForegroundNotificationState.CheckingHealth)
            val dnsServer = resources.platform().internalDnsServer
                ?: error("libbox не передал внутренний DNS TUN.")
            val health = container.vpnHealthPipeline.verify(
                mode = dnsMode,
                internalDnsServer = dnsServer,
                proxyIpFamily = BootstrapConfig.selectedProxyIpFamily(profile.json),
                onNetworkLease = { identity ->
                    controller.recordConnectionVpnNetwork(token, identity.toString())
                },
                onNetworkLost = {
                    controller.recordConnectionVpnNetwork(token, lost = true)
                },
                onStageStarted = { stage ->
                    controller.startConnectionDiagnosticStage(
                        token,
                        stage.diagnosticKey,
                        stage.diagnosticLabel,
                    )
                },
                onStageFinished = { stage, outcome, detail ->
                    controller.finishConnectionDiagnosticStage(
                        generation = token,
                        key = stage.diagnosticKey,
                        status = when (outcome) {
                            VpnHealthStageOutcome.Success -> DiagnosticStageStatus.Success
                            VpnHealthStageOutcome.Recovered -> DiagnosticStageStatus.Recovered
                            VpnHealthStageOutcome.Failed -> DiagnosticStageStatus.Failed
                        },
                        detail = detail,
                    )
                },
            )
            check(token == controller.currentGeneration()) { "Запуск отменён." }
            controller.startConnectionDiagnosticStage(token, "finalize", "Финализация сессии")
            container.proxyBootstrapper.recordSuccess(profileId, preparedBootstrap)
            check(activatePendingSession(resources, token)) { "Запуск отменён." }
            resetRecoveryCounters()
            synchronized(restartScheduleLock) { networkChangeSinceElapsed = 0L }
            resources.attachNetworkObserver(networkMonitor.observe { state ->
                onUnderlyingNetworkEvent(resources, state)
            })
            startHomeStatusObserver(resources)
            startDiagnosticsObserver(resources)
            if (!controller.diagnosticsVisible.value) resources.closeLogClient(controller)
            if (health.externalIpProbeAllowed) startConnectionIdentityProbe(resources)
        } catch (error: Throwable) {
            discardSession(resources)
            resources.close()
            throw error
        }

        if (token == controller.currentGeneration() && activeSession === resources) {
            // Connected is the public hand-off point. Publish it only after every
            // startup step that owns the session has completed; an immediate user
            // action may replace the lifecycle job as soon as this state is visible.
            controller.publish(
                token,
                VpnConnectionState.Connected(
                    profileId = profileId,
                    profileName = profile.metadata.name,
                    connectedAtEpochMillis = System.currentTimeMillis(),
                    updaterRouting = updaterRouting,
                ),
            )
            showForeground(ForegroundNotificationState.Connected)
        }
    }

    private suspend fun startWithDeadline(
        token: Long,
        profileId: String,
        noCacheLookup: Boolean = false,
        updaterRouting: Boolean = false,
    ) {
        val completed = withTimeoutOrNull(CONNECTION_START_TIMEOUT_MILLIS) {
            val configuredMode = container.uiSettingsStore.settings.first().dnsMode
            container.profileStore.initialize()
            val hasProfileDns = configuredMode == DnsMode.Automatic &&
                ConfigAnalyzer.hasProfileDns(container.profileStore.read(profileId).json)
            val strictPrivateDns = configuredMode == DnsMode.Automatic && snapshotStrictPrivateDns()
            if (strictPrivateDns) {
                controller.publishDiagnosticWarning(
                    "Strict Private DNS активен: автоматический режим использует DNS Android.",
                )
            }
            val candidates =
                AutomaticDnsFallbackPolicy.candidates(configuredMode, hasProfileDns, strictPrivateDns)
            var candidateAttemptId = 0
            AutomaticDnsFallbackPolicy.run(
                candidates = candidates,
                onFallback = { previous, candidate, failure ->
                    val failureChain = generateSequence<Throwable>(failure) { it.cause }.toList()
                    val failureType = (
                        failureChain.filterIsInstance<CodedFailure>().firstOrNull()?.failureCode
                            ?: failureChain.last().javaClass.simpleName
                        ).take(80)
                    val detail = "Автоматический DNS: ${AutomaticDnsFallbackPolicy.label(previous)} " +
                        "не отвечает ($failureType); пробуем ${AutomaticDnsFallbackPolicy.label(candidate)}."
                    controller.publishDiagnosticWarning(detail)
                    controller.startConnectionDiagnosticStage(
                        token,
                        "dns_fallback_${candidate.name.lowercase()}",
                        "DNS fallback: ${AutomaticDnsFallbackPolicy.label(candidate)}",
                    )
                    controller.publish(
                        token,
                        VpnConnectionState.Starting(profileId, detail, updaterRouting),
                    )
                },
                attempt = { candidate ->
                    candidateAttemptId += 1
                    controller.beginConnectionCandidate(token, candidateAttemptId)
                    startLocked(
                        token = token,
                        profileId = profileId,
                        noCacheLookup = noCacheLookup,
                        updaterRouting = updaterRouting,
                        runtimeDnsMode = candidate,
                    )
                    true
                },
            )
        } == true
        if (!completed) throw ConnectionStartupTimeoutException()
    }

    /**
     * Быстрый снапшот системного Private DNS до выбора DNS-кандидатов; ошибка
     * определения не должна блокировать запуск — вернём false и пойдём обычной
     * цепочкой, где строгие гейты внутри startLocked остаются страховкой.
     */
    private suspend fun snapshotStrictPrivateDns(): Boolean = try {
        networkMonitor.start()
        networkMonitor.runOnStableNetwork(
            accept = { it.isUsableForConnect() },
        ) { it.privateDnsMode == PrivateDnsMode.Strict }.value
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    private fun requestRestart(
        profileId: String,
        reason: String,
        startId: Int,
        noCacheLookup: Boolean,
        updaterRouting: Boolean? = null,
        resetRecovery: Boolean = true,
        expectedGeneration: Long? = null,
    ) {
        val token = synchronized(lifecycleCommandLock) {
            if (expectedGeneration != null &&
                (expectedGeneration != controller.currentGeneration() || stopInProgress.get())
            ) {
                return
            }
            stopInProgress.set(false)
            controller.nextGeneration()
        }
        if (resetRecovery) {
            cancelRecovery()
            resetRecoveryCounters()
        }
        trackLifecycleJob(serviceScope.launch {
            serviceLock.withLock {
                if (token != controller.currentGeneration()) return@withLock
                val targetProfile = activeSession?.profileId ?: profileId
                val targetUpdaterRouting = updaterRouting ?: activeSession?.updaterRouting ?: false
                if (targetProfile.isBlank()) {
                    controller.publishMessage("VPN выключен; перезапуск не требуется.")
                    finishForeground()
                    stopSelfResult(startId)
                    return@withLock
                }
                terminalError = false
                controller.beginConnectionDiagnostic(
                    token,
                    restartDiagnosticTrigger(reason),
                    targetProfile,
                )
                controller.startConnectionDiagnosticStage(
                    token,
                    "profile",
                    "Профиль и область приложений",
                )
                controller.publish(
                    token,
                    VpnConnectionState.Starting(targetProfile, reason, targetUpdaterRouting),
                )
                showForeground(ForegroundNotificationState.Restarting)
                clearPausedAutomation()
                detachSessions().forEach(ActiveSession::close)
                try {
                    awaitConnectableNetwork(token, targetProfile, targetUpdaterRouting)
                    if (!pauseForNetworkAutomation(token, targetProfile, targetUpdaterRouting)) {
                        startWithDeadline(
                            token,
                            targetProfile,
                            noCacheLookup,
                            updaterRouting = targetUpdaterRouting,
                        )
                    }
                } catch (error: Throwable) {
                    if (token == controller.currentGeneration()) {
                        failLocked(token, targetProfile, error, startId, targetUpdaterRouting)
                    }
                }
            }
        })
    }

    private fun requestClearDnsCache(startId: Int) {
        val expectedGeneration = controller.currentGeneration()
        // Do not register this short pre-command as the active lifecycle job:
        // requestRestart() installs the real job and would otherwise cancel its
        // own caller before the restart command can be observed reliably.
        serviceScope.launch {
            container.bootstrapCache.clear()
            val profileId = serviceLock.withLock { activeSession?.profileId.orEmpty() }
            if (profileId.isBlank()) {
                controller.publishMessage("Bootstrap cache очищен; системный DNS-кэш Android не изменён.")
                finishForeground()
                stopSelfResult(startId)
            } else {
                requestRestart(
                    profileId = profileId,
                    reason = "Сброс DNS-состояния",
                    startId = startId,
                    noCacheLookup = true,
                    expectedGeneration = expectedGeneration,
                )
            }
        }
    }

    private fun onUnderlyingNetworkEvent(session: ActiveSession, state: UnderlyingNetworkState) {
        if (activeSession !== session) return
        controller.publishDiagnosticNetwork(session.generation, state)
        if (state.identity != session.networkPolicyKey.identity) session.onNetworkChanged()
        val automationDecision = networkAutomationDecision(state, session.updaterRouting)
        if (automationDecision is NetworkAutomationDecision.PauseVpn) {
            scheduleAutomationPause(session, state)
            return
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(restartScheduleLock) {
            val waitedMillis = if (networkChangeSinceElapsed == 0L) {
                0L
            } else {
                now - networkChangeSinceElapsed
            }
            val plan = NetworkRestartPolicy.plan(
                sessionBaseline = session.networkPolicyKey,
                observed = state.policyKey(),
                observedSettled = state.isSettledForConnect(),
                waitedMillis = waitedMillis,
            )
            if (plan.decision == NetworkRestartDecision.KeepSession) {
                networkChangeSinceElapsed = 0L
                networkRestartJob?.cancel()
                networkRestartJob = null
                return
            }
            if (networkChangeSinceElapsed == 0L) networkChangeSinceElapsed = now
            networkRestartJob?.cancel()
            networkRestartJob = serviceScope.launch {
                delay(plan.debounceMillis)
                val current = activeSession
                if (current !== session || current.generation != controller.currentGeneration()) return@launch
                if (
                    NetworkRestartPolicy.decide(
                        current.networkPolicyKey,
                        current.networkMonitor.current.policyKey(),
                    ) == NetworkRestartDecision.KeepSession
                ) {
                    return@launch
                }
                requestRestart(
                    profileId = current.profileId,
                    reason = "Смена сети Android",
                    startId = 0,
                    noCacheLookup = false,
                    expectedGeneration = current.generation,
                )
            }
        }
    }

    private suspend fun pauseForNetworkAutomation(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
    ): Boolean {
        automationSettings = container.uiSettingsStore.settings.first().networkAutomation
        val decision = networkAutomationDecision(networkMonitor.current, updaterRouting)
        if (decision !is NetworkAutomationDecision.PauseVpn) return false
        enterAutomationPause(
            generation = token,
            profileId = profileId,
            updaterRouting = updaterRouting,
            reason = decision.reason,
        )
        return true
    }

    private fun networkAutomationDecision(
        state: UnderlyingNetworkState,
        updaterRouting: Boolean,
    ): NetworkAutomationDecision {
        if (updaterRouting) return NetworkAutomationDecision.RunVpn
        val overrideIdentity = automationOverrideIdentity
        if (overrideIdentity != null) {
            if (state.identity == overrideIdentity) return NetworkAutomationDecision.RunVpn
            automationOverrideIdentity = null
        }
        val wifiSsid = state.wifiSsid ?: if (
            state.transport == "wifi" &&
            automationSettings.pauseOnTrustedWifi &&
            automationSettings.trustedWifiSsids.isNotEmpty()
        ) {
            runCatching(currentWifiSsidReader::read).getOrNull()
        } else {
            null
        }
        return NetworkAutomationPolicy.decide(
            settings = automationSettings,
            networkAvailable = state.network != null,
            transport = state.transport,
            wifiSsid = wifiSsid,
        )
    }

    private fun scheduleAutomationPause(
        session: ActiveSession,
        state: UnderlyingNetworkState,
    ) {
        val now = SystemClock.elapsedRealtime()
        synchronized(restartScheduleLock) {
            val waitedMillis = if (networkChangeSinceElapsed == 0L) {
                0L
            } else {
                now - networkChangeSinceElapsed
            }
            if (networkChangeSinceElapsed == 0L) networkChangeSinceElapsed = now
            val debounceMillis = if (
                state.isSettledForConnect() ||
                waitedMillis >= NetworkRestartPolicy.MAX_SETTLING_WAIT_MILLIS
            ) {
                NetworkRestartPolicy.SETTLED_DEBOUNCE_MILLIS
            } else {
                NetworkRestartPolicy.SETTLING_DEBOUNCE_MILLIS
            }
            networkRestartJob?.cancel()
            lateinit var scheduled: Job
            scheduled = serviceScope.launch {
                delay(debounceMillis)
                val current = activeSession
                if (current !== session || current.generation != controller.currentGeneration()) {
                    return@launch
                }
                val latest = networkAutomationDecision(current.networkMonitor.current, current.updaterRouting)
                if (latest !is NetworkAutomationDecision.PauseVpn) return@launch
                synchronized(restartScheduleLock) {
                    if (networkRestartJob === scheduled) networkRestartJob = null
                }
                requestAutomationPause(current)
            }
            networkRestartJob = scheduled
        }
    }

    private fun requestAutomationPause(session: ActiveSession) {
        trackLifecycleJob(serviceScope.launch {
            serviceLock.withLock {
                if (activeSession !== session || session.generation != controller.currentGeneration()) {
                    return@withLock
                }
                val latest = networkAutomationDecision(networkMonitor.current, session.updaterRouting)
                if (latest !is NetworkAutomationDecision.PauseVpn) return@withLock
                cancelRecovery()
                resetRecoveryCounters()
                val token = controller.nextGeneration()
                val sessions = detachSessions()
                sessions.forEach(ActiveSession::closeTun)
                sessions.forEach(ActiveSession::close)
                enterAutomationPause(
                    generation = token,
                    profileId = session.profileId,
                    updaterRouting = session.updaterRouting,
                    reason = latest.reason,
                )
            }
        })
    }

    private fun enterAutomationPause(
        generation: Long,
        profileId: String,
        updaterRouting: Boolean,
        reason: NetworkPauseReason,
    ) {
        clearPausedAutomation()
        val paused = PausedAutomationSession(
            profileId = profileId,
            updaterRouting = updaterRouting,
            generation = generation,
            reason = reason,
        )
        synchronized(pausedAutomationLock) { pausedAutomation = paused }
        terminalError = false
        controller.publish(
            generation,
            VpnConnectionState.Paused(profileId, reason.userMessage()),
        )
        showForeground(ForegroundNotificationState.Paused)
        var initialized = false
        val observer = networkMonitor.observe { state ->
            if (initialized) onPausedNetworkEvent(state)
        }
        val accepted = synchronized(pausedAutomationLock) {
            if (pausedAutomation == paused) {
                pausedNetworkObserver = observer
                true
            } else {
                false
            }
        }
        initialized = true
        if (!accepted) {
            observer.close()
        } else {
            onPausedNetworkEvent(networkMonitor.current)
        }
    }

    private fun onPausedNetworkEvent(state: UnderlyingNetworkState) {
        val paused = pausedAutomation ?: return
        when (val decision = networkAutomationDecision(state, paused.updaterRouting)) {
            is NetworkAutomationDecision.PauseVpn -> {
                cancelScheduledNetworkRestart()
                if (decision.reason != paused.reason) {
                    val updated = paused.copy(reason = decision.reason)
                    synchronized(pausedAutomationLock) {
                        if (pausedAutomation == paused) pausedAutomation = updated
                    }
                    controller.publish(
                        paused.generation,
                        VpnConnectionState.Paused(paused.profileId, decision.reason.userMessage()),
                    )
                    showForeground(ForegroundNotificationState.Paused)
                }
            }
            NetworkAutomationDecision.WaitForNetwork -> cancelScheduledNetworkRestart()
            NetworkAutomationDecision.RunVpn -> schedulePausedResume(paused, state)
        }
    }

    private fun schedulePausedResume(
        paused: PausedAutomationSession,
        state: UnderlyingNetworkState,
    ) {
        val now = SystemClock.elapsedRealtime()
        synchronized(restartScheduleLock) {
            val waitedMillis = if (networkChangeSinceElapsed == 0L) {
                0L
            } else {
                now - networkChangeSinceElapsed
            }
            if (networkChangeSinceElapsed == 0L) networkChangeSinceElapsed = now
            val debounceMillis = if (
                state.isSettledForConnect() ||
                waitedMillis >= NetworkRestartPolicy.MAX_SETTLING_WAIT_MILLIS
            ) {
                NetworkRestartPolicy.SETTLED_DEBOUNCE_MILLIS
            } else {
                NetworkRestartPolicy.SETTLING_DEBOUNCE_MILLIS
            }
            networkRestartJob?.cancel()
            lateinit var scheduled: Job
            scheduled = serviceScope.launch {
                delay(debounceMillis)
                if (pausedAutomation != paused) return@launch
                if (networkAutomationDecision(networkMonitor.current, paused.updaterRouting) !=
                    NetworkAutomationDecision.RunVpn
                ) {
                    return@launch
                }
                synchronized(restartScheduleLock) {
                    if (networkRestartJob === scheduled) networkRestartJob = null
                }
                requestResumePaused(startId = 0, manualOverride = false)
            }
            networkRestartJob = scheduled
        }
    }

    private fun reevaluateNetworkAutomation() {
        activeSession?.let { session ->
            val decision = networkAutomationDecision(networkMonitor.current, session.updaterRouting)
            if (decision is NetworkAutomationDecision.PauseVpn) {
                requestAutomationPause(session)
            }
            return
        }
        val paused = pausedAutomation ?: return
        when (val decision = networkAutomationDecision(networkMonitor.current, paused.updaterRouting)) {
            is NetworkAutomationDecision.PauseVpn -> {
                if (decision.reason != paused.reason) {
                    val updated = paused.copy(reason = decision.reason)
                    synchronized(pausedAutomationLock) {
                        if (pausedAutomation == paused) pausedAutomation = updated
                    }
                    controller.publish(
                        paused.generation,
                        VpnConnectionState.Paused(paused.profileId, decision.reason.userMessage()),
                    )
                }
            }
            NetworkAutomationDecision.RunVpn -> requestResumePaused(0, manualOverride = false)
            NetworkAutomationDecision.WaitForNetwork -> Unit
        }
    }

    private fun requestResumePaused(startId: Int, manualOverride: Boolean) {
        val paused = synchronized(pausedAutomationLock) { pausedAutomation } ?: return
        if (!manualOverride &&
            networkAutomationDecision(networkMonitor.current, paused.updaterRouting) !=
            NetworkAutomationDecision.RunVpn
        ) {
            return
        }
        if (clearPausedAutomation(paused) == null) return
        if (manualOverride) automationOverrideIdentity = networkMonitor.current.identity
        requestRestart(
            profileId = paused.profileId,
            reason = if (manualOverride) {
                "Подключение до смены сети"
            } else {
                "Автоматическое подключение по сети"
            },
            startId = startId,
            noCacheLookup = false,
            updaterRouting = paused.updaterRouting,
        )
    }

    private fun clearPausedAutomation(
        expected: PausedAutomationSession? = null,
    ): PausedAutomationSession? {
        val (paused, observer) = synchronized(pausedAutomationLock) {
            val current = pausedAutomation
            if (expected != null && current != expected) return null
            pausedAutomation = null
            current to pausedNetworkObserver.also { pausedNetworkObserver = null }
        }
        runCatching { observer?.close() }
        return paused
    }

    private fun restartDiagnosticTrigger(reason: String): String = when (reason) {
        "Смена сети Android" -> "network_change"
        "Автоматическое переподключение" -> "auto_recovery"
        "Сброс DNS-состояния" -> "dns_cache_clear"
        "Изменение маршрутизации" -> "routing_change"
        "Подписка обновлена пользователем" -> "subscription_refresh"
        "Смена режима DNS" -> "dns_mode_change"
        "Смена IP-стратегии DNS" -> "dns_strategy_change"
        "Смена защиты от localhost-чекеров" -> "endpoint_policy_change"
        "Смена имени VPN-сессии" -> "session_name_change"
        "Смена MTU для скрытия VPN" -> "mtu_change"
        else -> "restart"
    }

    private fun requestStop(
        startId: Int,
        errorMessage: String?,
        systemPolicy: VpnSystemPolicy? = null,
        trigger: String = if (errorMessage == null) "user_stop" else "policy_stop",
    ) {
        val token = synchronized(lifecycleCommandLock) {
            if (!stopInProgress.compareAndSet(false, true)) return
            controller.nextGeneration()
        }
        cancelScheduledNetworkRestart()
        cancelRecovery()
        resetRecoveryCounters()
        automationOverrideIdentity = null
        controller.cancelCurrentConnectionDiagnostic(
            if (trigger == "user_stop") "user_cancelled" else trigger,
        )
        controller.beginStopDiagnostic(token, trigger)
        systemPolicy?.let { controller.publishVpnSystemPolicy(token, it) }
        terminalError = errorMessage != null
        val paused = clearPausedAutomation()
        val sessions = detachSessions()
        sessions.forEach { it.enableStopDiagnostics(token) }
        val profileId = sessions.firstOrNull()?.profileId ?: paused?.profileId

        controller.startStopDiagnosticStage(token, "cancel_run", "Отмена текущего запуска")
        cancelLifecycleJob()
        controller.finishStopDiagnosticStage(token, "cancel_run")

        controller.publish(token, VpnConnectionState.Stopping(profileId))
        showForeground(ForegroundNotificationState.Stopping)
        sessions.forEach(ActiveSession::closeTun)
        serviceScope.launch {
            sessions.forEach(ActiveSession::close)
            if (sessions.isEmpty()) {
                listOf(
                    "close_tun" to "Закрытие Android TUN",
                    "close_clients" to "Отключение клиентов libbox",
                    "close_libbox_service" to "Остановка сервиса libbox",
                ).forEach { (key, label) ->
                    controller.startStopDiagnosticStage(token, key, label)
                    controller.finishStopDiagnosticStage(token, key)
                }
            }
            controller.completeStopDiagnostic(token)
            if (token == controller.currentGeneration()) {
                closeNetworkMonitor()
                finishForeground()
                if (startId > 0) stopSelfResult(startId) else stopSelf()
                if (errorMessage == null) {
                    controller.publish(token, VpnConnectionState.Stopped)
                } else {
                    controller.publish(token, VpnConnectionState.Error(errorMessage))
                }
            }
        }
    }

    private fun requestSelect(
        profileId: String,
        groupTag: String,
        outboundTag: String,
        startId: Int,
    ) {
        val expectedGeneration = controller.currentGeneration()
        serviceScope.launch {
            serviceLock.withLock {
                if (expectedGeneration != controller.currentGeneration() || stopInProgress.get()) {
                    return@withLock
                }
                val session = activeSession
                if (session == null || session.profileId != profileId) {
                    // Восстановление здесь бессмысленно: сессии нет, а не сети.
                    attemptNetworkIdentity = null
                    failLocked(
                        token = controller.currentGeneration(),
                        profileId = profileId,
                        error = IllegalStateException("Активный VPN-профиль не найден."),
                        startId = startId,
                        updaterRouting = false,
                    )
                    return@withLock
                }
                try {
                    selectLocked(session, groupTag, outboundTag)
                    showForeground(ForegroundNotificationState.Connected)
                    controller.clearConnectionIdentity(session.generation)
                    startConnectionIdentityProbe(session)
                } catch (runtimeSwitchError: RuntimeSwitchException) {
                    val restartToken = controller.nextGeneration()
                    controller.beginConnectionDiagnostic(
                        restartToken,
                        "server_switch_restart",
                        profileId,
                    )
                    controller.startConnectionDiagnosticStage(
                        restartToken,
                        "profile",
                        "Профиль и область приложений",
                    )
                    controller.publish(
                        restartToken,
                        VpnConnectionState.Starting(
                            profileId,
                            "Перезапуск после смены сервера",
                            session.updaterRouting,
                        ),
                    )
                    showForeground(ForegroundNotificationState.Restarting)
                    detachSessions().forEach(ActiveSession::close)
                    try {
                        awaitConnectableNetwork(restartToken, profileId, session.updaterRouting)
                        startWithDeadline(
                            restartToken,
                            profileId,
                            updaterRouting = session.updaterRouting,
                        )
                    } catch (restartError: Throwable) {
                        if (restartToken == controller.currentGeneration()) {
                            restartError.addSuppressed(runtimeSwitchError)
                            failLocked(
                                token = restartToken,
                                profileId = profileId,
                                error = restartError,
                                startId = startId,
                                updaterRouting = session.updaterRouting,
                            )
                        }
                    }
                } catch (validationError: Throwable) {
                    controller.publishMessage(safeError(validationError).message)
                    showForeground(ForegroundNotificationState.Connected)
                }
            }
        }
    }

    private fun requestGroupPing(profileId: String, groupTag: String, startId: Int) {
        serviceScope.launch {
            val session = serviceLock.withLock { activeSession }
            if (session == null || session.profileId != profileId) {
                controller.publishMessage("Активный VPN-профиль не найден.")
                if (session == null) {
                    finishForeground()
                    stopSelfResult(startId)
                }
                return@launch
            }
            val group = controller.selectorGroups.value.firstOrNull { it.tag == groupTag }
            if (group == null || group.items.isEmpty()) {
                controller.publishMessage(session.generation, "Группа серверов не найдена в sing-box.")
                return@launch
            }
            session.toggleLatencyProbe(group)
        }
    }

    private fun startHomeStatusObserver(session: ActiveSession) {
        session.attachStatusObserver(serviceScope.launch {
            controller.homeVisible.collect { visible ->
                if (activeSession !== session) return@collect
                if (visible) {
                    session.openStatusClient(controller)
                } else {
                    session.closeStatusClient(controller)
                }
            }
        })
    }

    private fun startDiagnosticsObserver(session: ActiveSession) {
        session.attachDiagnosticsObserver(serviceScope.launch {
            controller.diagnosticsVisible.collect { visible ->
                if (activeSession !== session) return@collect
                if (visible) {
                    session.openLogClient(controller)
                } else {
                    session.closeLogClient(controller)
                }
            }
        })
    }

    private fun startConnectionIdentityProbe(session: ActiveSession) {
        session.replaceIdentityJob(serviceScope.launch {
            val externalIp = runCatching { container.vpnExternalIpProbe.fetch() }.getOrNull()
            if (externalIp != null && activeSession === session) {
                controller.publishExternalIp(session.generation, externalIp)
            }
        })
    }

    /**
     * Навязывает ядру сервер из сохранённого профиля сразу после старта.
     *
     * Без этого `cache.db` переопределял `selector.default`, поэтому сервер,
     * выбранный при остановленном ядре, молча игнорировался, а health-check и
     * весь трафик уходили через застрявший в кэше outbound. Шаг выполняется до
     * проверки DNS/HTTPS, иначе проверялся бы не тот сервер.
     *
     * Отказ команды не прерывает подключение: конфигурация уже принята ядром, а
     * фактический сервер виден в диагностике и на главном экране.
     */
    private suspend fun reconcileSelectorSelection(client: CommandClient, runtimeJson: String) {
        val selections = withContext(Dispatchers.Default) {
            SelectorCacheReconciliation.selections(ConfigAnalyzer.selectorGroups(runtimeJson))
        }
        selections.forEach { selection ->
            try {
                withContext(Dispatchers.IO) {
                    client.selectOutbound(selection.groupTag, selection.outboundTag)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                controller.publishDiagnosticWarning(
                    "Не удалось применить сервер " +
                        "${SecretRedactor.redactInline(selection.outboundTag)} " +
                        "группы ${SecretRedactor.redactInline(selection.groupTag)}: " +
                        SecretRedactor.redactInline(error.message.orEmpty()),
                )
            }
        }
    }

    private suspend fun selectLocked(
        session: ActiveSession,
        groupTag: String,
        outboundTag: String,
    ) {
        require(groupTag.isNotBlank() && outboundTag.isNotBlank()) { "Сервер не выбран." }
        val stored = container.profileStore.read(session.profileId)
        val candidate = ConfigAnalyzer.selectServer(stored.json, groupTag, outboundTag)
        withContext(Dispatchers.Default) { Libbox.checkConfig(candidate) }
        container.profileStore.update(session.profileId, candidate)
        val client = session.selectorClient()
            ?: throw RuntimeSwitchException(IllegalStateException("Клиент управления selector уже закрыт."))
        try {
            withContext(Dispatchers.IO) {
                client.selectOutbound(groupTag, outboundTag)
            }
        } catch (error: Throwable) {
            throw RuntimeSwitchException(error)
        }
        controller.publishSelection(session.generation, groupTag, outboundTag)
    }

    private suspend fun failLocked(
        token: Long,
        profileId: String,
        error: Throwable,
        startId: Int,
        updaterRouting: Boolean,
    ) {
        cancelScheduledNetworkRestart()
        val startupCoreLogs = controller.diagnostics.value.connectionAttempt
            ?.takeIf { it.generation == token }
            ?.startupCoreLogs
            .orEmpty()
        val failure = WireGuardDataPlaneClassifier.refine(
            failure = VpnAccessFailureClassifier.refine(safeError(error), startupCoreLogs),
            startupCoreLogs = startupCoreLogs,
        )
        detachSessions().forEach(ActiveSession::close)
        val decision = recoveryDecision(profileId, failure)
        if (decision == VpnRecoveryDecision.Terminal) {
            publishTerminalFailure(token, failure)
            if (startId > 0) stopSelfResult(startId) else stopSelf()
            return
        }
        scheduleRecovery(token, profileId, updaterRouting, failure, decision)
    }

    private fun recoveryDecision(
        profileId: String,
        failure: VpnConnectionState.Error,
    ): VpnRecoveryDecision {
        if (stopInProgress.get() || profileId.isBlank()) return VpnRecoveryDecision.Terminal
        val startedOn = attemptNetworkIdentity
        return VpnRecoveryPolicy.decide(
            failureCode = failure.code,
            attempt = recoveryAttempt,
            totalAttempts = recoveryTotalAttempts,
            networkChangedDuringAttempt = startedOn != null &&
                startedOn != networkMonitor.current.identity,
        )
    }

    private fun resetRecoveryCounters() {
        recoveryAttempt = 0
        recoveryTotalAttempts = 0
    }

    /**
     * Callback сети закрывается до публикации: инвариант «терминальное состояние
     * означает полностью освобождённые ресурсы» проверяется gate-тестами сразу
     * после появления состояния, а `onDestroy` приходит позже.
     */
    private fun publishTerminalFailure(token: Long, failure: VpnConnectionState.Error) {
        resetRecoveryCounters()
        terminalError = true
        closeNetworkMonitor()
        finishForeground()
        controller.publish(token, failure)
    }

    /**
     * Держит сервис живым между попытками. Наблюдатель сети переживает отказ,
     * поэтому появление рабочей сети само поднимает VPN — раньше сервис умирал
     * вместе с монитором и требовал ручного «Подключить».
     */
    private fun scheduleRecovery(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        failure: VpnConnectionState.Error,
        decision: VpnRecoveryDecision,
    ) {
        val attempt = recoveryAttempt + 1
        recoveryAttempt = attempt
        recoveryTotalAttempts += 1
        val failedOnIdentity = attemptNetworkIdentity
        controller.publishRecoverableFailure(token, failure)
        cancelRecovery()
        val job = serviceScope.launch {
            if (decision is VpnRecoveryDecision.RetryAfter) {
                publishRecovering(
                    token = token,
                    profileId = profileId,
                    updaterRouting = updaterRouting,
                    failure = failure,
                    message = "Повтор подключения через ${decision.delayMillis / 1_000} с",
                    attempt = attempt,
                )
                showForeground(ForegroundNotificationState.Retrying)
                delay(decision.delayMillis)
            }
            if (!networkMonitor.current.isSettledForConnect()) {
                publishRecovering(
                    token = token,
                    profileId = profileId,
                    updaterRouting = updaterRouting,
                    failure = failure,
                    message = "Ожидание сети Android",
                    attempt = attempt,
                )
                showForeground(ForegroundNotificationState.AwaitingNetwork)
            }
            val ready = awaitRecoveryNetwork()
            if (failedOnIdentity != null && ready.identity != failedOnIdentity) recoveryAttempt = 0
            requestRestart(
                profileId = profileId,
                reason = "Автоматическое переподключение",
                startId = 0,
                noCacheLookup = false,
                updaterRouting = updaterRouting,
                resetRecovery = false,
                expectedGeneration = token,
            )
        }
        synchronized(recoveryLock) { recoveryJob = job }
        job.invokeOnCompletion {
            synchronized(recoveryLock) { if (recoveryJob === job) recoveryJob = null }
        }
    }

    /**
     * Ждёт сеть столько, сколько нужно: отключённый на ночь Wi-Fi не должен
     * превращаться в ошибку, которую пользователь потом чинит руками. Это
     * ожидание события `ConnectivityManager`, а не серия попыток — ни таймера,
     * ни опроса, ни wakelock оно не создаёт, поэтому границы применяются к числу
     * попыток подключения, а не к длительности простоя.
     *
     * Внутри каждой итерации сначала ждём зрелую сеть, затем соглашаемся на любую
     * пригодную: Wi‑Fi без подтверждённого интернета Android может не пометить
     * validated никогда.
     */
    private suspend fun awaitRecoveryNetwork(): UnderlyingNetworkState {
        while (true) {
            awaitRecoveryNetworkStage(RECOVERY_SETTLE_WAIT_MILLIS) { it.isSettledForConnect() }
                ?.let { return it }
            awaitRecoveryNetworkStage(RECOVERY_USABLE_WAIT_MILLIS) { it.isUsableForConnect() }
                ?.let { return it }
        }
    }

    private suspend fun awaitRecoveryNetworkStage(
        timeoutMillis: Long,
        accept: (UnderlyingNetworkState) -> Boolean,
    ): UnderlyingNetworkState? = try {
        networkMonitor.awaitUnderlying(timeoutMillis, accept)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: BootstrapFailureException) {
        null
    }

    private fun publishRecovering(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        failure: VpnConnectionState.Error,
        message: String,
        attempt: Int,
    ) {
        controller.publish(
            token,
            VpnConnectionState.Reconnecting(
                profileId = profileId,
                message = message,
                code = failure.code,
                attempt = attempt,
                maxAttempts = VpnRecoveryPolicy.MAX_ATTEMPTS,
                updaterRouting = updaterRouting,
            ),
        )
    }

    private fun cancelRecovery() {
        val job = synchronized(recoveryLock) { recoveryJob.also { recoveryJob = null } }
        job?.cancel()
    }

    internal fun requestStopFromCore() {
        requestStop(0, "sing-box остановил VPN-сервис.")
    }

    private fun showForeground(state: ForegroundNotificationState) {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle("Zapret KVN")
            .setContentText(state.text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Открыть", openIntent)
        if (state == ForegroundNotificationState.Connected) {
            (controller.state.value as? VpnConnectionState.Connected)?.let { connected ->
                val restartIntent = PendingIntent.getService(
                    this,
                    3,
                    restartIntent(
                        this,
                        connected.profileId,
                        "Перезапуск из уведомления",
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, "Перезапустить", restartIntent)
            }
        } else if (state == ForegroundNotificationState.Paused) {
            val resumeIntent = PendingIntent.getService(
                this,
                4,
                resumeIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Подключить сейчас", resumeIntent)
        }
        val notification = builder
            .addAction(0, "Остановить", stopIntent)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        foregroundActive.set(true)
    }

    private fun cancelScheduledNetworkRestart() {
        synchronized(restartScheduleLock) {
            networkRestartJob?.cancel()
            networkRestartJob = null
            networkChangeSinceElapsed = 0L
        }
    }

    private fun trackLifecycleJob(job: Job) {
        val previous = synchronized(lifecycleJobLock) {
            val old = lifecycleJob
            lifecycleJob = job
            old
        }
        previous?.cancel()
        job.invokeOnCompletion {
            synchronized(lifecycleJobLock) {
                if (lifecycleJob === job) lifecycleJob = null
            }
        }
    }

    private fun cancelLifecycleJob() {
        synchronized(lifecycleJobLock) {
            lifecycleJob?.cancel(CancellationException("VPN lifecycle отменён."))
            lifecycleJob = null
        }
    }

    private fun registerPendingSession(session: ActiveSession, generation: Long): Boolean =
        synchronized(sessionStateLock) {
            if (generation != controller.currentGeneration()) {
                false
            } else {
                check(pendingSession == null) { "Параллельный запуск VPN запрещён." }
                pendingSession = session
                true
            }
        }

    private fun activatePendingSession(session: ActiveSession, generation: Long): Boolean =
        synchronized(sessionStateLock) {
            if (
                generation != controller.currentGeneration() ||
                pendingSession !== session
            ) {
                false
            } else {
                pendingSession = null
                activeSession = session
                true
            }
        }

    private fun discardSession(session: ActiveSession) {
        synchronized(sessionStateLock) {
            if (pendingSession === session) pendingSession = null
            if (activeSession === session) activeSession = null
        }
    }

    private fun detachSessions(): List<ActiveSession> = synchronized(sessionStateLock) {
        val sessions = listOfNotNull(activeSession, pendingSession).distinct()
        activeSession = null
        pendingSession = null
        sessions
    }

    private fun finishForeground() {
        if (!foregroundActive.compareAndSet(true, false)) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Состояние VPN и действие остановки"
                setShowBadge(false)
            },
        )
    }

    private fun safeError(error: Throwable): VpnConnectionState.Error {
        val causes = generateSequence(error) { it.cause }.toList()
        val coded = causes.filterIsInstance<CodedFailure>().firstOrNull()
        val raw = coded?.userMessage ?: causes
            .mapNotNull { it.message }
            .firstOrNull(String::isNotBlank)
            ?: "Не удалось запустить VPN."
        val message = raw
            .let(SecretRedactor::redactInline)
            .replace(NEW_LINES, " ")
            .trim()
            .take(360)
        val technicalDetail = coded?.technicalDetail
            ?.let(SecretRedactor::redactInline)
            ?.replace(NEW_LINES, " ")
            ?.trim()
            ?.take(240)
        return VpnConnectionState.Error(
            message = message,
            code = coded?.failureCode.orEmpty(),
            technicalDetail = technicalDetail,
        )
    }

    private data class PausedAutomationSession(
        val profileId: String,
        val updaterRouting: Boolean,
        val generation: Long,
        val reason: NetworkPauseReason,
    )

    private class ActiveSession(
        val profileId: String,
        val profileName: String,
        val generation: Long,
        val networkMonitor: DefaultNetworkMonitor,
        val networkPolicyKey: UnderlyingPolicyKey,
        val outboundDescriptions: Map<String, OutboundDescription>,
        selectorGroups: List<SelectorGroup>,
        val primaryGroupTag: String?,
        val updaterRouting: Boolean,
        private val controller: VpnController,
        scope: CoroutineScope,
        icmpPingProbe: IcmpPingProbe,
    ) : AutoCloseable {
        private val closing = AtomicBoolean(false)
        private val tunCloseStarted = AtomicBoolean(false)
        private val cleanupStarted = AtomicBoolean(false)
        private val cleanupComplete = CountDownLatch(1)
        private val libboxStarted = AtomicBoolean(false)
        private val resourceLock = Any()
        @Volatile private var platform: AndroidPlatformAdapter? = null
        @Volatile private var server: CommandServer? = null
        @Volatile private var client: CommandClient? = null
        @Volatile private var selectorClient: CommandClient? = null
        private var networkObserver: AutoCloseable? = null
        private var statusObserver: Job? = null
        private var diagnosticsObserver: Job? = null
        private var identityJob: Job? = null
        private var statusClient: CommandClient? = null
        private var statusClientCounted = false
        private var logClient: CommandClient? = null
        private var logClientCounted = false
        @Volatile private var stopDiagnosticGeneration = Long.MIN_VALUE
        private val pingTargetResolver = ServerPingTargetResolver(
            outboundDescriptions,
            selectorGroups,
            primaryGroupTag,
        )
        private val latencyProbeCoordinator = LatencyProbeCoordinator(
            generation = generation,
            scope = scope,
            networkMonitor = networkMonitor,
            targetResolver = pingTargetResolver,
            icmpProbe = icmpPingProbe,
            controller = controller,
        )

        init {
            VpnRuntimeMetrics.sessionOpened()
        }

        fun toggleLatencyProbe(group: RuntimeSelectorGroup) = latencyProbeCoordinator.toggle(group)

        fun onNetworkChanged() = latencyProbeCoordinator.onNetworkChanged()

        fun attachPlatform(candidate: AndroidPlatformAdapter) {
            val accepted = synchronized(resourceLock) {
                if (closing.get()) false else {
                    check(platform == null)
                    platform = candidate
                    true
                }
            }
            if (!accepted) {
                candidate.close()
                throw CancellationException("Запуск отменён до создания TUN.")
            }
        }

        fun platform(): AndroidPlatformAdapter =
            platform ?: throw CancellationException("Android VPN adapter уже закрыт.")

        fun attachServer(candidate: CommandServer) {
            val accepted = synchronized(resourceLock) {
                if (closing.get()) false else {
                    check(server == null)
                    server = candidate
                    true
                }
            }
            if (!accepted) {
                runCatching { candidate.close() }
                throw CancellationException("Запуск command server отменён.")
            }
        }

        fun attachClient(candidate: CommandClient) {
            val accepted = synchronized(resourceLock) {
                if (closing.get()) false else {
                    check(client == null)
                    client = candidate
                    true
                }
            }
            if (!accepted) {
                runCatching { candidate.disconnect() }
                throw CancellationException("Подключение command client отменено.")
            }
        }

        fun attachSelectorClient(candidate: CommandClient) {
            val accepted = synchronized(resourceLock) {
                if (closing.get()) false else {
                    check(selectorClient == null)
                    selectorClient = candidate
                    true
                }
            }
            if (!accepted) {
                runCatching { candidate.disconnect() }
                throw CancellationException("Клиент управления selector отменён.")
            }
        }

        fun selectorClient(): CommandClient? = selectorClient

        fun attachNetworkObserver(candidate: AutoCloseable) {
            val accepted = synchronized(resourceLock) {
                if (closing.get()) false else {
                    check(networkObserver == null)
                    networkObserver = candidate
                    true
                }
            }
            if (!accepted) runCatching { candidate.close() }
        }

        fun attachStatusObserver(candidate: Job) = attachJob(candidate) {
            check(statusObserver == null)
            statusObserver = candidate
        }

        fun attachDiagnosticsObserver(candidate: Job) = attachJob(candidate) {
            check(diagnosticsObserver == null)
            diagnosticsObserver = candidate
        }

        fun replaceIdentityJob(candidate: Job) {
            val previous = synchronized(resourceLock) {
                if (closing.get()) {
                    null
                } else {
                    identityJob.also { identityJob = candidate }
                }
            }
            previous?.cancel()
            if (closing.get()) candidate.cancel()
        }

        private inline fun attachJob(candidate: Job, crossinline attach: () -> Unit) {
            val accepted = synchronized(resourceLock) {
                if (closing.get()) false else {
                    attach()
                    true
                }
            }
            if (!accepted) candidate.cancel()
        }

        fun markLibboxStarted() {
            if (!closing.get() && libboxStarted.compareAndSet(false, true)) {
                VpnRuntimeMetrics.libboxOpened()
            }
        }

        fun enableStopDiagnostics(generation: Long) {
            stopDiagnosticGeneration = generation
        }

        fun closeTun() {
            closing.set(true)
            if (!tunCloseStarted.compareAndSet(false, true)) return
            timedStopStage("close_tun", "Закрытие Android TUN") {
                val current = synchronized(resourceLock) {
                    platform.also { platform = null }
                }
                current?.close()
            }
        }

        @Synchronized
        fun openStatusClient(controller: VpnController) {
            if (closing.get() || statusClient != null) return
            val candidate = Libbox.newCommandClient(
                StatusClientHandler(controller, generation),
                CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    statusInterval = STATUS_INTERVAL_NANOS
                },
            )
            try {
                candidate.connect()
                if (closing.get()) {
                    runCatching { candidate.disconnect() }
                    return
                }
                statusClient = candidate
                statusClientCounted = true
                VpnRuntimeMetrics.statusClientOpened()
                controller.publishStatusStream(generation, true)
            } catch (_: Throwable) {
                runCatching { candidate.disconnect() }
                controller.publishStatusStream(generation, false)
            }
        }

        @Synchronized
        fun closeStatusClient(controller: VpnController) {
            val current = statusClient
            statusClient = null
            runCatching { current?.disconnect() }
            if (statusClientCounted) {
                statusClientCounted = false
                VpnRuntimeMetrics.statusClientClosed()
            }
            controller.publishStatusStream(generation, false)
        }

        @Synchronized
        fun openLogClient(controller: VpnController) {
            if (closing.get() || logClient != null) return
            val candidate = Libbox.newCommandClient(
                DiagnosticLogClientHandler(controller, generation),
                CommandClientOptions().apply { addCommand(Libbox.CommandLog) },
            )
            try {
                candidate.connect()
                if (closing.get()) {
                    runCatching { candidate.disconnect() }
                    return
                }
                logClient = candidate
                logClientCounted = true
                VpnRuntimeMetrics.logClientOpened()
                controller.publishDiagnosticLogStream(generation, true)
            } catch (_: Throwable) {
                runCatching { candidate.disconnect() }
                controller.publishDiagnosticLogStream(generation, false)
            }
        }

        @Synchronized
        fun closeLogClient(controller: VpnController) {
            val current = logClient
            logClient = null
            runCatching { current?.disconnect() }
            if (logClientCounted) {
                logClientCounted = false
                VpnRuntimeMetrics.logClientClosed()
            }
            controller.publishDiagnosticLogStream(generation, false)
        }

        override fun close() {
            closing.set(true)
            if (!cleanupStarted.compareAndSet(false, true)) {
                cleanupComplete.await()
                return
            }
            try {
                timedStopStage("close_latency_probe", "Остановка проверки задержек") {
                    latencyProbeCoordinator.close()
                }
                closeTun()
                timedStopStage("close_observers", "Остановка callback и фоновых задач") {
                    val resources = synchronized(resourceLock) {
                        listOfNotNull(statusObserver, diagnosticsObserver, identityJob).also {
                            statusObserver = null
                            diagnosticsObserver = null
                            identityJob = null
                        }
                    }
                    resources.forEach(Job::cancel)
                }
                timedStopStage("close_clients", "Отключение клиентов libbox") {
                    closeStatusClient(controller)
                    closeLogClient(controller)
                    val current = synchronized(resourceLock) {
                        client.also { client = null }
                    }
                    runCatching { current?.disconnect() }
                    val selector = synchronized(resourceLock) {
                        selectorClient.also { selectorClient = null }
                    }
                    runCatching { selector?.disconnect() }
                }
                timedStopStage("close_libbox_service", "Остановка сервиса libbox") {
                    runCatching { server?.closeService() }.getOrThrow()
                }
                if (libboxStarted.compareAndSet(true, false)) VpnRuntimeMetrics.libboxClosed()
                timedStopStage("close_command_server", "Закрытие command server") {
                    val current = synchronized(resourceLock) {
                        server.also { server = null }
                    }
                    runCatching { current?.close() }.getOrThrow()
                }
                // Монитор принадлежит сервису и переживает сессию: его наблюдатель
                // нужен восстановлению после отказа. Сессия снимает только свою подписку.
                timedStopStage("close_network", "Отписка от мониторинга сети") {
                    val observer = synchronized(resourceLock) {
                        networkObserver.also { networkObserver = null }
                    }
                    runCatching { observer?.close() }
                }
            } finally {
                VpnRuntimeMetrics.sessionClosed()
                cleanupComplete.countDown()
            }
        }

        private inline fun timedStopStage(
            key: String,
            label: String,
            action: () -> Unit,
        ) {
            val diagnosticGeneration = stopDiagnosticGeneration
            if (diagnosticGeneration != Long.MIN_VALUE) {
                controller.startStopDiagnosticStage(diagnosticGeneration, key, label)
            }
            val error = runCatching(action).exceptionOrNull()
            if (diagnosticGeneration != Long.MIN_VALUE) {
                controller.finishStopDiagnosticStage(diagnosticGeneration, key, error)
            }
        }
    }

    private class RuntimeSwitchException(cause: Throwable) : Exception(cause)

    /**
     * Отдельный код нужен восстановлению: авторизация в Wi-Fi — действие
     * пользователя, но как только Android снимет флаг captive portal, попытку
     * можно повторить автоматически.
     */
    private class CaptivePortalException : IllegalStateException(
        "Интернет требует авторизации в Wi-Fi.",
    ), CodedFailure {
        override val failureCode = CAPTIVE_PORTAL_CODE
        override val userMessage = checkNotNull(message)
        override val technicalDetail = "captive_portal=true"
    }

    private class StrictPrivateDnsException(message: String) : IllegalStateException(message), CodedFailure {
        override val failureCode = "DNS-110"
        override val userMessage = message
        override val technicalDetail = "private_dns=strict"
    }

    private class ConnectionStartupTimeoutException : Exception(
        "Подключение не завершилось за ${CONNECTION_START_TIMEOUT_MILLIS / 1_000} секунд. " +
            "VPN полностью остановлен; повторите после стабилизации сети.",
    ), CodedFailure {
        override val failureCode = "VPN-120"
        override val userMessage = checkNotNull(message)
        override val technicalDetail = "timeout_ms=$CONNECTION_START_TIMEOUT_MILLIS"
    }

    private class ServerHandler(
        private val service: ZapretVpnService,
    ) : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
            available = false
            enabled = false
        }

        override fun serviceReload() = throw UnsupportedOperationException("Reload выполняет Android-сервис.")
        override fun serviceStop() = service.requestStopFromCore()
        override fun setSystemProxyEnabled(enabled: Boolean) {
            check(!enabled) { "Системный proxy не поддерживается." }
        }
        override fun writeDebugMessage(message: String) = Unit
    }

    private abstract class BaseClientHandler : CommandClientHandler {
        override fun connected() = Unit
        override fun disconnected(message: String) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun clearLogs() = Unit
        override fun writeLogs(messageList: LogIterator) = Unit
        override fun writeStatus(message: StatusMessage) = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents) = Unit
        override fun writeGroups(message: OutboundGroupIterator) = Unit
    }

    private class StatusClientHandler(
        private val controller: VpnController,
        private val generation: Long,
    ) : BaseClientHandler() {
        override fun writeStatus(message: StatusMessage) {
            if (generation != controller.currentGeneration() || !message.trafficAvailable) return
            VpnRuntimeMetrics.updateTraffic(message.uplinkTotal, message.downlinkTotal)
            controller.publishTraffic(
                generation = generation,
                uploadDelta = message.uplink,
                downloadDelta = message.downlink,
                uploadTotal = message.uplinkTotal,
                downloadTotal = message.downlinkTotal,
            )
        }
    }

    private class DiagnosticLogClientHandler(
        private val controller: VpnController,
        private val generation: Long,
    ) : BaseClientHandler() {
        override fun disconnected(message: String) {
            controller.publishDiagnosticLogStream(generation, false)
        }

        override fun clearLogs() {
            controller.clearCoreDiagnosticLogs(generation)
        }

        override fun writeLogs(messageList: LogIterator) {
            val collector = CoreDiagnosticBatchCollector()
            while (messageList.hasNext()) {
                val entry = messageList.next()
                collector.add(entry.level, entry.message)
            }
            val batch = collector.result()
            controller.publishCoreDiagnosticLogs(generation, batch.entries, batch.droppedLines)
        }
    }

    private class GroupClientHandler(
        private val controller: VpnController,
        private val generation: Long,
        private val descriptions: Map<String, OutboundDescription>,
        private val primaryGroupTag: String?,
    ) : BaseClientHandler() {

        override fun writeGroups(message: OutboundGroupIterator) {
            val groups = buildList {
                while (message.hasNext()) {
                    val group = message.next()
                    val items = buildList {
                        val iterator = group.items
                        while (iterator.hasNext()) {
                            val item = iterator.next()
                            val description = descriptions[item.tag]
                            add(
                                RuntimeOutboundItem(
                                    tag = item.tag,
                                    type = item.type.ifBlank { description?.type ?: "unknown" },
                                    endpoint = description?.endpoint,
                                ).withRelayHistory(item.urlTestTime, item.urlTestDelay),
                            )
                        }
                    }
                    add(
                        RuntimeSelectorGroup(
                            tag = group.tag,
                            type = group.type,
                            selected = group.selected,
                            selectable = group.selectable,
                            items = items,
                            primary = group.tag == primaryGroupTag,
                        ),
                    )
                }
            }
            controller.publishGroups(generation, groups)
        }
    }

    private enum class ForegroundNotificationState(val text: String) {
        Preparing("Подготовка VPN"),
        ValidatingProfile("Проверка профиля"),
        CheckingNetwork("Проверка сети Android"),
        ValidatingCore("Проверка sing-box"),
        CreatingTun("Создание TUN"),
        CheckingHealth("Проверка DNS и HTTPS"),
        Connected("Подключено"),
        Paused("VPN на паузе по правилу сети"),
        Restarting("Перезапуск VPN"),
        AwaitingNetwork("Ожидание сети Android"),
        Retrying("Повтор подключения"),
        Stopping("Отключение"),
    }

    companion object {
        private const val STATUS_INTERVAL_NANOS = 1_000_000_000L
        private const val ACTION_START = "io.github.zapretkvn.android.vpn.START"
        private const val ACTION_STOP = "io.github.zapretkvn.android.vpn.STOP"
        private const val ACTION_RESUME = "io.github.zapretkvn.android.vpn.RESUME"
        private const val ACTION_SELECT = "io.github.zapretkvn.android.vpn.SELECT"
        private const val ACTION_RESTART = "io.github.zapretkvn.android.vpn.RESTART"
        private const val ACTION_CLEAR_DNS_CACHE = "io.github.zapretkvn.android.vpn.CLEAR_DNS_CACHE"
        private const val ACTION_PING_GROUP = "io.github.zapretkvn.android.vpn.PING_GROUP"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_GROUP_TAG = "group_tag"
        private const val EXTRA_OUTBOUND_TAG = "outbound_tag"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_UPDATER_ROUTING = "updater_routing"
        private const val NOTIFICATION_CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1001
        private const val CONNECTION_START_TIMEOUT_MILLIS = 45_000L

        /** Ожидание зрелой сети перед попыткой; в бюджет подключения не входит. */
        private const val NETWORK_SETTLE_WAIT_MILLIS = 10_000L

        /** Компромисс, если Android так и не подтвердил доступ в интернет. */
        private const val NETWORK_USABLE_WAIT_MILLIS = 15_000L

        /**
         * Бюджеты ступеней внутри ожидания сети. Само ожидание не ограничено:
         * это цена одной итерации предпочтения «зрелая сеть → любая пригодная».
         */
        private const val RECOVERY_SETTLE_WAIT_MILLIS = 30_000L
        private const val RECOVERY_USABLE_WAIT_MILLIS = 180_000L

        /** Bootstrap переживает две смены сети: переход Wi-Fi ↔ cellular не атомарен. */
        private const val BOOTSTRAP_MAX_NETWORK_CHANGES = 2

        private const val CAPTIVE_PORTAL_CODE = "NET-110"
        private val NEW_LINES = Regex("[\\r\\n\\t]+")

        fun startIntent(
            context: Context,
            profileId: String,
            updaterRouting: Boolean = false,
        ): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_UPDATER_ROUTING, updaterRouting)

        fun stopIntent(context: Context): Intent =
            Intent(context, ZapretVpnService::class.java).setAction(ACTION_STOP)

        fun resumeIntent(context: Context): Intent =
            Intent(context, ZapretVpnService::class.java).setAction(ACTION_RESUME)

        fun selectIntent(
            context: Context,
            profileId: String,
            groupTag: String,
            outboundTag: String,
        ): Intent = Intent(context, ZapretVpnService::class.java)
            .setAction(ACTION_SELECT)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .putExtra(EXTRA_GROUP_TAG, groupTag)
            .putExtra(EXTRA_OUTBOUND_TAG, outboundTag)

        fun restartIntent(
            context: Context,
            profileId: String,
            reason: String,
            updaterRouting: Boolean? = null,
        ): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_RESTART)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_REASON, reason)
                .apply {
                    updaterRouting?.let { putExtra(EXTRA_UPDATER_ROUTING, it) }
                }

        fun clearDnsCacheIntent(context: Context): Intent =
            Intent(context, ZapretVpnService::class.java).setAction(ACTION_CLEAR_DNS_CACHE)

        fun pingGroupIntent(context: Context, profileId: String, groupTag: String): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_PING_GROUP)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_GROUP_TAG, groupTag)
    }
}
