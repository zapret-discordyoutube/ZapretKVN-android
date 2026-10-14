package io.github.zapretkvn.android.updates

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRouterTest {
    private class Client(val route: UpdateRoute, val behavior: (UpdateRoute) -> String) : UpdateHttpClient {
        override fun open(url: String, start: Long, endInclusive: Long?): UpdateResponse = error("unused")
        override fun readText(url: String, maxBytes: Int): String = behavior(route)
    }

    private class FakeVpn(val failConnect: Boolean = false) : UpdateVpnFallback {
        val connected = AtomicInteger()
        val closed = AtomicInteger()
        override suspend fun connect(): UpdateVpnSession {
            connected.incrementAndGet()
            if (failConnect) throw UpdateException("сначала разрешите VPN")
            return UpdateVpnSession { closed.incrementAndGet() }
        }
    }

    private fun router(
        vpn: FakeVpn?,
        vpnActive: Boolean,
        prefs: UpdatePrefs = InMemoryUpdatePrefs(),
        hedge: Long = 50,
        behavior: (UpdateRoute) -> String,
    ) = UpdateRouter({ route -> Client(route, behavior) }, vpn, { vpnActive }, prefs, hedge)

    private val routeFailure = { UpdateException("Не удалось связаться с Forgejo.", retryViaVpn = true) }

    @Test
    fun `direct wins and the vpn route is never started when direct answers in time`() = runBlocking {
        val vpn = FakeVpn()
        val lease = router(vpn, vpnActive = true, hedge = 5_000) { "ok-${it.name}" }
            .race(allowStartingVpn = true) { it.readText("u", 1) }
        assertEquals("ok-Direct", lease.value)
        lease.close()
        assertEquals(0, vpn.connected.get())
    }

    @Test
    fun `direct blocked while vpn is up - the vpn route answers and nothing fails`() = runBlocking {
        val vpn = FakeVpn()
        val prefs = InMemoryUpdatePrefs()
        val router = router(vpn, vpnActive = true, prefs = prefs) { route ->
            if (route == UpdateRoute.Direct) throw routeFailure() else "via-vpn"
        }
        val lease = router.race(allowStartingVpn = true) { it.readText("u", 1) }
        assertEquals("via-vpn", lease.value)
        assertEquals(UpdateRoute.Vpn, lease.route)
        assertEquals(UpdateRoute.Vpn, prefs.lastRoute)
        assertEquals("the route stays on until the lease is closed", 0, vpn.closed.get())
        lease.close()
        assertEquals(1, vpn.closed.get())
    }

    @Test
    fun `slow direct is overtaken by the vpn route after the hedge delay`() = runBlocking {
        val vpn = FakeVpn()
        val release = CountDownLatch(1)
        val router = router(vpn, vpnActive = true, hedge = 30) { route ->
            if (route == UpdateRoute.Direct) {
                release.await(5, TimeUnit.SECONDS)
                "slow-direct"
            } else {
                "fast-vpn"
            }
        }
        val lease = router.race(allowStartingVpn = true) { it.readText("u", 1) }
        release.countDown()
        assertEquals("fast-vpn", lease.value)
        lease.close()
    }

    @Test
    fun `losing vpn attempt is restored when direct wins late`() = runBlocking {
        val vpn = FakeVpn()
        val vpnEntered = CountDownLatch(1)
        val router = router(vpn, vpnActive = true, hedge = 20) { route ->
            if (route == UpdateRoute.Direct) {
                vpnEntered.await(5, TimeUnit.SECONDS)
                Thread.sleep(50)
                "direct"
            } else {
                vpnEntered.countDown()
                Thread.sleep(500)
                "vpn"
            }
        }
        val lease = router.race(allowStartingVpn = true) { it.readText("u", 1) }
        assertEquals("direct", lease.value)
        lease.close()
        // Проигравшая попытка дозавершается сама и обязана вернуть VPN в прежнее состояние.
        val deadline = System.nanoTime() + 3_000_000_000L
        while (vpn.closed.get() < vpn.connected.get() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(vpn.connected.get(), vpn.closed.get())
    }

    @Test
    fun `both routes failing reports one aggregated failure`() {
        val error = assertThrows(UpdateRoutesFailedException::class.java) {
            runBlocking {
                router(FakeVpn(), vpnActive = true) { throw routeFailure() }
                    .race(allowStartingVpn = true) { it.readText("u", 1) }
            }
        }
        assertEquals(2, error.failures.size)
        assertEquals("Не удалось связаться с Forgejo.", error.message)
        assertTrue(error.retryViaVpn)
        assertTrue("Direct" in error.detail && "Vpn" in error.detail)
    }

    @Test
    fun `without vpn the direct route goes first and the temporary vpn only on a route failure`() = runBlocking {
        val vpn = FakeVpn()
        val ok = router(vpn, vpnActive = false) { "direct" }.race(allowStartingVpn = true) { it.readText("u", 1) }
        assertEquals("direct", ok.value)
        assertEquals(0, vpn.connected.get())

        val fallback = router(vpn, vpnActive = false) { route ->
            if (route == UpdateRoute.Direct) throw routeFailure() else "vpn"
        }.race(allowStartingVpn = true) { it.readText("u", 1) }
        assertEquals("vpn", fallback.value)
        assertEquals(1, vpn.connected.get())
        fallback.close()
        assertEquals(1, vpn.closed.get())
    }

    @Test
    fun `background run never starts a vpn that is switched off`() {
        val vpn = FakeVpn()
        assertThrows(UpdateRoutesFailedException::class.java) {
            runBlocking {
                router(vpn, vpnActive = false) { throw routeFailure() }
                    .race(allowStartingVpn = false) { it.readText("u", 1) }
            }
        }
        assertEquals(0, vpn.connected.get())
    }

    @Test
    fun `a missing vpn permission is just a failed route`() {
        val error = assertThrows(UpdateRoutesFailedException::class.java) {
            runBlocking {
                router(FakeVpn(failConnect = true), vpnActive = false) { throw routeFailure() }
                    .race(allowStartingVpn = true) { it.readText("u", 1) }
            }
        }
        assertTrue("разрешите VPN" in error.detail)
    }

    @Test
    fun `non route errors are not retried on another route`() {
        val vpn = FakeVpn()
        val calls = AtomicInteger()
        val error = assertThrows(UpdateException::class.java) {
            runBlocking {
                router(vpn, vpnActive = false) {
                    calls.incrementAndGet()
                    throw UpdateException("Release предназначен для другого Android package.")
                }.race(allowStartingVpn = true) { it.readText("u", 1) }
            }
        }
        assertFalse(error.retryViaVpn)
        assertEquals(1, calls.get())
        assertEquals(0, vpn.connected.get())
    }

    @Test
    fun `the route that worked last is tried first`() = runBlocking {
        val prefs = InMemoryUpdatePrefs()
        assertEquals(listOf(UpdateRoute.Direct, UpdateRoute.Vpn), router(null, false, prefs) { "x" }.preferredOrder())
        prefs.lastRoute = UpdateRoute.Vpn
        val order = mutableListOf<UpdateRoute>()
        val lease = router(FakeVpn(), vpnActive = false, prefs = prefs) { route ->
            order += route
            "x"
        }.sequential(listOf(UpdateRoute.Vpn, UpdateRoute.Direct)) { it.readText("u", 1) }
        assertEquals(listOf(UpdateRoute.Vpn), order)
        lease.close()
        assertEquals(UpdateRoute.Vpn, router(null, false, prefs) { "x" }.preferredOrder().first())
    }

    @Test
    fun `download order puts the check winner first and respects the background rule`() {
        val vpn = FakeVpn()
        val r = router(vpn, vpnActive = false) { "x" }
        assertEquals(listOf(UpdateRoute.Vpn, UpdateRoute.Direct), r.downloadOrder(UpdateRoute.Vpn, allowStartingVpn = true))
        assertEquals(listOf(UpdateRoute.Direct), r.downloadOrder(UpdateRoute.Direct, allowStartingVpn = false))
        assertEquals(listOf(UpdateRoute.Direct), router(null, false) { "x" }.downloadOrder(null, true))
        assertNull(InMemoryUpdatePrefs().lastRoute)
    }
}
