package io.github.zapretkvn.android.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.DnsOverride
import io.github.zapretkvn.android.diagnostics.DiagnosticAttemptOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.routing.InstalledRuleSets
import io.github.zapretkvn.android.routing.GlobalRoutingPolicy
import io.github.zapretkvn.android.routing.ManagedRoutingRule
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.android.routing.RoutingMatchType
import io.github.zapretkvn.android.routing.RoutingPreset
import io.github.zapretkvn.android.routing.RoutingRuleAction
import io.github.zapretkvn.android.ui.NetworkTransportSetting
import java.io.File
import java.io.FileInputStream
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnServiceInstrumentedTest {
    @Before
    fun isolateGlobalRoutingPolicy() = runBlocking {
        VpnTestHooks.reset()
        awaitStableUnderlyingNetwork(InstrumentationRegistry.getInstrumentation().targetContext)
        setRoutingPolicy(RoutingPreset.Custom)
    }

    @After
    fun restoreGlobalRoutingPolicy() = runBlocking {
        VpnTestHooks.reset()
        setRoutingPolicy(RoutingPreset.Custom)
    }

    @Test
    fun foregroundNotificationContainsOnlyConnectionState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        val secret = "notification-secret-${System.nanoTime()}"
        allowVpn(packageName)
        val profile = createProfile(container, "token=$secret", TWO_SERVER_DIRECT_CONFIG)
        try {
            container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
            connect(container.vpnController, profile.id)

            val manager = context.getSystemService(NotificationManager::class.java)
            val notification = withTimeout(5_000) {
                while (true) {
                    manager.activeNotifications
                        .singleOrNull { it.id == 1001 }
                        ?.notification
                        ?.let { return@withTimeout it }
                    delay(25)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Connected notification was not published")
            }
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            assertEquals("Zapret KVN", title)
            assertTrue(
                "Notification must contain only a connection state: $text",
                text in setOf(
                    "Подготовка VPN",
                    "Проверка профиля",
                    "Проверка сети Android",
                    "Проверка sing-box",
                    "Создание TUN",
                    "Проверка DNS и HTTPS",
                    "Подключено",
                    "Перезапуск VPN",
                    "Отключение",
                ),
            )
            assertFalse("Notification leaked profile metadata", "$title\n$text".contains(secret))
        } finally {
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun logStreamExistsOnlyWhileDiagnosticsIsVisible() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        val profile = createProfile(container, "Lifecycle diagnostics", TWO_SERVER_DIRECT_CONFIG)
        try {
            container.vpnController.setDiagnosticsVisible(false)
            container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
            connect(container.vpnController, profile.id)
            withTimeout(5_000) {
                while (VpnRuntimeMetrics.snapshot().activeLogClients != 0) delay(25)
            }
            assertEquals(0, VpnRuntimeMetrics.snapshot().activeLogClients)

            container.vpnController.setDiagnosticsVisible(true)
            withTimeout(5_000) {
                while (VpnRuntimeMetrics.snapshot().activeLogClients != 1) delay(25)
            }
            assertTrue(container.vpnController.diagnostics.value.logStreamActive)

            container.vpnController.setDiagnosticsVisible(false)
            withTimeout(5_000) {
                while (VpnRuntimeMetrics.snapshot().activeLogClients != 0) delay(25)
            }
            assertFalse(container.vpnController.diagnostics.value.logStreamActive)
        } finally {
            container.vpnController.setDiagnosticsVisible(false)
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun statusStreamExistsOnlyWhileHomeIsVisible() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        val profile = createProfile(container, "Lifecycle status", TWO_SERVER_DIRECT_CONFIG)
        try {
            container.vpnController.setHomeVisible(false)
            container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
            connect(container.vpnController, profile.id)
            delay(1_200)
            assertEquals(0, VpnRuntimeMetrics.snapshot().activeStatusClients)
            assertEquals(0, VpnRuntimeMetrics.trafficUpdateCount())

            container.vpnController.setHomeVisible(true)
            withTimeout(5_000) {
                while (VpnRuntimeMetrics.snapshot().activeStatusClients != 1) delay(25)
            }
            withTimeout(5_000) {
                while (VpnRuntimeMetrics.trafficUpdateCount() == 0) delay(25)
            }
            assertTrue(container.vpnController.sessionStats.value.statusStreamActive)

            container.vpnController.setHomeVisible(false)
            withTimeout(5_000) {
                while (VpnRuntimeMetrics.snapshot().activeStatusClients != 0) delay(25)
            }
            val stoppedAt = VpnRuntimeMetrics.trafficUpdateCount()
            delay(1_200)
            assertEquals(stoppedAt, VpnRuntimeMetrics.trafficUpdateCount())
            assertFalse(container.vpnController.sessionStats.value.statusStreamActive)
        } finally {
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun connectSelectWithoutTunRestartAndStop() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        shell("appops set $packageName ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        }

        container.profileStore.initialize()
        val profile = container.profileStore.create(
            name = "Instrumented VPN",
            rawJson = TWO_SERVER_DIRECT_CONFIG,
            source = ProfileSource.RawJson,
        )
        try {
            container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
            assertEquals(null, container.vpnController.permissionIntent())

            val connected = connectResult(container.vpnController, profile.id)
            assertTrue("VPN failed: $connected", connected is VpnConnectionState.Connected)
            connected as VpnConnectionState.Connected
            val firstNetwork = waitForVpnNetwork(context)
            assertNotNull(firstNetwork)

            val groups = withTimeoutOrNull(20_000) {
                container.vpnController.selectorGroups.first { values ->
                    values.any { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }
                }
            } ?: error(
                "Selector groups were not published; state=${container.vpnController.state.value}, " +
                    "groups=${container.vpnController.selectorGroups.value}, " +
                    "attempt=${container.vpnController.diagnostics.value.connectionAttempt}",
            )
            assertEquals("server-a", groups.first { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }.selected)

            container.vpnController.selectOutbound(
                profile.id,
                ConfigAnalyzer.MANAGED_SELECTOR_TAG,
                "server-b",
            )
            val selected = withTimeoutOrNull(20_000) {
                container.vpnController.selectorGroups.first { values ->
                    values.any {
                        it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG && it.selected == "server-b"
                    }
                }
            }
            checkNotNull(selected) {
                "Selector command did not publish server-b; " +
                    "state=${container.vpnController.state.value}, " +
                    "groups=${container.vpnController.selectorGroups.value}, " +
                    "attempt=${container.vpnController.diagnostics.value.connectionAttempt}"
            }
            val afterSwitch = container.vpnController.state.value
            assertTrue(afterSwitch is VpnConnectionState.Connected)
            assertEquals(connected.connectedAtEpochMillis, (afterSwitch as VpnConnectionState.Connected).connectedAtEpochMillis)
            assertEquals(firstNetwork, waitForVpnNetwork(context))

            val saved = container.profileStore.read(profile.id).json
            assertEquals(
                "server-b",
                ConfigAnalyzer.selectorGroups(saved)
                    .first { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }
                    .default,
            )
        } finally {
            container.vpnController.stop()
            withTimeout(20_000) {
                container.vpnController.state.first { it is VpnConnectionState.Stopped }
            }
            withTimeout(20_000) {
                while (hasVpnNetwork(context)) delay(50)
            }
            container.profileStore.delete(profile.id)
            shell("appops set $packageName ACTIVATE_VPN default")
        }
    }

    @Test
    fun singletonSelectorIsPublishedAndRelayProbeKeepsSelection() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.profileStore.initialize()
        val profile = container.profileStore.create(
            name = "Singleton relay probe",
            rawJson = SINGLE_SERVER_DIRECT_CONFIG,
            source = ProfileSource.RawJson,
        )
        try {
            container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
            val connected = connectResult(container.vpnController, profile.id)
            assertTrue("VPN failed: $connected", connected is VpnConnectionState.Connected)
            val initial = withTimeout(20_000) {
                container.vpnController.selectorGroups.first { groups ->
                    groups.any { group ->
                        group.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG && group.items.size == 1
                    }
                }.first { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }
            }
            assertEquals("server-only", initial.selected)

            container.vpnController.measureGroup(initial.tag)
            val completed = withTimeout(20_000) {
                container.vpnController.selectorGroups.first { groups ->
                    groups.firstOrNull { it.tag == initial.tag }
                        ?.probeProgress
                        ?.running == false
                }.first { it.tag == initial.tag }
            }
            assertEquals("server-only", completed.selected)
            assertFalse(completed.items.single().relay is LatencyProbeState.Running)
            assertTrue(completed.items.single().relay != LatencyProbeState.NotTested)
            assertEquals(
                LatencyProbeState.Unsupported(LatencyUnsupportedReason.MissingEndpoint),
                completed.items.single().icmp,
            )
        } finally {
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun selectedAppEntersTunWhileControlAppStaysDirect() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testPackage = instrumentation.context.packageName
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        val profile = createProfile(container, "Per-app traffic", TWO_SERVER_DIRECT_CONFIG)
        try {
            container.appSelectionStore.setMode(AppScopeMode.Include)
            container.appSelectionStore.replaceAllowlist(setOf(testPackage))
            connect(container.vpnController, profile.id)
            val tun = waitForVpnInterface(context)
            awaitTrafficStatus(container.vpnController)

            val selectedBefore = VpnRuntimeMetrics.trafficTotal()
            repeat(4) {
                val result = awaitSuccessfulControlCall(context, ControlTrafficProvider.METHOD_TCP)
                assertNotEquals(Process.myUid(), result.getInt(ControlTrafficProvider.RESULT_UID))
            }
            val selectedAfter = waitForTrafficGrowth(selectedBefore, MIN_SELECTED_TUN_BYTES)
            assertTrue("Selected traffic did not enter $tun", selectedAfter - selectedBefore >= MIN_SELECTED_TUN_BYTES)

            // CommandStatus is sampled once per second. Wait until all selected-provider bytes
            // have reached two unchanged samples before attributing later growth to shell UID.
            val controlBefore = awaitTrafficQuiescence()
            repeat(3) {
                val directResult = shell("ping -c 1 -W 3 1.1.1.1")
                assertTrue("Unselected shell UID lost direct network: $directResult", "1 received" in directResult)
            }
            val updateBefore = VpnRuntimeMetrics.trafficUpdateCount()
            withTimeout(3_000) {
                while (VpnRuntimeMetrics.trafficUpdateCount() == updateBefore) delay(25)
            }
            val controlAfter = VpnRuntimeMetrics.trafficTotal()
            assertTrue(
                "Unselected shell UID unexpectedly entered $tun: ${controlAfter - controlBefore} bytes",
                controlAfter - controlBefore <= MAX_IDLE_TUN_GROWTH,
            )
            assertTrue(testPackage in container.appSelectionStore.selection.first().allowedPackages)
        } finally {
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            container.appSelectionStore.setMode(AppScopeMode.Include)
            denyVpn(packageName)
        }
    }

    @Test
    fun blockedAppCanBeTheOnlyIncludePackageAndGetsNetworkReject() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val blockedPackage = instrumentation.context.packageName
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        val profile = createProfile(container, "Whole-app block", TWO_SERVER_DIRECT_CONFIG)
        try {
            container.appSelectionStore.setMode(AppScopeMode.Include)
            container.appSelectionStore.replaceAllowlist(emptySet())
            container.appSelectionStore.replaceBlocklist(setOf(blockedPackage))
            connect(container.vpnController, profile.id)
            waitForVpnInterface(context)
            awaitActiveResources()

            val blocked = controlCall(context, ControlTrafficProvider.METHOD_TCP)
            assertFalse(
                "Blocked package unexpectedly reached the network",
                blocked.getBoolean(ControlTrafficProvider.RESULT_SUCCESS),
            )
            val selection = container.appSelectionStore.selection.first()
            assertTrue(selection.allowedPackages.isEmpty())
            assertEquals(setOf(blockedPackage), selection.blockedPackages)
        } finally {
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            container.appSelectionStore.replaceBlocklist(emptySet())
            container.appSelectionStore.setMode(AppScopeMode.Include)
            denyVpn(packageName)
        }
    }

    @Test
    fun routeRejectBlocksSelectedIpv4WhileIpv6ControlStillUsesDirectRule() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        GateEchoServer().use { echo ->
            val rules = listOf(
                ManagedRoutingRule(
                    RoutingMatchType.IpCidr,
                    listOf("192.0.2.0/24"),
                    RoutingRuleAction.Block,
                ),
            )
            setRoutingPolicy(RoutingPreset.Custom, rules)
            val configured = RoutingConfigEditor.apply(
                raw = GateProfiles.directOverride(echo.reachableAddress, echo.port),
                preset = RoutingPreset.Custom,
                manualRules = rules,
                installed = InstalledRuleSets(1, emptyMap()),
            ).json
            val profile = createProfile(container, "Route reject", configured)
            try {
                container.appSelectionStore.setMode(AppScopeMode.Include)
                container.appSelectionStore.replaceAllowlist(setOf(instrumentation.context.packageName))
                connect(container.vpnController, profile.id)
                awaitActiveResources()

                val blocked = controlCall(
                    context,
                    ControlTrafficProvider.METHOD_TCP_ECHO,
                    echoArguments(DOCUMENTATION_IPV4, echo.port, 1024, 0x61),
                )
                assertFalse("IPv4 reject unexpectedly succeeded", blocked.getBoolean(ControlTrafficProvider.RESULT_SUCCESS))
                awaitSuccessfulControlCall(
                    context,
                    ControlTrafficProvider.METHOD_TCP_ECHO,
                    echoArguments(DOCUMENTATION_IPV6, echo.port, 1024, 0x62),
                    "IPv6 direct control",
                )
            } finally {
                stopIfNeeded(container.vpnController, context)
                container.profileStore.delete(profile.id)
                container.appSelectionStore.setMode(AppScopeMode.Include)
                denyVpn(packageName)
            }
        }
        Unit
    }

    @Test
    fun everyPresetRoutesRealRuAndNonRuDomainIpv4Ipv6ThroughExpectedPath() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        shell("settings delete global private_dns_specifier")
        awaitPrivateDns(context, PrivateDnsMode.Off)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(setOf(instrumentation.context.packageName))
        GateEchoServer().use { echo ->
            GateSocksServer(echo.reachableAddress, echo.port).use { socks ->
                val installed = container.ruleSetAssetManager.ensureInstalled()
                try {
                    RoutingPreset.entries.forEachIndexed { index, preset ->
                        val ruDomain = "gate-$index.ru"
                        val nonRuDomain = "gate-$index.example"
                        val rules = gateRules(preset, ruDomain, nonRuDomain)
                        container.routingPolicyStore.set(GlobalRoutingPolicy(preset, rules))
                        val edited = RoutingConfigEditor.apply(
                            raw = GateProfiles.routingMatrix(
                                echo.reachableAddress,
                                socks.port,
                                ruDomain,
                                nonRuDomain,
                            ),
                            preset = preset,
                            manualRules = rules,
                            installed = installed,
                        )
                        assertEquals(preset, edited.inspection.preset)
                        assertTrue(edited.inspection.summary.startsWith(preset.detail))
                        assertFalse(edited.json.contains("package_name"))
                        val profile = createProfile(
                            container,
                            "Gate ${preset.name}",
                            GateProfiles.routingMatrix(
                                echo.reachableAddress,
                                socks.port,
                                ruDomain,
                                nonRuDomain,
                            ),
                        )
                        try {
                            VpnTestHooks.setEffectiveRoutingTransform { effective ->
                                GateProfiles.withDestinationOverrides(
                                    effective,
                                    echo.reachableAddress,
                                    echo.port,
                                )
                            }
                            connect(container.vpnController, profile.id)
                            awaitActiveResources()
                            waitForVpnNetwork(context)
                            awaitSocksQuiescence(socks)
                            gateProbes(preset, ruDomain, nonRuDomain).forEach { probe ->
                                assertGatePath(echo, socks, probe)
                            }
                        } finally {
                            stopIfNeeded(container.vpnController, context)
                            VpnTestHooks.reset()
                            container.profileStore.delete(profile.id)
                        }
                    }
                } finally {
                    container.appSelectionStore.setMode(AppScopeMode.Include)
                    container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
                    shell("settings put global private_dns_mode opportunistic")
                    denyVpn(packageName)
                }
            }
        }
    }

    @Test
    fun embeddedDohBypassesDocumentedDomainOnlyBlockWhileSystemDnsIsRejected() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        shell("settings delete global private_dns_specifier")
        awaitPrivateDns(context, PrivateDnsMode.Off)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        val blockedHost = "www.iana.org"
        val rules = listOf(
            ManagedRoutingRule(
                RoutingMatchType.Domain,
                listOf(blockedHost),
                RoutingRuleAction.Block,
            ),
        )
        setRoutingPolicy(RoutingPreset.Custom, rules)
        val configured = RoutingConfigEditor.apply(
            raw = GateProfiles.embeddedDohLimit(blockedHost),
            preset = RoutingPreset.Custom,
            manualRules = rules,
            installed = InstalledRuleSets(1, emptyMap()),
        ).json
        val profile = createProfile(container, "Embedded DoH limitation", configured)
        try {
            container.appSelectionStore.setMode(AppScopeMode.Include)
            container.appSelectionStore.replaceAllowlist(setOf(instrumentation.context.packageName))
            connect(container.vpnController, profile.id)
            awaitActiveResources()
            val systemDns = GateTrafficClient.tunDns(blockedHost)
            assertFalse(
                "Managed standard DNS unexpectedly bypassed domain reject",
                systemDns.success,
            )

            val embedded = GateTrafficClient.embeddedDohConnect(blockedHost)
            assertTrue(
                "Embedded DoH numeric path should demonstrate the documented limitation: ${embedded.error}",
                embedded.success,
            )
            assertTrue(
                "Embedded DoH did not return a numeric answer",
                embedded.resolvedAddress?.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) == true,
            )
        } finally {
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            shell("settings put global private_dns_mode opportunistic")
            denyVpn(packageName)
        }
    }

    @Test
    fun productionRuleSetLookupCpuRamColdStartAndSizeStayBounded() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(setOf(instrumentation.context.packageName))
        GateEchoServer().use { echo ->
            GateSocksServer(echo.reachableAddress, echo.port).use { socks ->
                val assetRoot = File(context.filesDir, "rule-sets")
                assetRoot.deleteRecursively()
                val extractionStart = System.nanoTime()
                val installed = container.ruleSetAssetManager.ensureInstalled()
                val extractionMillis = (System.nanoTime() - extractionStart) / 1_000_000
                val totalBytes = installed.paths.values.sumOf { File(it).length() }
                setRoutingPolicy(RoutingPreset.RussiaDirect)
                val profile = createProfile(
                    container,
                    "Routing performance",
                    GateProfiles.routingMatrix(
                        echo.reachableAddress,
                        socks.port,
                        "perf-gate.ru",
                        "perf-gate.example",
                    ),
                )
                try {
                    VpnTestHooks.setEffectiveRoutingTransform { effective ->
                        GateProfiles.withDestinationOverrides(
                            effective,
                            echo.reachableAddress,
                            echo.port,
                        )
                    }
                    val connectStart = System.nanoTime()
                    connect(container.vpnController, profile.id)
                    awaitActiveResources()
                    val coldConnectMillis = (System.nanoTime() - connectStart) / 1_000_000
                    repeat(6) { iteration ->
                        val target = if (iteration % 2 == 0) RU_IPV4 else NON_RU_IPV4
                        assertGatePath(
                            echo,
                            socks,
                            GateProbe("warm-$iteration", target, if (iteration % 2 == 0) GatePath.Direct else GatePath.Proxy),
                        )
                    }
                    System.gc()
                    delay(250)
                    val pssBeforeKb = Debug.getPss()
                    val cpuBeforeMillis = Process.getElapsedCpuTime()
                    val flowStart = System.nanoTime()
                    repeat(PERF_FLOW_COUNT) { iteration ->
                        val proxy = iteration % 2 != 0
                        assertGatePath(
                            echo,
                            socks,
                            GateProbe(
                                "lookup-$iteration",
                                if (proxy) NON_RU_IPV4 else RU_IPV4,
                                if (proxy) GatePath.Proxy else GatePath.Direct,
                                payloadSize = 256,
                            ),
                        )
                    }
                    val wallMillis = (System.nanoTime() - flowStart) / 1_000_000
                    val cpuMillis = Process.getElapsedCpuTime() - cpuBeforeMillis
                    delay(250)
                    val pssAfterKb = Debug.getPss()
                    val pssGrowthKb = (pssAfterKb - pssBeforeKb).coerceAtLeast(0)
                    val cpuPerFlowMillis = cpuMillis.toDouble() / PERF_FLOW_COUNT

                    println(
                        "ROUTING_DEVICE_PERF api=${Build.VERSION.SDK_INT} bytes=$totalBytes " +
                            "extract_ms=$extractionMillis cold_connect_ms=$coldConnectMillis " +
                            "flows=$PERF_FLOW_COUNT wall_ms=$wallMillis cpu_ms=$cpuMillis " +
                            "cpu_ms_per_flow=$cpuPerFlowMillis pss_before_kb=$pssBeforeKb " +
                            "pss_after_kb=$pssAfterKb pss_growth_kb=$pssGrowthKb",
                    )
                    assertEquals(50_114L, totalBytes)
                    assertTrue("Rule-set extraction took ${extractionMillis}ms", extractionMillis < 5_000)
                    assertTrue("Cold VPN start took ${coldConnectMillis}ms", coldConnectMillis < 20_000)
                    assertTrue("Lookup CPU is ${cpuPerFlowMillis}ms/flow", cpuPerFlowMillis < 250.0)
                    assertTrue("Lookup PSS grew by ${pssGrowthKb}KiB", pssGrowthKb < 32 * 1024)
                } finally {
                    stopIfNeeded(container.vpnController, context)
                    VpnTestHooks.reset()
                    container.profileStore.delete(profile.id)
                    container.appSelectionStore.setMode(AppScopeMode.Include)
                    container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
                    denyVpn(packageName)
                }
            }
        }
    }

    @Test
    fun advancedExcludeModeKeepsSelectedPackageOutsideTun() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testPackage = instrumentation.context.packageName
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        GateEchoServer().use { echo ->
            val profile = createProfile(
                container,
                "Exclude contract",
                GateProfiles.directOverride(echo.reachableAddress, echo.port),
            )
            try {
                container.appSelectionStore.replaceAllowlist(setOf(testPackage))
                container.appSelectionStore.setMode(AppScopeMode.Exclude)
                connect(container.vpnController, profile.id)
                awaitActiveResources()

                val outsideTun = controlCall(
                    context,
                    ControlTrafficProvider.METHOD_TCP_ECHO,
                    echoArguments(DOCUMENTATION_IPV4, echo.port, 1024, 0x63),
                )
                assertFalse(
                    "Excluded package unexpectedly used the TUN override",
                    outsideTun.getBoolean(ControlTrafficProvider.RESULT_SUCCESS),
                )
                assertTrue(container.vpnController.state.value is VpnConnectionState.Connected)
            } finally {
                stopIfNeeded(container.vpnController, context)
                container.profileStore.delete(profile.id)
                container.appSelectionStore.setMode(AppScopeMode.Include)
                denyVpn(packageName)
            }
        }
    }

    @Test
    fun ipv4Ipv6TcpUdpTravelThroughTun() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        GateEchoServer().use { echo ->
            val profile = createProfile(
                container,
                "IPv4 IPv6 TCP UDP",
                GateProfiles.directOverride(echo.reachableAddress, echo.port),
            )
            try {
                container.appSelectionStore.replaceAllowlist(setOf(instrumentation.context.packageName))
                connect(container.vpnController, profile.id)
                awaitActiveResources()
                val tun = waitForVpnInterface(context)
                awaitTrafficStatus(container.vpnController)
                val before = VpnRuntimeMetrics.trafficTotal()
                val probes = listOf(
                    "IPv4/TCP" to (ControlTrafficProvider.METHOD_TCP_ECHO to echoArguments(DOCUMENTATION_IPV4, echo.port, 16 * 1024, 0x41)),
                    "IPv6/TCP" to (ControlTrafficProvider.METHOD_TCP_ECHO to echoArguments(DOCUMENTATION_IPV6, echo.port, 16 * 1024, 0x42)),
                    "IPv4/UDP" to (ControlTrafficProvider.METHOD_UDP_ECHO to echoArguments(DOCUMENTATION_IPV4, echo.port, 1_200, 0x43, repeat = 16)),
                    "IPv6/UDP" to (ControlTrafficProvider.METHOD_UDP_ECHO to echoArguments(DOCUMENTATION_IPV6, echo.port, 1_200, 0x44, repeat = 16)),
                )
                probes.forEach { (label, probe) ->
                    awaitSuccessfulControlCall(context, probe.first, probe.second, label)
                }
                val after = waitForTrafficGrowth(before, MIN_PROTOCOL_TUN_BYTES)
                assertTrue("Protocol matrix did not cross TUN", after - before >= MIN_PROTOCOL_TUN_BYTES)
            } finally {
                stopIfNeeded(container.vpnController, context)
                container.profileStore.delete(profile.id)
                denyVpn(packageName)
            }
        }
    }

    @Test
    fun hysteria2CarriesTcpOverRealQuic() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.uiSettingsStore.setVpnHidingBlockLocalEndpoints(false)
        GateEchoServer().use { echo ->
            val quicPort = freeUdpPort()
            val profile = createProfile(
                container,
                "Hysteria2 QUIC",
                GateProfiles.hysteria2Loopback(echo.reachableAddress, quicPort, echo.port),
            )
            try {
                container.appSelectionStore.replaceAllowlist(setOf(instrumentation.context.packageName))
                connect(container.vpnController, profile.id)
                awaitActiveResources()
                awaitTrafficStatus(container.vpnController)
                val before = VpnRuntimeMetrics.trafficTotal()
                listOf(
                    "IPv4/QUIC" to echoArguments(DOCUMENTATION_IPV4, echo.port, 16 * 1024, 0x51),
                    "IPv6/QUIC" to echoArguments(DOCUMENTATION_IPV6, echo.port, 16 * 1024, 0x52),
                ).forEach { (label, arguments) ->
                    awaitSuccessfulControlCall(
                        context,
                        ControlTrafficProvider.METHOD_TCP_ECHO,
                        arguments,
                        label,
                    )
                }
                val after = waitForTrafficGrowth(before, MIN_PROTOCOL_TUN_BYTES)
                assertTrue("Hysteria2 payload did not cross TUN", after - before >= MIN_PROTOCOL_TUN_BYTES)
            } finally {
                stopIfNeeded(container.vpnController, context)
                container.uiSettingsStore.setVpnHidingBlockLocalEndpoints(true)
                container.profileStore.delete(profile.id)
                denyVpn(packageName)
            }
        }
    }

    @Test
    fun protectFalseAndPostEstablishFailureCloseEverything() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        val profile = createProfile(container, "Fault lifecycle", TWO_SERVER_DIRECT_CONFIG)
        try {
            VpnTestHooks.failNextProtect()
            val protectError = connectResult(container.vpnController, profile.id)
            assertTrue(protectError is VpnConnectionState.Error)
            assertTrue((protectError as VpnConnectionState.Error).message.contains("проверочный сокет"))
            awaitCompletelyIdle(context)

            VpnTestHooks.failNextPostEstablish()
            val postEstablishError = connectResult(container.vpnController, profile.id)
            assertTrue(postEstablishError is VpnConnectionState.Error)
            assertTrue((postEstablishError as VpnConnectionState.Error).message.contains("после создания TUN"))
            awaitCompletelyIdle(context)
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun healthFailureNeverPublishesConnectedAndClosesTun() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        val profile = createProfile(container, "Health fail-close", TWO_SERVER_DIRECT_CONFIG)
        try {
            awaitCompletelyIdle(context)
            awaitStableUnderlyingNetwork(context)
            VpnTestHooks.reset()
            VpnTestHooks.failNextHealthCheck()
            val state = startAndAwaitTerminal(container.vpnController, profile.id)
            assertTrue(state is VpnConnectionState.Error)
            assertTrue((state as VpnConnectionState.Error).message.contains("health-check"))
            assertEquals(
                DiagnosticAttemptOutcome.Failed,
                container.vpnController.diagnostics.value.connectionAttempt?.outcome,
            )
            awaitCompletelyIdle(context)
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun deadInternalDnsFailsClosedAndImmediatelyRestoresOrdinaryAndroidNetwork() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.uiSettingsStore.setDnsMode(DnsMode.Secure)
        val profile = createProfile(container, "Dead internal DNS", TWO_SERVER_DIRECT_CONFIG)
        try {
            VpnTestHooks.failNextDnsProbe()
            val state = startAndAwaitTerminal(container.vpnController, profile.id)
            assertTrue(state is VpnConnectionState.Error)
            assertTrue((state as VpnConnectionState.Error).message.contains("DNS через VPN"))
            val dnsStage = container.vpnController.diagnostics.value.connectionAttempt
                ?.stages
                ?.singleOrNull { it.key == "dns_udp" }
            assertEquals(DiagnosticStageStatus.Failed, dnsStage?.status)
            assertEquals("test_override", dnsStage?.detail)
            awaitCompletelyIdle(context)

            val ordinary = withTimeout(10_000) {
                while (true) {
                    connectivity.activeNetwork?.let { network ->
                        val capabilities = connectivity.getNetworkCapabilities(network)
                        if (capabilities != null &&
                            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        ) {
                            return@withTimeout network
                        }
                    }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Ordinary Android network was not restored")
            }
            assertTrue(BootstrapResolver().resolve(ordinary, "example.com").isNotEmpty())
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun automaticDnsFallsBackOnceFromAndroidToSecureAfterConfirmedDnsFailure() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.uiSettingsStore.setDnsMode(DnsMode.Automatic)
        val profile = createProfile(container, "Automatic DNS fallback", TWO_SERVER_DIRECT_CONFIG)
        try {
            VpnTestHooks.reset()
            VpnTestHooks.failNextDnsProbe()
            val terminal = startAndAwaitTerminal(container.vpnController, profile.id, timeoutMillis = 40_000)
            assertTrue("Automatic DNS fallback failed: $terminal", terminal is VpnConnectionState.Connected)

            val diagnostics = container.vpnController.diagnostics.value
            assertTrue(
                diagnostics.connectionAttempt?.stages.orEmpty().any {
                    it.key == "dns_fallback_secure" && it.status == DiagnosticStageStatus.Success
                },
            )
            assertTrue(diagnostics.effectiveOverlay.orEmpty().contains("\"dns_mode\": \"Secure\""))
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun captivePortalStopsBeforeTun() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        val profile = createProfile(container, "Captive portal", TWO_SERVER_DIRECT_CONFIG)
        try {
            VpnTestHooks.reportNextNetworkAsCaptivePortal()
            val state = connectResult(container.vpnController, profile.id)
            assertTrue(state is VpnConnectionState.Error)
            assertTrue((state as VpnConnectionState.Error).message.contains("авторизации"))
            assertFalse(hasVpnNetwork(context))
            assertEquals(VpnRuntimeSnapshot.Idle, VpnRuntimeMetrics.snapshot())
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun unavailableProxyMakesBothManagedDohTransportsFailClosed() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.uiSettingsStore.setDnsMode(DnsMode.Secure)
        try {
            GateEchoServer().use { echo ->
                val profile = createProfile(
                    container,
                    "Unavailable DNS proxy",
                    deadManagedHysteria2Config(echo.reachableAddress, freeUdpPort()),
                )
                try {
                    VpnTestHooks.reset()
                    val state = startAndAwaitTerminal(container.vpnController, profile.id, timeoutMillis = 30_000)
                    assertTrue("Dead proxy unexpectedly connected: $state", state is VpnConnectionState.Error)
                    assertTrue((state as VpnConnectionState.Error).message.contains("DNS через VPN"))
                    awaitCompletelyIdle(context)
                } finally {
                    stopIfNeeded(container.vpnController, context)
                    container.profileStore.delete(profile.id)
                }
            }
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            denyVpn(packageName)
        }
    }

    @Test
    fun realSecureDnsAndHttpsHealthCompleteBeforeConnected() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.uiSettingsStore.setDnsMode(DnsMode.Secure)
        val profile = createProfile(container, "Real managed health", TWO_SERVER_DIRECT_CONFIG)
        try {
            VpnTestHooks.reset()
            val before = container.vpnController.state.value
            container.vpnController.start(profile.id)
            withTimeout(10_000) { container.vpnController.state.first { it != before } }
            val terminal = withTimeout(25_000) {
                container.vpnController.state.first {
                    it is VpnConnectionState.Connected || it is VpnConnectionState.Error
                }
            }
            assertTrue("Managed DNS/HTTPS health failed: $terminal", terminal is VpnConnectionState.Connected)
        } finally {
            stopIfNeeded(container.vpnController, context)
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun realAndroidDnsHealthUsesPlatformResolverBeforeConnected() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.uiSettingsStore.setDnsMode(DnsMode.Android)
        val profile = createProfile(container, "Real Android DNS health", TWO_SERVER_DIRECT_CONFIG)
        try {
            VpnTestHooks.reset()
            val before = container.vpnController.state.value
            container.vpnController.start(profile.id)
            withTimeout(10_000) { container.vpnController.state.first { it != before } }
            val terminal = withTimeout(25_000) {
                container.vpnController.state.first {
                    it is VpnConnectionState.Connected || it is VpnConnectionState.Error
                }
            }
            assertTrue("Android DNS/HTTPS health failed: $terminal", terminal is VpnConnectionState.Connected)
        } finally {
            stopIfNeeded(container.vpnController, context)
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun defaultDnsOverrideResolvesNtcPartyAndPreservesHttpsIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.uiSettingsStore.setDnsMode(DnsMode.Android)
        container.uiSettingsStore.setDnsOverride(
            DnsOverride.DEFAULT_HOSTNAME,
            DnsOverride.DEFAULT_IPV4_ADDRESS,
        )
        container.uiSettingsStore.setDnsOverrideEnabled(true)
        val profile = createProfile(container, "DNS override", TWO_SERVER_DIRECT_CONFIG)
        try {
            VpnTestHooks.reset()
            val before = container.vpnController.state.value
            container.vpnController.start(profile.id)
            withTimeout(10_000) { container.vpnController.state.first { it != before } }
            val terminal = withTimeout(25_000) {
                container.vpnController.state.first {
                    it is VpnConnectionState.Connected || it is VpnConnectionState.Error
                }
            }
            assertTrue("Managed Android DNS failed: $terminal", terminal is VpnConnectionState.Connected)

            val dns = GateTrafficClient.tunDns(DnsOverride.DEFAULT_HOSTNAME)
            assertTrue("Override DNS failed: ${dns.error}", dns.success)
            assertEquals(DnsOverride.DEFAULT_IPV4_ADDRESS, dns.resolvedAddress)
            val https = GateTrafficClient.https(DnsOverride.DEFAULT_HOSTNAME)
            assertTrue("Override HTTPS failed: ${https.error}", https.success)
            assertEquals(DnsOverride.DEFAULT_IPV4_ADDRESS, https.resolvedAddress)
        } finally {
            stopIfNeeded(container.vpnController, context)
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            container.uiSettingsStore.setDnsOverride(
                DnsOverride.DEFAULT_HOSTNAME,
                DnsOverride.DEFAULT_IPV4_ADDRESS,
            )
            container.uiSettingsStore.setDnsOverrideEnabled(true)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun strictPrivateDnsWorkingAndBrokenFollowModeContract() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        val profile = createProfile(container, "Strict Private DNS", TWO_SERVER_DIRECT_CONFIG)
        try {
            shell("settings put global private_dns_specifier dns.google")
            shell("settings put global private_dns_mode hostname")
            awaitPrivateDns(context, PrivateDnsMode.Strict, "dns.google", expectedActive = true)

            VpnTestHooks.reset()
            container.uiSettingsStore.setDnsMode(DnsMode.Secure)
            val blocked = connectResult(container.vpnController, profile.id)
            assertTrue("Secure was not blocked", blocked is VpnConnectionState.Error)
            assertTrue((blocked as VpnConnectionState.Error).message.contains("Strict Private DNS"))
            assertFalse(hasVpnNetwork(context))

            VpnTestHooks.reset()
            container.uiSettingsStore.setDnsMode(DnsMode.Automatic)
            val automatic = connectResult(container.vpnController, profile.id)
            assertTrue(
                "Automatic did not narrow to Android DNS: $automatic",
                automatic is VpnConnectionState.Connected,
            )
            stop(container.vpnController, context)

            VpnTestHooks.reset()
            container.uiSettingsStore.setDnsMode(DnsMode.Android)
            val strictWorking = startWithOneCleanRetry(
                container.vpnController,
                profile.id,
                context,
                30_000,
            )
            assertTrue("Working strict Private DNS failed: $strictWorking", strictWorking is VpnConnectionState.Connected)

            shell("settings put global private_dns_specifier strict-does-not-exist.invalid")
            awaitPrivateDns(
                context,
                PrivateDnsMode.Strict,
                "strict-does-not-exist.invalid",
            )
            VpnTestHooks.reset()
            val strictBroken = withTimeout(30_000) {
                container.vpnController.state.first { state -> state is VpnConnectionState.Error }
            } as VpnConnectionState.Error
            assertTrue(
                "Active VPN did not fail closed after strict Private DNS broke: $strictBroken",
                strictBroken.message.contains("Strict Private DNS") ||
                    strictBroken.message.contains("DNS через VPN") ||
                    strictBroken.code == "DNS-105",
            )
            awaitCompletelyIdle(context)
            assertFalse(hasVpnNetwork(context))
        } finally {
            stopIfNeeded(container.vpnController, context)
            shell("settings put global private_dns_mode off")
            shell("settings delete global private_dns_specifier")
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
            VpnTestHooks.reset()
        }
    }

    @Test
    fun privateDnsOffAndAutomaticAllowManagedSecure() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@runBlocking
        if (!isEmulator()) return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("svc data enable")
        shell("su 0 svc wifi disable")
        awaitUnderlyingTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.uiSettingsStore.setDnsMode(DnsMode.Secure)
        val profile = createProfile(container, "Private DNS off automatic", TWO_SERVER_DIRECT_CONFIG)
        try {
            shell("settings put global private_dns_mode off")
            shell("settings delete global private_dns_specifier")
            awaitPrivateDns(context, PrivateDnsMode.Off)
            VpnTestHooks.reset()
            VpnTestHooks.succeedNextHealthCheck()
            val off = startWithOneCleanRetry(container.vpnController, profile.id, context, 30_000)
            assertTrue("Managed DNS failed with Private DNS off: $off", off is VpnConnectionState.Connected)
            stop(container.vpnController, context)

            shell("settings put global private_dns_mode opportunistic")
            awaitPrivateDns(context, PrivateDnsMode.Automatic)
            VpnTestHooks.reset()
            VpnTestHooks.succeedNextHealthCheck()
            val automatic = startWithOneCleanRetry(container.vpnController, profile.id, context, 30_000)
            assertTrue(
                "Managed DNS failed with automatic Private DNS: $automatic",
                automatic is VpnConnectionState.Connected,
            )
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            shell("settings put global private_dns_mode off")
            shell("settings delete global private_dns_specifier")
            shell("svc data enable")
            shell("su 0 svc wifi enable")
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun activeVpnRestartsExactlyOnceForWifiCellularAndBack() = runBlocking {
        if (!isEmulator()) return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        shell("svc data enable")
        shell("su 0 svc wifi enable")
        awaitUnderlyingTransport(context, NetworkCapabilities.TRANSPORT_WIFI)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        val profile = createProfile(container, "Network transition", TWO_SERVER_DIRECT_CONFIG)
        try {
            connect(container.vpnController, profile.id)
            val initial = container.vpnController.state.value as VpnConnectionState.Connected
            val createdBefore = VpnRuntimeMetrics.libboxCreationCount()

            VpnTestHooks.succeedNextHealthCheck()
            shell("su 0 svc wifi disable")
            awaitUnderlyingTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR)
            val onCellular = awaitNewConnection(container.vpnController, initial.connectedAtEpochMillis)
            assertEquals(createdBefore + 1, VpnRuntimeMetrics.libboxCreationCount())

            VpnTestHooks.succeedNextHealthCheck()
            shell("su 0 svc wifi enable")
            awaitUnderlyingTransport(context, NetworkCapabilities.TRANSPORT_WIFI)
            awaitNewConnection(container.vpnController, onCellular.connectedAtEpochMillis)
            assertEquals(createdBefore + 2, VpnRuntimeMetrics.libboxCreationCount())
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeNetworkCallbacks)
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeTunDescriptors)
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            shell("svc data enable")
            shell("su 0 svc wifi enable")
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun networkAutomationPausesOnWifiAndResumesOnCellular() = runBlocking {
        if (!isEmulator()) return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        shell("settings put global private_dns_mode off")
        shell("svc data enable")
        shell("su 0 svc wifi enable")
        awaitUnderlyingTransport(context, NetworkCapabilities.TRANSPORT_WIFI)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.uiSettingsStore.setUseVpnOnNetwork(NetworkTransportSetting.Wifi, false)
        container.uiSettingsStore.setUseVpnOnNetwork(NetworkTransportSetting.Cellular, true)
        container.uiSettingsStore.setNetworkAutomationEnabled(true)
        val profile = createProfile(container, "Network automation", TWO_SERVER_DIRECT_CONFIG)
        try {
            container.vpnController.start(profile.id)
            val pausedOnWifi = withTimeout(15_000) {
                container.vpnController.state.first { it is VpnConnectionState.Paused }
            }
            assertTrue(pausedOnWifi is VpnConnectionState.Paused)
            withTimeout(5_000) {
                while (hasVpnNetwork(context)) delay(50)
            }
            assertEquals(0, VpnRuntimeMetrics.snapshot().activeTunDescriptors)
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeNetworkCallbacks)

            VpnTestHooks.succeedNextHealthCheck()
            shell("su 0 svc wifi disable")
            awaitUnderlyingTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR)
            val connectedOnCellular = withTimeout(30_000) {
                container.vpnController.state.first { it is VpnConnectionState.Connected }
            }
            assertTrue(connectedOnCellular is VpnConnectionState.Connected)
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeTunDescriptors)

            shell("su 0 svc wifi enable")
            awaitUnderlyingTransport(context, NetworkCapabilities.TRANSPORT_WIFI)
            withTimeout(15_000) {
                container.vpnController.state.first { it is VpnConnectionState.Paused }
            }
            withTimeout(5_000) {
                while (hasVpnNetwork(context)) delay(50)
            }
            assertEquals(0, VpnRuntimeMetrics.snapshot().activeTunDescriptors)

            VpnTestHooks.succeedNextHealthCheck()
            container.uiSettingsStore.setNetworkAutomationEnabled(false)
            withTimeout(30_000) {
                container.vpnController.state.first { it is VpnConnectionState.Connected }
            }
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeTunDescriptors)
        } finally {
            VpnTestHooks.reset()
            container.uiSettingsStore.setNetworkAutomationEnabled(false)
            container.uiSettingsStore.setUseVpnOnNetwork(NetworkTransportSetting.Wifi, true)
            container.uiSettingsStore.setUseVpnOnNetwork(NetworkTransportSetting.Cellular, true)
            stopIfNeeded(container.vpnController, context)
            shell("svc data enable")
            shell("su 0 svc wifi enable")
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun clearDnsCacheDeletesLkgAndPerformsOneControlledCoreRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        val profile = createProfile(container, "DNS restart", TWO_SERVER_DIRECT_CONFIG)
        try {
            connect(container.vpnController, profile.id)
            container.bootstrapCache.recordSuccess(
                profileId = profile.id,
                hostname = "vpn.example",
                addresses = listOf(InetAddress.getByName("203.0.113.10")),
            )
            assertNotNull(container.bootstrapCache.find(profile.id, "vpn.example"))
            val createdBefore = VpnRuntimeMetrics.libboxCreationCount()
            val connectedBefore = container.vpnController.state.value as VpnConnectionState.Connected

            VpnTestHooks.succeedNextHealthCheck()
            container.vpnController.clearDnsCache()
            withTimeout(10_000) {
                container.vpnController.state.first { it is VpnConnectionState.Starting }
            }
            val connectedAfter = withTimeout(20_000) {
                container.vpnController.state.first {
                    it is VpnConnectionState.Connected &&
                        it.connectedAtEpochMillis != connectedBefore.connectedAtEpochMillis
                }
            }

            assertTrue(connectedAfter is VpnConnectionState.Connected)
            assertEquals(createdBefore + 1, VpnRuntimeMetrics.libboxCreationCount())
            assertEquals(null, container.bootstrapCache.find(profile.id, "vpn.example"))
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeNetworkCallbacks)
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeTunDescriptors)
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun selectingAnotherProfileSwitchesTheConnectedVpnSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        val first = createProfile(container, "Profile switch A", TWO_SERVER_DIRECT_CONFIG)
        val second = createProfile(container, "Profile switch B", TWO_SERVER_DIRECT_CONFIG)
        try {
            connect(container.vpnController, first.id)
            val connectedBefore = container.vpnController.state.value as VpnConnectionState.Connected
            val createdBefore = VpnRuntimeMetrics.libboxCreationCount()

            assertFalse(container.vpnController.switchProfileIfConnected(first.id))
            assertEquals(createdBefore, VpnRuntimeMetrics.libboxCreationCount())

            VpnTestHooks.succeedNextHealthCheck()
            assertTrue(container.vpnController.switchProfileIfConnected(second.id))
            val connectedAfter = withTimeout(20_000) {
                container.vpnController.state.first { state ->
                    state is VpnConnectionState.Connected &&
                        state.profileId == second.id &&
                        state.connectedAtEpochMillis != connectedBefore.connectedAtEpochMillis
                }
            } as VpnConnectionState.Connected

            assertEquals(second.id, connectedAfter.profileId)
            assertEquals(createdBefore + 1, VpnRuntimeMetrics.libboxCreationCount())
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeTunDescriptors)
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeLibboxInstances)
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(first.id)
            container.profileStore.delete(second.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun realAndroidRevokeClosesOriginalVpnExactlyOnce() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testPackage = instrumentation.context.packageName
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        allowVpn(testPackage)
        val profile = createProfile(container, "Revoke lifecycle", TWO_SERVER_DIRECT_CONFIG)
        try {
            container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
            connect(container.vpnController, profile.id)
            awaitActiveResources()

            val revokeStart = controlCall(context, ControlTrafficProvider.METHOD_REVOKE_START)
            assertEquals(
                revokeStart.getString(ControlTrafficProvider.RESULT_ERROR),
                true,
                revokeStart.getBoolean(ControlTrafficProvider.RESULT_SUCCESS),
            )
            val revoked = withTimeout(20_000) {
                container.vpnController.state.first { state ->
                    state is VpnConnectionState.Error && state.message.contains("отозвано")
                }
            }
            assertTrue(revoked is VpnConnectionState.Error)
            withTimeout(20_000) {
                while (!VpnRuntimeMetrics.snapshot().isIdle) delay(25)
            }

            controlCall(context, ControlTrafficProvider.METHOD_REVOKE_STOP)
            withTimeout(10_000) {
                while (hasVpnNetwork(context)) delay(50)
            }
        } finally {
            runCatching { controlCall(context, ControlTrafficProvider.METHOD_REVOKE_STOP) }
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
            denyVpn(testPackage)
        }
    }

    @Test
    fun alwaysOnAndLockdownAreDetectedExplainedAndRejectedBeforeTun() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        allowVpn(packageName)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        val profile = createProfile(container, "Always-on policy", TWO_SERVER_DIRECT_CONFIG)
        try {
            VpnTestHooks.reportNextVpnSystemPolicy(alwaysOn = true, lockdown = true)
            val terminal = connectResult(container.vpnController, profile.id)
            assertTrue("Unsupported Lockdown was not rejected: $terminal", terminal is VpnConnectionState.Error)
            assertTrue((terminal as VpnConnectionState.Error).message.contains("Lockdown"))
            val policy = container.vpnController.diagnostics.value.vpnPolicy
            assertEquals(true, policy?.statusAvailable)
            assertEquals(true, policy?.alwaysOn)
            assertEquals(true, policy?.lockdown)
            assertFalse(hasVpnNetwork(context))
            assertEquals(VpnRuntimeSnapshot.Idle, VpnRuntimeMetrics.snapshot())
        } finally {
            VpnTestHooks.reset()
            stopIfNeeded(container.vpnController, context)
            container.profileStore.delete(profile.id)
            denyVpn(packageName)
        }
    }

    @Test
    fun oneHundredConnectStopCyclesDoNotLeakTunResources() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        shell("appops set $packageName ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        container.profileStore.initialize()
        val profile = container.profileStore.create(
            "Lifecycle VPN",
            TWO_SERVER_DIRECT_CONFIG,
            ProfileSource.RawJson,
        )
        try {
            container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
            val initialLibboxCreations = VpnRuntimeMetrics.libboxCreationCount()
            val initialCallbackRegistrations = VpnRuntimeMetrics.callbackRegistrationCount()
            repeat(5) {
                connect(container.vpnController, profile.id)
                awaitActiveResources()
                stop(container.vpnController, context)
                assertEquals(VpnRuntimeSnapshot.Idle, VpnRuntimeMetrics.snapshot())
            }
            delay(200)
            val baselineFds = File("/proc/self/fd").list().orEmpty().size

            repeat(45) {
                connect(container.vpnController, profile.id)
                awaitActiveResources()
                stop(container.vpnController, context)
                assertEquals(VpnRuntimeSnapshot.Idle, VpnRuntimeMetrics.snapshot())
            }
            delay(200)
            val midpointThreads = currentNonBinderThreadNames()
            val midpointTasks = midpointThreads.size
            repeat(50) {
                connect(container.vpnController, profile.id)
                awaitActiveResources()
                stop(container.vpnController, context)
                assertEquals(VpnRuntimeSnapshot.Idle, VpnRuntimeMetrics.snapshot())
            }
            delay(300)

            val finalFds = File("/proc/self/fd").list().orEmpty().size
            val finalThreads = currentNonBinderThreadNames()
            val finalTasks = finalThreads.size
            assertTrue("PFD grew from $baselineFds to $finalFds", finalFds <= baselineFds + 2)
            assertTrue(
                "threads kept growing from $midpointTasks to $finalTasks; " +
                    "midpoint=$midpointThreads; final=$finalThreads",
                finalTasks <= midpointTasks + 2,
            )
            assertEquals(VpnRuntimeSnapshot.Idle, VpnRuntimeMetrics.snapshot())
            assertTrue(
                "Not every cycle created a measured libbox instance",
                VpnRuntimeMetrics.libboxCreationCount() - initialLibboxCreations >= 100,
            )
            assertTrue(
                "Default-network callbacks were not exercised",
                VpnRuntimeMetrics.callbackRegistrationCount() - initialCallbackRegistrations >= 100,
            )
        } finally {
            if (container.vpnController.state.value !is VpnConnectionState.Stopped) {
                stop(container.vpnController, context)
            }
            container.profileStore.delete(profile.id)
            shell("appops set $packageName ACTIVATE_VPN default")
        }
    }

    /**
     * Binder grows its process pool lazily under instrumentation, independently of the VPN lifecycle.
     * The exact VPN-owned resources are asserted through [VpnRuntimeMetrics] after every stop; this
     * secondary process-level guard therefore excludes Binder workers and still rejects sustained
     * growth of every other thread class.
     */
    private fun currentNonBinderThreadNames(): List<String> = File("/proc/self/task")
        .listFiles()
        .orEmpty()
        .mapNotNull { task ->
            runCatching { task.resolve("comm").readText().trim() }.getOrNull()
        }
        .filterNot { it.startsWith("Binder:") }
        .sorted()

    @Test
    fun invalidRuntimeProfilesFailBeforeAnyTunIsEstablished() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        shell("appops set $packageName ACTIVATE_VPN allow")
        container.profileStore.initialize()
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        val profile = container.profileStore.create(
            "Invalid runtime",
            TWO_SERVER_DIRECT_CONFIG,
            ProfileSource.RawJson,
        )
        val profileFile = File(context.filesDir, "profiles/${profile.id}.json")
        val cases = listOf(
            TWO_SERVER_DIRECT_CONFIG.replace(
                Regex(""""inbounds":\[[^\n]+]"""),
                "\"inbounds\":[]",
            ) to "ровно один TUN",
            TWO_SERVER_DIRECT_CONFIG.replace(
                "\"inbounds\":[",
                "\"inbounds\":[{\"type\":\"tun\",\"tag\":\"tun-second\",\"address\":[\"10.0.0.1/30\",\"fd00::1/126\"],\"auto_route\":true},",
            ) to "ровно один TUN",
            TWO_SERVER_DIRECT_CONFIG.replace(
                "\"auto_route\":true",
                "\"auto_route\":true,\"route_address\":[\"10.0.0.0/8\",\"::/0\"]",
            ) to "полные IPv4",
            TWO_SERVER_DIRECT_CONFIG.replace(
                "\"auto_route\":true",
                "\"auto_route\":true,\"include_package\":[\"a\"],\"exclude_package\":[\"b\"]",
            ) to "одновременно",
            TWO_SERVER_DIRECT_CONFIG.replace(
                "\"type\":\"direct\",\"tag\":\"server-a\"",
                "\"type\":\"direct\",\"tag\":\"server-a\",\"routing_mark\":7",
            ) to "routing_mark",
        )
        try {
            for ((invalidJson, expectedError) in cases) {
                profileFile.writeText(invalidJson)
                container.vpnController.start(profile.id)
                val error = withTimeout(15_000) {
                    container.vpnController.state.first { state ->
                        state is VpnConnectionState.Error && expectedError in state.message
                    }
                }
                assertTrue(error is VpnConnectionState.Error)
                withTimeout(5_000) {
                    while (hasVpnNetwork(context)) delay(50)
                }
            }
        } finally {
            if (container.vpnController.state.value is VpnConnectionState.Connected) {
                stop(container.vpnController, context)
            }
            container.profileStore.delete(profile.id)
            shell("appops set $packageName ACTIVATE_VPN default")
        }
    }

    private suspend fun waitForVpnNetwork(context: Context): Network = withTimeout(10_000) {
        while (true) {
            vpnNetwork(context)?.let { return@withTimeout it }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE")
        error("VPN network not found")
    }

    private suspend fun connect(controller: VpnController, profileId: String) {
        val state = connectResult(controller, profileId)
        assertTrue("VPN failed: $state", state is VpnConnectionState.Connected)
    }

    private suspend fun connectResult(
        controller: VpnController,
        profileId: String,
    ): VpnConnectionState {
        VpnTestHooks.succeedNextHealthCheck()
        return startAndAwaitTerminal(controller, profileId)
    }

    private suspend fun startAndAwaitTerminal(
        controller: VpnController,
        profileId: String,
        timeoutMillis: Long = 20_000,
    ): VpnConnectionState {
        val before = controller.state.value
        controller.start(profileId)
        val progressed = withTimeoutOrNull(timeoutMillis) { controller.state.first { it != before } }
            ?: error(
                "VPN did not leave $before within ${timeoutMillis}ms; " +
                    "current=${controller.state.value}, " +
                    "attempt=${controller.diagnostics.value.connectionAttempt}",
            )
        if (progressed is VpnConnectionState.Connected || progressed is VpnConnectionState.Error) {
            return progressed
        }
        return withTimeoutOrNull(timeoutMillis) {
            controller.state.first {
                it is VpnConnectionState.Connected || it is VpnConnectionState.Error
            }
        } ?: error(
            "VPN did not reach a terminal state within ${timeoutMillis}ms; " +
                "current=${controller.state.value}, " +
                "attempt=${controller.diagnostics.value.connectionAttempt}",
        )
    }

    private suspend fun startWithOneCleanRetry(
        controller: VpnController,
        profileId: String,
        context: Context,
        timeoutMillis: Long,
    ): VpnConnectionState {
        val first = startAndAwaitTerminal(controller, profileId, timeoutMillis)
        if (first is VpnConnectionState.Connected) return first
        stopIfNeeded(controller, context)
        return startAndAwaitTerminal(controller, profileId, timeoutMillis)
    }

    private suspend fun awaitNewConnection(
        controller: VpnController,
        previousConnectedAt: Long,
    ): VpnConnectionState.Connected = withTimeout(25_000) {
        controller.state.first { state ->
            state is VpnConnectionState.Connected && state.connectedAtEpochMillis != previousConnectedAt
        } as VpnConnectionState.Connected
    }

    private suspend fun awaitPrivateDns(
        context: Context,
        expectedMode: PrivateDnsMode,
        expectedServerName: String? = null,
        expectedActive: Boolean? = null,
    ): UnderlyingNetworkState {
        val monitor = DefaultNetworkMonitor(context)
        return try {
            monitor.start()
            withTimeout(25_000) {
                while (true) {
                    val state = monitor.current
                    if (state.network != null &&
                        state.privateDnsMode == expectedMode &&
                        (expectedServerName == null || state.privateDnsServerName == expectedServerName) &&
                        (expectedActive == null || state.privateDnsActive == expectedActive)
                    ) {
                        return@withTimeout state
                    }
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Private DNS state was not observed")
            }
        } finally {
            monitor.close()
        }
    }

    private suspend fun awaitStableUnderlyingNetwork(context: Context): UnderlyingNetworkState {
        val monitor = DefaultNetworkMonitor(context)
        return try {
            monitor.start()
            monitor.awaitStableUnderlying(timeoutMillis = 30_000)
        } finally {
            monitor.close()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun awaitUnderlyingTransport(context: Context, transport: Int): Network =
        withTimeout(30_000) {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            while (true) {
                connectivity.activeNetwork?.let { network ->
                    val matches = connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                        capabilities.hasTransport(transport) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    } == true
                    if (matches) return@withTimeout network
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            error("Underlying transport was not observed")
        }

    private suspend fun stop(controller: VpnController, context: Context) {
        controller.stop()
        withTimeout(20_000) { controller.state.first { it is VpnConnectionState.Stopped } }
        withTimeout(10_000) {
            while (hasVpnNetwork(context)) delay(50)
        }
        delay(50)
    }

    private suspend fun stopIfNeeded(controller: VpnController, context: Context) {
        controller.setHomeVisible(false)
        controller.setDiagnosticsVisible(false)
        if (controller.state.value !is VpnConnectionState.Stopped) {
            stop(controller, context)
        }
        val stopped = withTimeoutOrNull(10_000) {
            while (!VpnRuntimeMetrics.snapshot().isIdle || hasVpnNetwork(context)) delay(50)
            true
        }
        assertEquals("Cleanup failed: ${VpnRuntimeMetrics.snapshot()}, vpn=${hasVpnNetwork(context)}", true, stopped)
    }

    private suspend fun createProfile(
        container: io.github.zapretkvn.android.AppContainer,
        name: String,
        json: String,
    ): ProfileMetadata {
        container.profileStore.initialize()
        return container.profileStore.create(name, json, ProfileSource.RawJson)
    }

    private suspend fun setRoutingPolicy(
        preset: RoutingPreset,
        rules: List<ManagedRoutingRule> = emptyList(),
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as ZapretApplication).container
        container.routingPolicyStore.set(GlobalRoutingPolicy(preset, rules))
    }

    private suspend fun awaitActiveResources() {
        var last = VpnRuntimeMetrics.snapshot()
        var active = false
        withTimeoutOrNull(10_000) {
            while (!active) {
                val current = VpnRuntimeMetrics.snapshot()
                last = current
                active =
                    current.activeSessions == 1 &&
                    current.activeLibboxInstances == 1 &&
                    current.activePlatformAdapters == 1 &&
                    current.activeTunDescriptors == 1 &&
                    current.activeNetworkCallbacks in 0..1
                if (!active) delay(25)
            }
        }
        assertTrue("Runtime did not become active: $last", active)
    }

    private suspend fun awaitCompletelyIdle(context: Context) {
        withTimeout(20_000) {
            while (!VpnRuntimeMetrics.snapshot().isIdle || hasVpnNetwork(context)) delay(25)
        }
    }

    private fun allowVpn(packageName: String) {
        shell("appops set $packageName ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    private fun denyVpn(packageName: String) {
        shell("appops set $packageName ACTIVATE_VPN default")
    }

    private fun controlCall(context: Context, method: String, extras: Bundle? = null) = requireNotNull(
        context.contentResolver.call(
            Uri.parse("content://${ControlTrafficProvider.AUTHORITY}"),
            method,
            null,
            extras,
        ),
    )

    private suspend fun awaitSuccessfulControlCall(
        context: Context,
        method: String,
        extras: Bundle? = null,
        label: String = method,
    ): Bundle {
        var success: Bundle? = null
        var lastError: String? = null
        withTimeoutOrNull(10_000) {
            while (success == null) {
                val result = controlCall(context, method, extras)
                if (result.getBoolean(ControlTrafficProvider.RESULT_SUCCESS)) {
                    success = result
                } else {
                    lastError = result.getString(ControlTrafficProvider.RESULT_ERROR)
                    delay(100)
                }
            }
        }
        return requireNotNull(success) { "$label failed within 10 seconds: $lastError" }
    }

    private fun echoArguments(
        address: String,
        port: Int,
        size: Int,
        value: Int,
        repeat: Int = 1,
    ) = Bundle().apply {
        putString(ControlTrafficProvider.EXTRA_ADDRESS, address)
        putInt(ControlTrafficProvider.EXTRA_PORT, port)
        putInt(ControlTrafficProvider.EXTRA_SIZE, size)
        putInt(ControlTrafficProvider.EXTRA_VALUE, value)
        putInt(ControlTrafficProvider.EXTRA_REPEAT, repeat)
    }

    private fun gateRules(
        preset: RoutingPreset,
        ruDomain: String,
        nonRuDomain: String,
    ): List<ManagedRoutingRule> = when (preset) {
        RoutingPreset.OnlySelectedSites -> listOf(
            ManagedRoutingRule(
                RoutingMatchType.Domain,
                listOf(ruDomain, nonRuDomain),
                RoutingRuleAction.Proxy,
            ),
            ManagedRoutingRule(
                RoutingMatchType.IpCidr,
                listOf("$RU_IPV4/32", "$RU_IPV6/128", "$NON_RU_IPV4/32", "$NON_RU_IPV6/128"),
                RoutingRuleAction.Proxy,
            ),
        )
        RoutingPreset.Custom -> listOf(
            ManagedRoutingRule(RoutingMatchType.Domain, listOf(ruDomain), RoutingRuleAction.Proxy),
            ManagedRoutingRule(RoutingMatchType.Domain, listOf(nonRuDomain), RoutingRuleAction.Block),
            ManagedRoutingRule(RoutingMatchType.IpCidr, listOf("$RU_IPV4/32"), RoutingRuleAction.Direct),
            ManagedRoutingRule(RoutingMatchType.IpCidr, listOf("$RU_IPV6/128"), RoutingRuleAction.Proxy),
            ManagedRoutingRule(RoutingMatchType.IpCidr, listOf("$NON_RU_IPV4/32"), RoutingRuleAction.Block),
            ManagedRoutingRule(RoutingMatchType.IpCidr, listOf("$NON_RU_IPV6/128"), RoutingRuleAction.Direct),
        )
        else -> emptyList()
    }

    private fun gateProbes(
        preset: RoutingPreset,
        ruDomain: String,
        nonRuDomain: String,
    ): List<GateProbe> {
        fun geo(ru: Boolean): GatePath = when (preset) {
            RoutingPreset.AllThroughVpn, RoutingPreset.BypassLan -> GatePath.Proxy
            RoutingPreset.OnlySelectedSites -> GatePath.Proxy
            RoutingPreset.RussiaDirect -> if (ru) GatePath.Direct else GatePath.Proxy
            RoutingPreset.RussiaVpn -> if (ru) GatePath.Proxy else GatePath.Direct
            RoutingPreset.Custom -> error("Custom paths are explicit")
        }
        if (preset == RoutingPreset.Custom) {
            return listOf(
                GateProbe("RU domain", ruDomain, GatePath.Proxy),
                GateProbe("non-RU domain", nonRuDomain, GatePath.Reject),
                GateProbe("RU IPv4", RU_IPV4, GatePath.Direct),
                GateProbe("RU IPv6", RU_IPV6, GatePath.Proxy),
                GateProbe("non-RU IPv4", NON_RU_IPV4, GatePath.Reject),
                GateProbe("non-RU IPv6", NON_RU_IPV6, GatePath.Direct),
                GateProbe("custom final", "outside.gate.test", GatePath.Proxy),
            )
        }
        val privatePath = when (preset) {
            RoutingPreset.AllThroughVpn -> GatePath.Proxy
            RoutingPreset.BypassLan,
            RoutingPreset.RussiaDirect,
            RoutingPreset.RussiaVpn,
            RoutingPreset.OnlySelectedSites,
            -> GatePath.Direct
            RoutingPreset.Custom -> error("handled above")
        }
        return listOf(
            GateProbe("RU IPv4", RU_IPV4, geo(true)),
            GateProbe("RU IPv6", RU_IPV6, geo(true)),
            GateProbe("non-RU IPv4", NON_RU_IPV4, geo(false)),
            GateProbe("non-RU IPv6", NON_RU_IPV6, geo(false)),
            GateProbe("RU domain", ruDomain, geo(true)),
            GateProbe("non-RU domain", nonRuDomain, geo(false)),
            GateProbe("private LAN", PRIVATE_IPV4, privatePath),
        )
    }

    private suspend fun assertGatePath(
        echo: GateEchoServer,
        socks: GateSocksServer,
        probe: GateProbe,
    ) {
        val before = socks.requestCount
        var result = GateTrafficClient.tcpEcho(
            probe.target,
            echo.port,
            probe.payloadSize,
            probe.label.hashCode(),
        )
        if (probe.path == GatePath.Reject) {
            assertFalse("${probe.label} unexpectedly succeeded", result.success)
            assertEquals("${probe.label} unexpectedly reached proxy", before, socks.requestCount)
            return
        }
        if (!result.success) {
            delay(100)
            result = GateTrafficClient.tcpEcho(
                probe.target,
                echo.port,
                probe.payloadSize,
                probe.label.hashCode(),
            )
        }
        assertTrue(
            "${probe.label} failed on ${probe.path}: ${result.error}; " +
                "proxy_requests=$before→${socks.requestCount}; proxy_error=${socks.lastError}",
            result.success,
        )
        if (probe.path == GatePath.Proxy) {
            withTimeout(3_000) {
                while (socks.requestCount <= before) delay(10)
            }
        } else {
            delay(100)
            assertEquals("${probe.label} unexpectedly reached proxy", before, socks.requestCount)
        }
    }

    private suspend fun awaitSocksQuiescence(socks: GateSocksServer) {
        var previous = socks.requestCount
        var stableSince = SystemClock.elapsedRealtime()
        withTimeout(5_000) {
            while (SystemClock.elapsedRealtime() - stableSince < 1_000) {
                delay(50)
                val current = socks.requestCount
                if (current != previous) {
                    previous = current
                    stableSince = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private suspend fun waitForVpnInterface(context: Context): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        var result: String? = null
        withTimeoutOrNull(10_000) {
            while (result == null) {
                vpnNetwork(context)?.let { network ->
                    result = connectivity.getLinkProperties(network)?.interfaceName
                }
                if (result == null) delay(50)
            }
        }
        return requireNotNull(result) { "VPN interface not found; state=${vpnNetworks(context)}" }
    }

    private suspend fun awaitTrafficStatus(controller: VpnController) {
        controller.setHomeVisible(true)
        withTimeout(3_000) {
            while (VpnRuntimeMetrics.trafficUpdateCount() == 0) delay(25)
        }
    }

    private suspend fun waitForTrafficGrowth(
        baseline: Long,
        minimumGrowth: Long,
    ): Long {
        var current = baseline
        var complete = false
        withTimeoutOrNull(10_000) {
            while (!complete) {
                current = VpnRuntimeMetrics.trafficTotal()
                complete = current - baseline >= minimumGrowth
                if (!complete) delay(25)
            }
        }
        check(complete) {
            "No libbox/TUN traffic growth: baseline=$baseline current=$current, " +
                "statusUpdates=${VpnRuntimeMetrics.trafficUpdateCount()}"
        }
        return current
    }

    private suspend fun awaitTrafficQuiescence(requiredStableUpdates: Int = 2): Long {
        var previousTotal = VpnRuntimeMetrics.trafficTotal()
        var previousUpdate = VpnRuntimeMetrics.trafficUpdateCount()
        var stableUpdates = 0
        withTimeout(6_000) {
            while (stableUpdates < requiredStableUpdates) {
                while (VpnRuntimeMetrics.trafficUpdateCount() == previousUpdate) delay(25)
                previousUpdate = VpnRuntimeMetrics.trafficUpdateCount()
                val currentTotal = VpnRuntimeMetrics.trafficTotal()
                stableUpdates = if (currentTotal == previousTotal) stableUpdates + 1 else 0
                previousTotal = currentTotal
            }
        }
        return previousTotal
    }

    private fun vpnNetworks(context: Context): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.joinToString { network ->
            "$network:${connectivity.getLinkProperties(network)?.interfaceName}:" +
                "${connectivity.getNetworkCapabilities(network)}"
        }
    }

    private fun freeUdpPort(): Int = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use {
        it.localPort
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.startsWith("sdk_gphone") ||
            Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "goldfish"

    private fun hasVpnNetwork(context: Context): Boolean = vpnNetwork(context) != null

    @Suppress("DEPRECATION")
    private fun vpnNetwork(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    input.readBytes().toString(Charsets.UTF_8).trim()
                }
            }

    private companion object {
        const val DOCUMENTATION_IPV4 = "192.0.2.1"
        const val DOCUMENTATION_IPV6 = "2001:db8::1"
        const val RU_IPV4 = "5.255.255.5"
        const val RU_IPV6 = "2a02:6b8::feed:0ff"
        const val NON_RU_IPV4 = "1.1.1.1"
        const val NON_RU_IPV6 = "2606:4700:4700::1111"
        const val PRIVATE_IPV4 = "192.168.77.7"
        const val PERF_FLOW_COUNT = 40
        const val MIN_SELECTED_TUN_BYTES = 512L
        const val MIN_PROTOCOL_TUN_BYTES = 32 * 1024L
        const val MAX_IDLE_TUN_GROWTH = 512L
        val TWO_SERVER_DIRECT_CONFIG = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {"type":"direct","tag":"server-a"},
                {"type":"direct","tag":"server-b"},
                {"type":"selector","tag":"zapret-proxy","outbounds":["server-a","server-b"],"default":"server-a","interrupt_exist_connections":true},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent()

        val SINGLE_SERVER_DIRECT_CONFIG = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {"type":"direct","tag":"server-only"},
                {"type":"selector","tag":"zapret-proxy","outbounds":["server-only"],"default":"server-only"},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent()

        fun deadManagedHysteria2Config(serverAddress: String, serverPort: Int): String = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {
                  "type":"hysteria2","tag":"dead-proxy","server":"$serverAddress","server_port":$serverPort,
                  "up_mbps":10,"down_mbps":10,"password":"dead-proxy",
                  "tls":{"enabled":true,"server_name":"dead.invalid","insecure":true}
                },
                {"type":"selector","tag":"zapret-proxy","outbounds":["dead-proxy"],"default":"dead-proxy"},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent()
    }

    private enum class GatePath { Proxy, Direct, Reject }

    private data class GateProbe(
        val label: String,
        val target: String,
        val path: GatePath,
        val payloadSize: Int = 1_024,
    )
}
