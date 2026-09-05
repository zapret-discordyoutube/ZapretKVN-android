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
import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.RuntimeConfigBuilder
import io.github.zapretkvn.android.config.RuntimeConfigOptions
import io.github.zapretkvn.android.config.RuntimeConfigResult
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ManagedServer
import io.github.zapretkvn.android.profiles.ProfileSource
import io.nekohasekai.libbox.Libbox
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in audit against a private production snapshot pushed into the target app cache.
 * The fixture and its credentials are never committed, copied to reports, or printed.
 */
@RunWith(AndroidJUnit4::class)
class ProductionHysteriaInstrumentedTest {
    @Test
    fun currentSnapshotImportsSavesAndReachesExactNativeRuntime() = runBlocking {
        val (context, candidate) = productionCandidate()
        val servers = candidate.servers
        val byLabel = REQUIRED_E2E_NAMES.associateWith { label -> servers.server(label) }

        assertEquals(42, servers.size)
        assertFalse(
            "Maintenance server must not be published",
            servers.any { it.displayName.contains("Hiponet", ignoreCase = true) },
        )

        val pinned = servers.filter { server ->
            server.outbound.tls()?.boolean("insecure") == true &&
                !server.outbound.string("certificate_sha256").isNullOrBlank()
        }
        val caSan = servers.filter { server ->
            server.outbound.tls()?.boolean("insecure") != true &&
                server.outbound.string("certificate_sha256").isNullOrBlank()
        }
        assertEquals(41, pinned.size)
        assertEquals(1, caSan.size)
        assertTrue(caSan.single().displayName.contains("4valon", ignoreCase = true))
        assertEquals(4, servers.count { it.outbound.obfsType() == "gecko" })
        assertTrue(servers.any { it.outbound.obfsType() == "salamander" })

        listOf("Hosal", "Polite Horologium", "Vdasasi").forEach { name ->
            assertTrue("$name must use a DNS endpoint", byLabel.getValue(name).outbound.string("server")!!.any(Char::isLetter))
        }
        listOf("Magnus", "Serva Pro").forEach { name ->
            assertFalse("$name must use an IP endpoint", byLabel.getValue(name).outbound.string("server")!!.any(Char::isLetter))
        }
        assertEquals("gecko", byLabel.getValue("Serva Pro").outbound.obfsType())
        assertEquals("gecko", servers.server("Vdokh").outbound.obfsType())

        val container = (context.applicationContext as ZapretApplication).container
        container.profileStore.initialize()
        for (server in servers) {
            val storedJson = ManagedProfileFactory.single(server)
            val profile = container.profileStore.create(
                "Production audit ${server.displayName}",
                storedJson,
                ProfileSource.Subscription,
            )
            try {
                val persisted = container.profileStore.read(profile.id).json
                assertTransportParity(server.outbound, persisted, "persisted")
                val runtime = RuntimeConfigBuilder.build(
                    persisted,
                    options = RuntimeConfigOptions(
                        dnsMode = DnsMode.Secure,
                        healthCheckPackageName = context.packageName,
                    ),
                ) as? RuntimeConfigResult.Ready
                assertNotNull("Runtime config rejected ${server.displayName}", runtime)
                Libbox.checkConfig(checkNotNull(runtime).json)
                assertTransportParity(server.outbound, runtime.json, "runtime")
            } finally {
                container.profileStore.delete(profile.id)
            }
        }
    }

    @Test
    fun mandatoryProductionProfilesCarryRealHttpsThroughAndroidTun() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val (context, candidate) = productionCandidate()
        val container = (context.applicationContext as ZapretApplication).container
        val testPackage = instrumentation.context.packageName
        val selected = REQUIRED_E2E_NAMES.associateWith { label -> candidate.servers.server(label) }
        val requestedLabel = InstrumentationRegistry.getArguments()
            .getString(PROFILE_ARGUMENT)
            .orEmpty()
            .trim()
        val testLabels = if (requestedLabel.isBlank()) {
            REQUIRED_E2E_NAMES
        } else {
            listOf(requestedLabel.also { check(it in selected) { "Unknown production audit label" } })
        }
        val failures = mutableListOf<String>()
        val createdIds = mutableListOf<String>()

        allowVpn(context.packageName)
        shell("settings put global private_dns_mode off")
        container.profileStore.initialize()
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(setOf(testPackage))
        container.uiSettingsStore.setDnsMode(DnsMode.Secure)
        container.vpnController.setHomeVisible(true)
        container.vpnController.setDiagnosticsVisible(true)
        VpnTestHooks.reset()
        try {
            stopAndAwaitIdle(container.vpnController, context)
            for (name in testLabels) {
                val server = checkNotNull(selected[name])
                val profile = container.profileStore.create(
                    "Production E2E $name",
                    ManagedProfileFactory.single(server),
                    ProfileSource.Subscription,
                )
                createdIds += profile.id
                try {
                    var lastFailure: String? = null
                    for (attempt in 1..MAX_E2E_ATTEMPTS) {
                        try {
                            val terminal = startAndAwaitTerminal(container.vpnController, profile.id)
                            if (terminal !is VpnConnectionState.Connected) {
                                container.diagnosticExporter.createShareIntent()
                                lastFailure = safeState(terminal)
                                continue
                            }
                            val before = VpnRuntimeMetrics.trafficTotal()
                            val https = controlCall(context, ControlTrafficProvider.METHOD_HTTPS)
                            if (!https.getBoolean(ControlTrafficProvider.RESULT_SUCCESS)) {
                                container.diagnosticExporter.createShareIntent()
                                lastFailure = "HTTPS " +
                                    safeText(https.getString(ControlTrafficProvider.RESULT_ERROR))
                                continue
                            }
                            val grew = withTimeoutOrNull(12_000) {
                                while (VpnRuntimeMetrics.trafficTotal() <= before) delay(50)
                                true
                            } == true
                            lastFailure = if (grew) {
                                null
                            } else {
                                "HTTPS completed without observable TUN traffic"
                            }
                            if (lastFailure == null) break
                        } catch (error: Throwable) {
                            lastFailure = safeText(error.message ?: error.javaClass.simpleName)
                        } finally {
                            stopAndAwaitIdle(container.vpnController, context)
                        }
                        if (attempt < MAX_E2E_ATTEMPTS) delay(250)
                    }
                    lastFailure?.let { failures += "$name: $it" }
                } finally {
                    stopAndAwaitIdle(container.vpnController, context)
                    container.profileStore.delete(profile.id)
                    createdIds -= profile.id
                }
            }
        } finally {
            stopAndAwaitIdle(container.vpnController, context)
            createdIds.forEach { id -> runCatching { container.profileStore.delete(id) } }
            container.vpnController.setHomeVisible(false)
            container.vpnController.setDiagnosticsVisible(false)
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            container.appSelectionStore.replaceAllowlist(emptySet())
            VpnTestHooks.reset()
            denyVpn(context.packageName)
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    private fun productionCandidate(): Pair<Context, ImportCandidate.Managed> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true")
        val context = instrumentation.targetContext
        val snapshot = File(context.cacheDir, SNAPSHOT_PATH)
        assumeTrue("Private production snapshot was not staged", snapshot.isFile)
        val raw = snapshot.readText(Charsets.UTF_8)
        val candidate = ImportParser.parse(raw, ProfileSource.Subscription, "Production audit")
        assertTrue("Snapshot did not produce a managed subscription", candidate is ImportCandidate.Managed)
        return context to (candidate as ImportCandidate.Managed)
    }

    private fun assertTransportParity(expected: JsonObject, config: String, stage: String) {
        val root = JsonConfig.parse(config) as JsonObject
        val actual = (root["outbounds"] as JsonArray)
            .mapNotNull { it as? JsonObject }
            .single { it.string("type") == "hysteria2" }
        TRANSPORT_FIELDS.forEach { field ->
            assertTrue("Hysteria2 $field changed in $stage config", expected[field] == actual[field])
        }
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

    private suspend fun stopAndAwaitIdle(controller: VpnController, context: Context) {
        if (controller.state.value !is VpnConnectionState.Stopped) {
            controller.stop()
            withTimeout(25_000) { controller.state.first { it is VpnConnectionState.Stopped } }
        }
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

    private fun safeState(state: VpnConnectionState): String = safeText(state.toString())

    private fun safeText(value: String?): String = SecretRedactor.redactInline(value.orEmpty())

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.tls(): JsonObject? = this["tls"] as? JsonObject

    private fun JsonObject.obfsType(): String? = (this["obfs"] as? JsonObject)?.string("type")

    private fun List<ManagedServer>.server(label: String): ManagedServer {
        val matches = filter { it.displayName.contains(label, ignoreCase = true) }
        assertEquals("Production label must identify exactly one member: $label", 1, matches.size)
        return matches.single()
    }

    private companion object {
        const val ENABLE_ARGUMENT = "productionHysteriaAudit"
        const val PROFILE_ARGUMENT = "productionHysteriaProfile"
        const val SNAPSHOT_PATH = "import/subscription.txt"
        const val MAX_E2E_ATTEMPTS = 2
        val REQUIRED_E2E_NAMES = listOf(
            "Hosal",
            "Polite Horologium",
            "Vdasasi",
            "Magnus",
            "Serva Pro",
            "4valon",
        )
        val TRANSPORT_FIELDS = listOf(
            "server",
            "server_port",
            "server_ports",
            "password",
            "uri",
            "tls",
            "certificate_sha256",
            "obfs",
            "hop_interval",
        )
    }
}
