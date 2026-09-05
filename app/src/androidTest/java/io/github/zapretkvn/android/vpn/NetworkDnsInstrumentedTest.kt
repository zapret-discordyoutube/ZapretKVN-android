package io.github.zapretkvn.android.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.network.AndroidLocalDnsTransport
import io.github.zapretkvn.android.network.BootstrapCache
import io.github.zapretkvn.android.network.BootstrapCacheEntry
import io.github.zapretkvn.android.network.BootstrapResolver
import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.UnderlyingNetworkState
import io.github.zapretkvn.android.network.probes.ProxyBootstrapper
import io.github.zapretkvn.networkbootstrap.BootstrapFailureCode
import io.github.zapretkvn.networkbootstrap.BootstrapFailureException
import java.io.File
import java.io.FileInputStream
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkDnsInstrumentedTest {
    @Test
    fun monitorReturnsOnlyUnderlyingNetworkAndBootstrapUsesItsResolver() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val baseline = VpnRuntimeMetrics.snapshot().activeNetworkCallbacks
        val monitor = DefaultNetworkMonitor(context)
        try {
            monitor.start()
            val state = monitor.awaitUnderlying(10_000)
            val network = checkNotNull(state.network)
            val capabilities = connectivity.getNetworkCapabilities(network)

            assertNotNull(state.interfaceName)
            assertFalse(capabilities!!.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            assertTrue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            assertTrue(BootstrapResolver().resolve(network, "example.com").isNotEmpty())
            assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q, AndroidLocalDnsTransport { network }.raw())
        } finally {
            monitor.close()
        }
        assertEquals(baseline, VpnRuntimeMetrics.snapshot().activeNetworkCallbacks)
    }

    @Test
    fun proxyBootstrapPerformsSocketPreflightOnUnderlyingNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val monitor = DefaultNetworkMonitor(context)
        GateEchoServer().use { echo ->
            val cacheRoot = File(context.cacheDir, "bootstrap-test-${System.nanoTime()}")
            try {
                monitor.start()
                val underlying = checkNotNull(monitor.awaitUnderlying(10_000).network)
                val prepared = ProxyBootstrapper(
                    BootstrapResolver(),
                    BootstrapCache(cacheRoot),
                ).prepare(
                    profileId = "test-profile",
                    rawJson = """
                        {
                          "outbounds":[
                            {"type":"vless","tag":"server-a","server":"${echo.reachableAddress}","server_port":${echo.port},"tls":{"enabled":true,"server_name":"vpn.example"}},
                            {"type":"selector","tag":"zapret-proxy","outbounds":["server-a"],"default":"server-a"}
                          ],
                          "route":{"final":"zapret-proxy"}
                        }
                    """.trimIndent(),
                    underlying = underlying,
                )

                assertEquals("server-a", prepared.target?.outboundTag)
                assertTrue(prepared.addresses.isNotEmpty())
                assertEquals(null, prepared.overlay)
            } finally {
                monitor.close()
            }
        }
    }

    @Test
    fun blockedSystemDnsUsesFreshAndEmergencyLkgButRejectsExpiredOrMissingCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val monitor = DefaultNetworkMonitor(context)
        GateEchoServer().use { echo ->
            val cacheRoot = File(context.cacheDir, "lkg-matrix-${System.nanoTime()}")
            val cache = BootstrapCache(cacheRoot)
            val bootstrapper = ProxyBootstrapper(BootstrapResolver(), cache)
            val profileId = "lkg-profile"
            val hostname = "blocked-bootstrap.invalid"
            val rawJson = lkgProfile(hostname, echo.port)
            try {
                monitor.start()
                val underlying = checkNotNull(monitor.awaitUnderlying(10_000).network)
                val now = System.currentTimeMillis()

                suspend fun assertLkgAccepted(ageMillis: Long) {
                    cache.removeProfile(profileId)
                    cache.recordSuccess(
                        profileId = profileId,
                        hostname = hostname,
                        addresses = listOf(InetAddress.getByName(echo.reachableAddress)),
                        resolvedAtEpochMillis = now - ageMillis,
                    )
                    VpnTestHooks.failNextBootstrapResolution()
                    val prepared = bootstrapper.prepare(profileId, rawJson, underlying)
                    assertEquals(hostname, prepared.overlay?.hostname)
                    assertTrue(echo.reachableAddress in prepared.overlay!!.addresses)
                }

                assertLkgAccepted(ageMillis = 60_000)
                assertLkgAccepted(ageMillis = 2L * 24 * 60 * 60 * 1_000)

                cache.removeProfile(profileId)
                cache.recordSuccess(
                    profileId = profileId,
                    hostname = hostname,
                    addresses = listOf(InetAddress.getByName(echo.reachableAddress)),
                    resolvedAtEpochMillis = now - BootstrapCacheEntry.EMERGENCY_MILLIS - 1,
                )
                VpnTestHooks.failNextBootstrapResolution()
                val expired = runCatching { bootstrapper.prepare(profileId, rawJson, underlying) }.exceptionOrNull()
                assertEquals(
                    "Expired LKG must preserve the typed DNS failure",
                    BootstrapFailureCode.DnsSystem,
                    (expired as? BootstrapFailureException)?.reason,
                )

                cache.removeProfile(profileId)
                VpnTestHooks.failNextBootstrapResolution()
                val missing = runCatching { bootstrapper.prepare(profileId, rawJson, underlying) }.exceptionOrNull()
                assertEquals(
                    "Missing LKG must preserve the typed DNS failure",
                    BootstrapFailureCode.DnsSystem,
                    (missing as? BootstrapFailureException)?.reason,
                )
            } finally {
                VpnTestHooks.reset()
                monitor.close()
                cache.clear()
                cacheRoot.deleteRecursively()
            }
        }
    }

    @Test
    fun monitorTracksRealCellularWifiCellularTransitionAndWifiIpv6() = runBlocking {
        assumeTrue("This transport test requires an Android emulator", isEmulator())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shell("svc data enable")
        shell("su 0 svc wifi disable")
        awaitActiveTransport(connectivity, NetworkCapabilities.TRANSPORT_CELLULAR)
        val monitor = DefaultNetworkMonitor(context)
        try {
            monitor.start()
            val cellular = awaitTransport(monitor, connectivity, NetworkCapabilities.TRANSPORT_CELLULAR)
            val cellularIdentity = cellular.identity

            shell("su 0 svc wifi enable")
            awaitActiveTransport(connectivity, NetworkCapabilities.TRANSPORT_WIFI)
            val wifi = awaitTransport(monitor, connectivity, NetworkCapabilities.TRANSPORT_WIFI)
            assertNotEquals(cellularIdentity, wifi.identity)
            val wifiNetwork = checkNotNull(wifi.network)
            val ipv6Ready = withTimeoutOrNull(30_000) {
                while (true) {
                    val properties = connectivity.getLinkProperties(wifiNetwork)
                    if (properties?.linkAddresses.orEmpty().any { link ->
                            val address = link.address
                            address is Inet6Address && !address.isLinkLocalAddress && !address.isLoopbackAddress
                        }
                    ) {
                        return@withTimeoutOrNull properties
                    }
                    delay(100)
                }
            }
            assertNotNull(
                "Emulator Wi-Fi did not expose a non-link-local IPv6 address: " +
                    connectivity.getLinkProperties(wifiNetwork),
                ipv6Ready,
            )

            shell("su 0 svc wifi disable")
            awaitActiveTransport(connectivity, NetworkCapabilities.TRANSPORT_CELLULAR)
            val cellularAgain = awaitTransport(monitor, connectivity, NetworkCapabilities.TRANSPORT_CELLULAR)
            assertNotEquals(wifi.identity, cellularAgain.identity)
        } finally {
            monitor.close()
            shell("svc data enable")
            shell("su 0 svc wifi enable")
        }
    }

    private suspend fun awaitTransport(
        monitor: DefaultNetworkMonitor,
        connectivity: ConnectivityManager,
        transport: Int,
    ): UnderlyingNetworkState {
        var result: UnderlyingNetworkState? = null
        withTimeoutOrNull(30_000) {
            while (result == null) {
                val state = monitor.current
                val network = state.network
                if (network != null && connectivity.getNetworkCapabilities(network)?.hasTransport(transport) == true) {
                    result = state
                }
                if (result == null) delay(100)
            }
        }
        return requireNotNull(result) {
            "Monitor did not select transport=$transport; current=${monitor.current}; " +
                "active=${connectivity.activeNetwork}:${connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)}"
        }
    }

    private suspend fun awaitActiveTransport(
        connectivity: ConnectivityManager,
        transport: Int,
    ): Network = withTimeout(30_000) {
        while (true) {
            connectivity.activeNetwork?.let { network ->
                if (connectivity.getNetworkCapabilities(network)?.hasTransport(transport) == true) {
                    return@withTimeout network
                }
            }
            delay(100)
        }
        error("unreachable")
    }

    private fun lkgProfile(hostname: String, port: Int): String = """
        {
          "outbounds":[
            {
              "type":"vless","tag":"server-a","server":"$hostname","server_port":$port,
              "uuid":"00000000-0000-4000-8000-000000000001",
              "tls":{"enabled":true,"server_name":"vpn.example"}
            },
            {"type":"selector","tag":"zapret-proxy","outbounds":["server-a"],"default":"server-a"}
          ],
          "route":{"final":"zapret-proxy"}
        }
    """.trimIndent()

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.startsWith("sdk_gphone") ||
            Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "goldfish"

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    input.readBytes().toString(Charsets.UTF_8).trim()
                }
            }
}
