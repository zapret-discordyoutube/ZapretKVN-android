package io.github.zapretkvn.android.diagnostics

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.DnsOverride
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.vpn.PrivateDnsMode
import io.github.zapretkvn.android.vpn.UnderlyingNetworkState
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnSystemPolicy
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticExportInstrumentedTest {
    @Test
    fun reportIsExplicitRedactedShareableAndCleanable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val exporter = container.diagnosticExporter
        val directory = File(context.cacheDir, DiagnosticExporter.DIRECTORY_NAME)
        exporter.cleanupStaleFiles()
        container.appCrashStore.clear()
        container.uiSettingsStore.setDnsMode(DnsMode.Automatic)
        container.uiSettingsStore.setDnsOverride(
            DnsOverride.DEFAULT_HOSTNAME,
            DnsOverride.DEFAULT_IPV4_ADDRESS,
        )
        container.uiSettingsStore.setDnsOverrideEnabled(true)
        assertFalse("No report may exist before the explicit action", directory.exists())

        val previousToken = container.vpnController.nextGeneration()
        container.vpnController.beginConnectionDiagnostic(previousToken, "previous_test")
        container.vpnController.startConnectionDiagnosticStage(previousToken, "profile", "Профиль")
        container.vpnController.publish(
            previousToken,
            VpnConnectionState.Error("DNS через VPN не отвечает: previous test."),
        )
        val token = container.vpnController.nextGeneration()
        try {
            container.vpnController.beginConnectionDiagnostic(token, "instrumented_test")
            container.vpnController.beginConnectionCandidate(token, 2)
            container.vpnController.recordConnectionVpnNetwork(token, identity = "42", lost = true)
            container.vpnController.startConnectionDiagnosticStage(token, "profile", "Профиль")
            container.vpnController.startConnectionDiagnosticStage(token, "dns_probe", "DNS-проверка")
            container.vpnController.publish(token, VpnConnectionState.Starting("hidden-profile", "Тест"))
            container.vpnController.attachDiagnosticRuntimeMap(
                token,
                DiagnosticRuntimeMap.create(
                    profileId = "hidden-profile",
                    profileName = "Hidden profile",
                    descriptions = mapOf(
                        "hy2-prod" to OutboundDescription(
                            tag = "hy2-prod",
                            type = "hysteria2",
                            serverHost = "vpn.example",
                            endpoint = "vpn.example:8443",
                        ),
                    ),
                    selectedRawTag = "hy2-prod",
                ),
            )
            container.vpnController.publishDiagnosticNetwork(
                token,
                UnderlyingNetworkState(
                    transport = "wifi",
                    interfaceName = "wlan0",
                    metered = false,
                    validated = true,
                    privateDnsMode = PrivateDnsMode.Automatic,
                    privateDnsActive = true,
                ),
            )
            container.vpnController.publishEffectiveOverlay(
                token,
                """{"dns_mode":"Automatic","uuid":"123e4567-e89b-12d3-a456-426614174000"}""",
            )
            container.vpnController.publishVpnSystemPolicy(
                token,
                VpnSystemPolicy(statusAvailable = true, alwaysOn = false, lockdown = false),
            )
            container.vpnController.publishCoreDiagnosticLog(
                token,
                3,
                "\u001B[31mtoken=super-secret 123e4567-e89b-12d3-a456-426614174000 " +
                    "hysteria2://auth-secret@vpn.example:8443?pinSHA256=pin-secret&ech=ech-secret#name " +
                    "outbound/hysteria2[hy2-prod] com.example.hidden 203.0.113.7\u001B[0m",
            )
            container.vpnController.publish(
                token,
                VpnConnectionState.Connected(
                    profileId = "hidden-profile",
                    profileName = "Hidden profile",
                    connectedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            val stopToken = container.vpnController.nextGeneration()
            container.vpnController.beginStopDiagnostic(stopToken, "instrumented_stop")
            container.vpnController.startStopDiagnosticStage(
                stopToken,
                "close_tun",
                "Закрытие Android TUN",
            )
            container.vpnController.finishStopDiagnosticStage(stopToken, "close_tun")
            container.vpnController.startStopDiagnosticStage(
                stopToken,
                "close_libbox_service",
                "Остановка сервиса libbox",
            )
            container.vpnController.finishStopDiagnosticStage(stopToken, "close_libbox_service")
            container.vpnController.completeStopDiagnostic(stopToken)
            container.appCrashStore.record(
                threadName = "test-worker",
                throwable = IllegalStateException("token=super-secret"),
            )
            val savedCrash = checkNotNull(container.appCrashStore.read())
            assertEquals("IllegalStateException", savedCrash.exceptionType)
            assertFalse(savedCrash.message.orEmpty().contains("super-secret"))
            assertTrue(savedCrash.stack.size <= 16)
            val inMemoryLogs = container.vpnController.diagnostics.value.logs
                .joinToString("\n") { it.message }
            assertFalse("super-secret" in inMemoryLogs)
            assertFalse("123e4567-e89b-12d3-a456-426614174000" in inMemoryLogs)
            assertFalse("\u001B" in inMemoryLogs)
            assertFalse("hysteria2://" in inMemoryLogs)
            assertFalse("pinSHA256" in inMemoryLogs)
            assertFalse("ech-secret" in inMemoryLogs)
            assertFalse("vpn.example" in inMemoryLogs)

            val report = exporter.createReport()
            JsonConfig.parse(report)
            assertTrue("\"report_version\": 6" in report)
            assertTrue(BuildConfig.CORE_COMMIT in report)
            assertTrue(BuildConfig.CORE_PATCH_SHA256 in report)
            assertTrue("\"private_dns_mode\"" in report)
            assertTrue("\"override_active\": true" in report)
            assertTrue("\"vpn_system_policy\"" in report)
            assertTrue("\"supported_by_app\": false" in report)
            assertTrue("\"effective_overlay\"" in report)
            assertTrue("\"connection_attempt\"" in report)
            assertTrue("\"connection_attempts\"" in report)
            assertTrue("\"stop_attempt\"" in report)
            assertTrue("\"close_tun\"" in report)
            assertTrue("\"close_libbox_service\"" in report)
            assertTrue("\"startup_core_logs\"" in report)
            assertTrue("\"startup_core_log_stats\"" in report)
            assertTrue("\"log_stats\"" in report)
            assertTrue("\"previous_process_exit\"" in report)
            assertTrue("\"runtime_resources\"" in report)
            assertTrue("\"outer_tun_mtu\"" in report)
            assertTrue("\"inner_wireguard_mtu\"" in report)
            assertTrue("\"candidate_attempt_id\": 2" in report)
            assertTrue("\"vpn_network_identity\": \"42\"" in report)
            assertTrue("\"vpn_network_lost\": true" in report)
            assertTrue("\"dns_probe_socket_path\": \"vpn_uid_tun\"" in report)
            assertTrue("\"elapsed_ms\"" in report)
            assertTrue("\"remaining_startup_budget_ms\"" in report)
            assertTrue("\"profile_ref\"" in report)
            assertTrue("\"profile_name\"" in report)
            assertTrue("\"outbound_tag\": \"hy2-prod\"" in report)
            assertTrue("\"protocol\": \"hysteria2\"" in report)
            assertTrue("\"endpoint\": \"ep-" in report)
            assertTrue("\"runtime_log_persisted\": false" in report)
            assertTrue("\"support_code\": \"DNS-200\"" in report)
            assertEquals(
                2,
                (JsonConfig.parse(report) as JsonObject)
                    .getValue("connection_attempts")
                    .let { it as JsonArray }
                    .size,
            )
            assertTrue("\"total_duration_ms\"" in report)
            assertTrue("\"dns_probe\"" in report)
            assertTrue("\"previous_crash\"" in report)
            assertTrue("\"IllegalStateException\"" in report)
            assertFalse("hidden-profile" in report)
            assertFalse("super-secret" in report)
            assertFalse("hysteria2://" in report)
            assertFalse("auth-secret" in report)
            assertFalse("pin-secret" in report)
            assertFalse("ech-secret" in report)
            assertFalse("vpn.example" in report)
            assertFalse("123e4567-e89b-12d3-a456-426614174000" in report)
            assertFalse("com.example.hidden" in report)
            assertFalse("203.0.113.7" in report)
            assertFalse(DnsOverride.DEFAULT_HOSTNAME in report)
            assertFalse(DnsOverride.DEFAULT_IPV4_ADDRESS in report)
            assertFalse("allowed_packages" in report)
            assertFalse(directory.exists())

            val share = exporter.createShareIntent()
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals("application/json", share.type)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = share.clipData?.getItemAt(0)?.uri
            assertNotNull(uri)
            val sharedReport = context.contentResolver.openInputStream(checkNotNull(uri))
                ?.bufferedReader()
                ?.use { it.readText() }
            assertNotNull(sharedReport)
            assertFalse("super-secret" in checkNotNull(sharedReport))
            assertTrue(File(directory, DiagnosticExporter.FILE_NAME).isFile)

            val provider = context.packageManager.resolveContentProvider(
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                PackageManager.GET_META_DATA,
            )
            assertNotNull(provider)
            assertFalse(checkNotNull(provider).exported)
        } finally {
            exporter.cleanupStaleFiles()
            container.appCrashStore.clear()
            container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
            container.vpnController.publish(token, VpnConnectionState.Stopped)
        }
        assertFalse(directory.exists())
    }
}
