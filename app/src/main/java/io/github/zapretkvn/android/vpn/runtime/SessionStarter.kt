package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.apps.AllowedApplicationSink
import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.apps.DisallowedApplicationSink
import io.github.zapretkvn.android.apps.VpnAppScopeResult
import io.github.zapretkvn.android.apps.selectedPackagesForTunBoundary
import io.github.zapretkvn.android.config.BootstrapConfig
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.RuntimeConfigBuilder
import io.github.zapretkvn.android.config.RuntimeConfigOptions
import io.github.zapretkvn.android.config.RuntimeConfigResult
import io.github.zapretkvn.android.diagnostics.DiagnosticRuntimeMap
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.diagnostics.EffectiveOverlaySummary
import io.github.zapretkvn.android.diagnostics.RuntimeErrors
import io.github.zapretkvn.android.diagnostics.RuntimeStartupFailure
import io.github.zapretkvn.android.diagnostics.ServerAddressRedactor
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.engines.singbox.ListStringIterator
import io.github.zapretkvn.android.engines.singbox.SelectorCacheReconciliation
import io.github.zapretkvn.android.hardening.VpnRuntimeHardening
import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.PrivateDnsMode
import io.github.zapretkvn.android.network.UnderlyingNetworkState
import io.github.zapretkvn.android.network.isSettledForConnect
import io.github.zapretkvn.android.network.isUsableForConnect
import io.github.zapretkvn.android.network.policyKey
import io.github.zapretkvn.android.network.probes.HealthCheckResult
import io.github.zapretkvn.android.network.probes.VpnHealthStageOutcome
import io.github.zapretkvn.android.platform.AndroidPlatformAdapter
import io.github.zapretkvn.android.platform.VpnSystemPolicyDetector
import io.github.zapretkvn.android.routing.GlobalRoutingPolicy
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.ZapretVpnService
import io.github.zapretkvn.networkbootstrap.BootstrapFailureException
import io.github.zapretkvn.networkbootstrap.CodedFailure
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Кандидат замены сервера, выбранный после отказа запуска; сохраняется после готовности. */
internal data class PendingReplacement(
    val profileId: String,
    val candidateJson: String,
    val targetTag: String,
)

internal data class StartRequest(
    val token: Long,
    val profileId: String,
    val noCacheLookup: Boolean,
    val updaterRouting: Boolean,
    val replacement: PendingReplacement?,
)

/** Колбэки работающей сессии. Все потокобезопасны: вызываются из потоков libbox и Android. */
internal interface SessionEvents {
    fun onCoreLogs(generation: Long, messages: List<String>)
    fun onObserverUnavailable(generation: Long, message: String)
    fun onCoreServiceStop()
    fun onUnderlyingNetwork(session: CoreSession, state: UnderlyingNetworkState)

    /** Сессия активна, но ещё не опубликована: подключить наблюдателей. */
    fun onSessionActivated(session: CoreSession)

    /** Отказ, подменённый инструментальным тестом, уже после Connected. */
    fun onInjectedFailure(session: CoreSession, code: HysteriaFailureCode)

    /** Подсказка ядра, подменённая инструментальным тестом: идёт через пробу. */
    fun onInjectedHint(session: CoreSession, code: HysteriaFailureCode)
}

/**
 * Конвейер запуска одной попытки подключения.
 *
 * Работает в дочерней задаче рантайма и ничего не решает о восстановлении:
 * при любом отказе закрывает свою сессию и бросает исключение, а решение
 * принимает [VpnRuntime]. Каждый шаг сверяет поколение: команда пользователя
 * заменяет попытку сразу, как только поколение сменилось.
 */
internal class SessionStarter(
    private val service: ZapretVpnService,
    private val container: AppContainer,
    private val sessions: SessionSlot,
    private val networkMonitor: () -> DefaultNetworkMonitor,
    private val foreground: (ForegroundState) -> Unit,
    private val events: SessionEvents,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val controller get() = container.vpnController

    /**
     * Ожидание физической сети вынесено за пределы бюджета подключения: он не
     * должен тратиться на то, что Android ещё поднимает Wi-Fi. Сначала ждём
     * зрелую сеть, затем соглашаемся на любую пригодную; после этого отказ
     * становится `NET-101` — уже восстановимым.
     *
     * @return идентичность сети, на которой начата попытка.
     */
    suspend fun awaitConnectableNetwork(token: Long, profileId: String, updaterRouting: Boolean): String? {
        val monitor = networkMonitor()
        monitor.start()
        if (!monitor.current.isSettledForConnect()) {
            controller.publish(token, VpnConnectionState.Starting(profileId, "Ожидание сети Android", updaterRouting))
            foreground(ForegroundState.CheckingNetwork)
            val settled = try {
                monitor.awaitUnderlying(NETWORK_SETTLE_WAIT_MILLIS) { it.isSettledForConnect() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: BootstrapFailureException) {
                null
            }
            if (settled == null) monitor.awaitUnderlying(NETWORK_USABLE_WAIT_MILLIS) { it.isUsableForConnect() }
        }
        return monitor.current.identity
    }

    /** Запуск с перебором DNS-кандидатов в общем бюджете [CONNECTION_START_TIMEOUT_MILLIS]. */
    suspend fun startWithDeadline(request: StartRequest) {
        val completed = withTimeoutOrNull(CONNECTION_START_TIMEOUT_MILLIS) {
            val configuredMode = container.uiSettingsStore.settings.first().dnsMode
            container.profileStore.initialize()
            val hasProfileDns = configuredMode == DnsMode.Automatic &&
                ConfigAnalyzer.hasProfileDns(container.profileStore.read(request.profileId).json)
            val strictPrivateDns = configuredMode == DnsMode.Automatic && snapshotStrictPrivateDns()
            if (strictPrivateDns) {
                controller.publishDiagnosticWarning(
                    "Strict Private DNS активен: автоматический режим использует DNS Android.",
                )
            }
            val candidates = AutomaticDnsFallbackPolicy.candidates(configuredMode, hasProfileDns, strictPrivateDns)
            var candidateAttemptId = 0
            AutomaticDnsFallbackPolicy.run(
                candidates = candidates,
                onFallback = { previous, candidate, failure ->
                    val failureChain = failure.causes().toList()
                    val failureType = (
                        failureChain.filterIsInstance<CodedFailure>().firstOrNull()?.failureCode
                            ?: failureChain.last().javaClass.simpleName
                        ).take(80)
                    val detail = "Автоматический DNS: ${AutomaticDnsFallbackPolicy.label(previous)} " +
                        "не отвечает ($failureType); пробуем ${AutomaticDnsFallbackPolicy.label(candidate)}." +
                        if (candidate == DnsMode.Android && !strictPrivateDns) {
                            " Системный DNS может передавать запросы без шифрования провайдеру."
                        } else {
                            ""
                        }
                    controller.publishDiagnosticWarning(detail)
                    controller.startConnectionDiagnosticStage(
                        request.token,
                        "dns_fallback_${candidate.name.lowercase()}",
                        "DNS fallback: ${AutomaticDnsFallbackPolicy.label(candidate)}",
                    )
                    controller.publish(
                        request.token,
                        VpnConnectionState.Starting(request.profileId, detail, request.updaterRouting),
                    )
                },
                attempt = { candidate ->
                    candidateAttemptId += 1
                    controller.beginConnectionCandidate(request.token, candidateAttemptId)
                    start(request, candidate)
                    true
                },
            )
        } == true
        if (!completed) throw ConnectionStartupTimeoutException(CONNECTION_START_TIMEOUT_MILLIS)
    }

    /**
     * Быстрый снапшот системного Private DNS до выбора DNS-кандидатов; ошибка
     * определения не блокирует запуск — строгие гейты внутри [start] остаются
     * страховкой.
     */
    private suspend fun snapshotStrictPrivateDns(): Boolean = try {
        val monitor = networkMonitor()
        monitor.start()
        monitor.runOnStableNetwork(accept = { it.isUsableForConnect() }) {
            it.privateDnsMode == PrivateDnsMode.Strict
        }.value
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    private fun checkCurrent(token: Long) {
        check(token == controller.currentGeneration()) { "Запуск отменён." }
    }

    private suspend fun start(request: StartRequest, dnsMode: DnsMode) {
        val token = request.token
        val profileId = request.profileId
        val updaterRouting = request.updaterRouting
        require(profileId.isNotBlank()) { "Профиль не выбран." }
        val systemPolicy = VpnSystemPolicyDetector.detect(service)
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
        request.replacement
            ?.takeIf { it.profileId == profileId }
            ?.let { pending -> profile = profile.copy(json = pending.candidateJson) }
        val storedRouting = withContext(Dispatchers.Default) { RoutingConfigEditor.inspect(profile.json) }
        val policy = container.routingPolicyStore.getOrInitialize(
            GlobalRoutingPolicy(preset = storedRouting.preset, rules = storedRouting.rules),
        )
        val installed = container.ruleSetAssetManager.ensureInstalled()
        val effectiveRouting = withContext(Dispatchers.Default) {
            RoutingConfigEditor.apply(profile.json, policy.preset, policy.rules, installed).json
        }
        profile = profile.copy(json = VpnTestHooks.transformEffectiveRouting(effectiveRouting))
        val uiSettings = container.uiSettingsStore.settings.first()
        val configuredDnsMode = uiSettings.dnsMode
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
                        "Пропущены недоступные приложения: " + preflight.skippedPackages.joinToString(),
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
        controller.publish(token, VpnConnectionState.Starting(profileId, "Проверка сети Android", updaterRouting))
        foreground(ForegroundState.CheckingNetwork)
        val monitor = networkMonitor()
        monitor.start()
        controller.startConnectionDiagnosticStage(token, "bootstrap", "Bootstrap DNS и доступность сервера")
        // До bootstrap: его ошибки («VPN-сервер не отвечает: host:port») уже
        // показывают адрес сервера как «<сервер ep-…>».
        ServerAddressRedactor.registerProfile(profileId, profile.json)
        val networkBootstrap = monitor.runOnStableNetwork(
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
            if (underlying.captivePortal) throw CaptivePortalException()
            if (underlying.privateDnsMode == PrivateDnsMode.Strict &&
                (configuredDnsMode == DnsMode.Secure ||
                    (configuredDnsMode == DnsMode.Automatic && dnsMode != DnsMode.Android))
            ) {
                throw StrictPrivateDnsException(
                    "Strict Private DNS несовместим с этим режимом. Выберите «DNS Android» или «Из JSON».",
                )
            }
            if (underlying.privateDnsMode == PrivateDnsMode.Strict &&
                dnsMode == DnsMode.Android &&
                (!underlying.privateDnsActive || !underlying.validated)
            ) {
                throw StrictPrivateDnsException(
                    "Strict Private DNS не отвечает. Исправьте системную настройку или выберите «Из JSON».",
                )
            }
            container.proxyBootstrapper.prepare(
                profileId = profileId,
                rawJson = profile.json,
                underlying = checkNotNull(underlying.network),
                noCacheLookup = request.noCacheLookup,
            )
        }
        val underlying = networkBootstrap.network
        val preparedBootstrap = networkBootstrap.value
        preparedBootstrap.target?.let { target ->
            ServerAddressRedactor.registerAliases(
                target.hostname,
                preparedBootstrap.addresses.mapNotNull { it.hostAddress },
            )
        }

        controller.startConnectionDiagnosticStage(token, "runtime_config", "Runtime overlay")
        val runtimeJson = when (
            val runtime = RuntimeConfigBuilder.build(
                profile.json,
                enableTrafficStats = true,
                options = RuntimeConfigOptions(
                    dnsMode = dnsMode,
                    proxyIpv4Only = uiSettings.proxyIpv4Only,
                    dnsOverride = uiSettings.dnsOverride,
                    bootstrapHost = preparedBootstrap.overlay,
                    vpnHiding = vpnHiding,
                    healthCheckPackageName = service.packageName,
                    updaterPackageName = service.packageName.takeIf { updaterRouting },
                    blockedPackages = appSelection.blockedPackages,
                ),
            )
        ) {
            is RuntimeConfigResult.Ready -> runtime.json
            is RuntimeConfigResult.Invalid -> error(runtime.message)
        }
        controller.publishEffectiveOverlay(token, EffectiveOverlaySummary.create(runtimeJson, dnsMode))
        controller.startConnectionDiagnosticStage(token, "check_config", "Проверка конфигурации ядром")
        controller.publish(token, VpnConnectionState.Starting(profileId, "Проверка sing-box", updaterRouting))
        foreground(ForegroundState.ValidatingCore)
        Libbox.checkConfig(runtimeJson)
        checkCurrent(token)

        controller.startConnectionDiagnosticStage(token, "platform_adapter", "Подготовка Android VPN adapter")
        controller.publish(token, VpnConnectionState.Starting(profileId, "Создание TUN", updaterRouting))
        foreground(ForegroundState.CreatingTun)
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
        val session = CoreSession(
            profileId = profileId,
            profileName = profile.metadata.name,
            profileJson = profile.json,
            generation = token,
            networkMonitor = monitor,
            networkPolicyKey = underlying.policyKey(),
            outboundDescriptions = outboundDescriptions,
            selectorGroups = selectorGroups,
            primaryGroupTag = primaryGroupTag,
            initialSelectedOutboundTag = selectedOutboundTag,
            runtimeDnsMode = dnsMode,
            updaterRouting = updaterRouting,
            controller = controller,
            scope = scope,
            icmpPingProbe = container.icmpPingProbe,
            latencyStore = container.serverLatencyStore,
        )
        if (!sessions.registerPending(session, token)) {
            session.close()
            throw CancellationException("Запуск отменён.")
        }
        val health: HealthCheckResult
        try {
            session.attachPlatform(
                AndroidPlatformAdapter(
                    service = service,
                    selectedPackages = selectedPackages,
                    scopeMode = appSelection.mode,
                    expectedPackages = effectivePackages,
                    scopePreflight = container.vpnAppScopePreflight,
                    networkMonitor = monitor,
                    sessionName = VpnRuntimeHardening.sessionName(vpnHiding),
                ),
            )
            controller.startConnectionDiagnosticStage(token, "command_server", "Запуск локального command server")
            val commandServer = Libbox.newCommandServer(
                CoreServerHandler(events::onCoreServiceStop),
                session.platform(),
            )
            session.attachServer(commandServer)
            commandServer.start()
            session.openRuntimeErrorClient(
                onLogs = events::onCoreLogs,
                onUnavailable = events::onObserverUnavailable,
            )
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
            session.markLibboxStarted()
            checkCurrent(token)

            // Подписка до любой стартовой пробы: command server хранит ограниченный
            // backlog, и ошибки рукопожатия/транспорта/DNS остаются доступны, даже
            // если health-check провалится и сессия сразу закроется.
            controller.startConnectionDiagnosticStage(token, "core_log", "Снимок bounded core-лога")
            session.openLogClient()
            controller.startConnectionDiagnosticStage(token, "group_client", "Чтение selector-групп")
            val groupClient = Libbox.newCommandClient(
                GroupClientHandler(
                    controller,
                    token,
                    session.outboundDescriptions,
                    session.primaryGroupTag,
                    session::onRuntimeGroups,
                ),
                CommandClientOptions().apply { addCommand(Libbox.CommandGroup) },
            )
            session.attachGroupClient(groupClient)
            groupClient.connect()
            val selectorClient = Libbox.newCommandClient(
                object : BaseClientHandler() {},
                CommandClientOptions().apply { addCommand(Libbox.CommandGroup) },
            )
            session.attachSelectorClient(selectorClient)
            selectorClient.connect()
            checkCurrent(token)
            reconcileSelectorSelection(session, groupClient, runtimeJson)
            checkCurrent(token)
            controller.publish(token, VpnConnectionState.Starting(profileId, "Проверка DNS и HTTPS", updaterRouting))
            foreground(ForegroundState.CheckingHealth)
            val dnsServer = session.platform().internalDnsServer ?: error("libbox не передал внутренний DNS TUN.")
            health = container.vpnHealthPipeline.verify(
                mode = dnsMode,
                internalDnsServer = dnsServer,
                proxyIpFamily = BootstrapConfig.selectedProxyIpFamily(profile.json),
                onNetworkLease = { identity -> controller.recordConnectionVpnNetwork(token, identity.toString()) },
                onNetworkLost = { controller.recordConnectionVpnNetwork(token, lost = true) },
                onStageStarted = { stage ->
                    controller.startConnectionDiagnosticStage(token, stage.diagnosticKey, stage.diagnosticLabel)
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
            checkCurrent(token)
            controller.startConnectionDiagnosticStage(token, "finalize", "Финализация сессии")
            container.proxyBootstrapper.recordSuccess(profileId, preparedBootstrap)
            session.requireRuntimeErrorClient()
            check(sessions.activate(session, token)) { "Запуск отменён." }
            // Закрыть гонку pending→active: отключение наблюдателя между первой
            // проверкой и публикацией тоже должно провалить запуск.
            session.requireRuntimeErrorClient()
            session.attachNetworkObserver(monitor.observe { state -> events.onUnderlyingNetwork(session, state) })
            events.onSessionActivated(session)
            if (health.externalIpProbeAllowed) startIdentityProbe(session)
            // Кандидат замены сохраняется только после готовности конфигурации,
            // ядра, TUN, DNS/HTTPS и всех наблюдателей сессии.
            request.replacement
                ?.takeIf { it.profileId == profileId }
                ?.let { pending -> container.profileStore.update(profileId, pending.candidateJson) }
        } catch (error: Throwable) {
            val startupFailure = if (error is CancellationException) {
                error
            } else {
                RuntimeStartupFailure(error, RuntimeErrors.bestEvidence(controller.runtimeErrors.forGeneration(token)))
            }
            sessions.discard(session)
            session.close()
            throw startupFailure
        }

        if (token == controller.currentGeneration() && sessions.active() === session) {
            // Connected — точка передачи: публикуется только после всех шагов,
            // владеющих сессией. Команда пользователя может заменить попытку
            // сразу, как только это состояние видно.
            controller.publish(
                token,
                VpnConnectionState.Connected(
                    profileId = profileId,
                    profileName = profile.metadata.name,
                    connectedAtEpochMillis = System.currentTimeMillis(),
                    updaterRouting = updaterRouting,
                ),
            )
            foreground(ForegroundState.Connected)
            if (VpnTestHooks.consumeHysteriaFailureObserverDisconnect()) {
                scope.launch {
                    yield()
                    session.simulateFailureLogDisconnect()
                }
            }
            VpnTestHooks.consumeHysteriaFailure()?.let { code -> events.onInjectedFailure(session, code) }
            VpnTestHooks.consumeCoreHint()?.let { code -> events.onInjectedHint(session, code) }
        }
    }

    /** Внешний IP через туннель — для главного экрана; не влияет на подключение. */
    fun startIdentityProbe(session: CoreSession) {
        session.replaceIdentityJob(scope.launch {
            val externalIp = runCatching { container.vpnExternalIpProbe.fetch() }.getOrNull()
            if (externalIp != null && sessions.active() === session) {
                controller.publishExternalIp(session.generation, externalIp)
            }
        })
    }

    /**
     * Навязывает ядру сервер из сохранённого профиля сразу после старта.
     *
     * Без этого `cache.db` переопределял `selector.default`: сервер, выбранный
     * при остановленном ядре, молча игнорировался, а health-check и весь
     * трафик уходили через застрявший в кэше outbound. Отказ команды не
     * прерывает подключение: фактический сервер виден в диагностике.
     */
    private suspend fun reconcileSelectorSelection(session: CoreSession, client: CommandClient, runtimeJson: String) {
        val selections = withContext(Dispatchers.Default) {
            SelectorCacheReconciliation.selections(ConfigAnalyzer.selectorGroups(runtimeJson))
        }
        selections.forEach { selection ->
            try {
                withContext(Dispatchers.IO) { client.selectOutbound(selection.groupTag, selection.outboundTag) }
                session.recordCommandedSelection(selection.groupTag, selection.outboundTag)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                controller.publishDiagnosticWarning(
                    "Не удалось применить сервер ${SecretRedactor.redactInline(selection.outboundTag)} " +
                        "группы ${SecretRedactor.redactInline(selection.groupTag)}: " +
                        SecretRedactor.redactInline(error.message.orEmpty()),
                )
            }
        }
    }

    internal companion object {
        const val CONNECTION_START_TIMEOUT_MILLIS = 45_000L

        /** Ожидание зрелой сети перед попыткой; в бюджет подключения не входит. */
        const val NETWORK_SETTLE_WAIT_MILLIS = 10_000L

        /** Компромисс, если Android так и не подтвердил доступ в интернет. */
        const val NETWORK_USABLE_WAIT_MILLIS = 15_000L

        /** Bootstrap переживает две смены сети: переход Wi-Fi ↔ cellular не атомарен. */
        const val BOOTSTRAP_MAX_NETWORK_CHANGES = 2
    }
}
