package io.github.zapretkvn.android.vpn

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HysteriaTransitionInstrumentedTest {
    @Test
    fun reducerAndCoordinatorFenceOneReplacementOnDevice() {
        var now = 1_000L
        val reducer = HysteriaStateReducer { ++now }
        val coordinator = HysteriaTransitionCoordinator({ now }, cooldownMillis = 60_000)
        val valid = HysteriaCapabilityClassifier.classify("hy2://auth@example.test:443/")
        val targets = listOf(
            HysteriaFallbackTarget("old", valid),
            HysteriaFallbackTarget("maintenance", valid, maintenance = true),
            HysteriaFallbackTarget("replacement", valid),
            HysteriaFallbackTarget("second", valid),
        )

        reducer.begin(8, "old", targets.map { it.id }.toSet())
        reducer.advance(8, HysteriaRuntimeState.READY)
        assertEquals(
            "replacement",
            coordinator.chooseReplacement(
                "old",
                HysteriaFailureCode.TARGET_NETWORK_TIMEOUT,
                targets,
            )?.id,
        )
        reducer.fail(8, HysteriaFailureCode.TARGET_NETWORK_TIMEOUT, automaticSwitch = true)
        reducer.advance(8, HysteriaRuntimeState.PREPARING_REPLACEMENT)
        reducer.advance(8, HysteriaRuntimeState.REPLACEMENT_READY)
        coordinator.failReplacement()

        assertEquals(
            null,
            coordinator.chooseReplacement(
                "old",
                HysteriaFailureCode.TARGET_CONNECTION_REFUSED,
                targets,
            ),
        )
        assertFalse(reducer.advance(7, HysteriaRuntimeState.FAILED))
        assertEquals(HysteriaRuntimeState.REPLACEMENT_READY, reducer.session.state)
    }

    @Test
    fun productionTimeoutSwitchesOnceAndKeepsHttpsWorking() = runBlocking {
        val fixture = productionPair()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val testPackage = instrumentation.context.packageName
        val profileJson = ManagedProfileFactory.subscription(fixture)
        val tags = ManagedProfileFactory.stableTags(fixture)

        prepareVpn(context, container, testPackage)
        val profile = container.profileStore.create(
            "Hysteria transition success",
            profileJson,
            ProfileSource.Subscription,
        )
        try {
            VpnTestHooks.reportNextHysteriaFailure(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT)
            assertTrue(startAndAwaitTerminal(container.vpnController, profile.id) is VpnConnectionState.Connected)
            withTimeout(45_000) {
                while (selectedTag(container, profile.id) != tags[1]) delay(50)
            }
            assertTrue(container.vpnController.state.value is VpnConnectionState.Connected)
            assertTrue(controlCall(context, ControlTrafficProvider.METHOD_HTTPS).getBoolean(ControlTrafficProvider.RESULT_SUCCESS))
        } finally {
            cleanupVpn(context, container, profile.id)
        }
    }

    @Test
    fun failedProductionReplacementIsTerminalAndDoesNotPersistCandidate() = runBlocking {
        val fixture = productionPair()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val testPackage = instrumentation.context.packageName
        val profileJson = ManagedProfileFactory.subscription(fixture)
        val originalTag = ManagedProfileFactory.stableTags(fixture).first()

        prepareVpn(context, container, testPackage)
        val profile = container.profileStore.create(
            "Hysteria transition failure",
            profileJson,
            ProfileSource.Subscription,
        )
        try {
            VpnTestHooks.reportNextHysteriaFailure(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT)
            VpnTestHooks.failNextHysteriaReplacement()
            assertTrue(startAndAwaitTerminal(container.vpnController, profile.id) is VpnConnectionState.Connected)
            val failure = withTimeout(35_000) {
                container.vpnController.state.first { it is VpnConnectionState.Error }
            } as VpnConnectionState.Error
            assertEquals(HysteriaFailureCode.TRANSITION_DEADLINE_EXCEEDED.name, failure.code)
            assertEquals(originalTag, selectedTag(container, profile.id))
            awaitIdle(context)
        } finally {
            cleanupVpn(context, container, profile.id)
        }
    }

    private fun productionPair() = run {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true")
        val context = instrumentation.targetContext
        val snapshot = File(context.cacheDir, SNAPSHOT_PATH)
        assumeTrue("Private production snapshot was not staged", snapshot.isFile)
        val parsed = ImportParser.parse(
            snapshot.readText(Charsets.UTF_8),
            ProfileSource.Subscription,
            "Hysteria transition audit",
        )
        assumeTrue(parsed is ImportCandidate.Managed)
        val servers = (parsed as ImportCandidate.Managed).servers
        listOf("Hosal", "Polite Horologium").map { label ->
            servers.single { it.displayName.contains(label, ignoreCase = true) }
        }
    }

    private suspend fun prepareVpn(
        context: Context,
        container: io.github.zapretkvn.android.AppContainer,
        testPackage: String,
    ) {
        allowVpn(context.packageName)
        shell("settings put global private_dns_mode off")
        container.profileStore.initialize()
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(setOf(testPackage))
        container.uiSettingsStore.setDnsMode(DnsMode.Secure)
        VpnTestHooks.reset()
        stopAndAwaitIdle(container.vpnController, context)
    }

    private suspend fun cleanupVpn(
        context: Context,
        container: io.github.zapretkvn.android.AppContainer,
        profileId: String,
    ) {
        VpnTestHooks.reset()
        stopAndAwaitIdle(container.vpnController, context)
        runCatching { container.profileStore.delete(profileId) }
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.appSelectionStore.replaceAllowlist(emptySet())
        denyVpn(context.packageName)
    }

    private suspend fun startAndAwaitTerminal(
        controller: VpnController,
        profileId: String,
    ): VpnConnectionState {
        val before = controller.state.value
        controller.start(profileId)
        withTimeout(15_000) { controller.state.first { it != before } }
        return withTimeout(60_000) {
            controller.state.first {
                it is VpnConnectionState.Connected || it is VpnConnectionState.Error
            }
        }
    }

    private suspend fun selectedTag(
        container: io.github.zapretkvn.android.AppContainer,
        profileId: String,
    ): String? = ConfigAnalyzer.selectorGroups(container.profileStore.read(profileId).json)
        .firstOrNull { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }
        ?.default

    private suspend fun stopAndAwaitIdle(controller: VpnController, context: Context) {
        if (controller.state.value !is VpnConnectionState.Stopped) {
            controller.stop()
            withTimeout(25_000) { controller.state.first { it is VpnConnectionState.Stopped } }
        }
        awaitIdle(context)
    }

    private suspend fun awaitIdle(context: Context) {
        withTimeout(15_000) {
            while (!VpnRuntimeMetrics.snapshot().isIdle || hasVpnNetwork(context)) delay(50)
        }
    }

    private fun controlCall(context: Context, method: String): Bundle = checkNotNull(
        context.contentResolver.call(
            Uri.parse("content://${ControlTrafficProvider.AUTHORITY}"),
            method,
            null,
            null,
        ),
    )

    private fun hasVpnNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
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

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    input.readBytes().toString(Charsets.UTF_8).trim()
                }
            }

    private companion object {
        const val ENABLE_ARGUMENT = "productionHysteriaAudit"
        const val SNAPSHOT_PATH = "import/subscription.txt"
    }
}
