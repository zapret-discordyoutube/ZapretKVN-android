package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencySample
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLatencyStoreTest {
    private val directory: File = Files.createTempDirectory("latency").toFile()
    private val file = File(directory, "server-latency.json")
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        directory.deleteRecursively()
    }

    private fun store(now: Long = 5_000L): ServerLatencyStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        return ServerLatencyStore(file, scope, clock = { now })
    }

    private fun success(millis: Int, at: Long) =
        LatencyProbeState.Success(LatencySample(millis, at, networkIdentity = "wifi:1"))

    private val fingerprints = mapOf(
        "tokyo" to ServerLatencyFingerprint.of("vless", "tokyo.example:443"),
        "paris" to ServerLatencyFingerprint.of("hysteria2", "paris.example:443"),
    )

    @Test
    fun `results survive a restart of the store`() = runBlocking {
        withTimeout(10_000) {
            val first = store()
            first.record(
                profileId = "profile",
                fingerprints = fingerprints,
                relay = mapOf("tokyo" to success(42, 1_000), "paris" to success(90, 1_100)),
                icmp = mapOf("tokyo" to LatencyProbeState.Failed(LatencyFailure.NoResponse)),
            )
            first.awaitPersisted()
            assertTrue(file.isFile)
            assertFalse("server addresses are never written", file.readText().contains("example"))

            val second = store()
            second.awaitPersisted()
            val restored = second.forProfile("profile")
            assertEquals(42, restored.getValue("tokyo").relay?.millis)
            assertEquals(1_000L, restored.getValue("tokyo").relay?.measuredAtEpochMillis)
            val icmp = restored.getValue("tokyo").icmp!!
            assertNull(icmp.millis)
            assertEquals(LatencyFailure.NoResponse, icmp.failure)
            assertEquals("failure without its own time gets the record time", 5_000L, icmp.measuredAtEpochMillis)
            assertEquals(90, restored.getValue("paris").relay?.millis)
            assertEquals(first.entries.value, second.entries.value)
        }
    }

    @Test
    fun `a new measurement overwrites and a failure keeps the last good ping`() = runBlocking {
        val store = store()
        store.record("profile", fingerprints, relay = mapOf("tokyo" to success(42, 1_000)))
        store.record("profile", fingerprints, relay = mapOf("tokyo" to success(35, 2_000)))
        assertEquals(35, store.forProfile("profile").getValue("tokyo").relay?.millis)
        store.record(
            "profile",
            fingerprints,
            relay = mapOf("tokyo" to LatencyProbeState.Failed(LatencyFailure.Failed, failedAtEpochMillis = 3_000)),
        )
        val relay = store.forProfile("profile").getValue("tokyo").relay!!
        assertTrue(relay.failed)
        assertEquals(35, relay.previousMillis)
        val restoredState = relay.toState() as LatencyProbeState.Failed
        assertEquals(3_000L, restoredState.failedAtEpochMillis)
        assertEquals(35, restoredState.previous?.millis)
        // Running/unsupported states are not results and never overwrite a stored ping.
        store.record("profile", fingerprints, relay = mapOf("paris" to LatencyProbeState.Running(null)))
        assertNull(store.forProfile("profile")["paris"])
    }

    @Test
    fun `pruning drops vanished profiles, vanished servers and changed addresses`() = runBlocking {
        withTimeout(10_000) {
            val store = store()
            store.record("keep", fingerprints, relay = mapOf("tokyo" to success(42, 1), "paris" to success(50, 1)))
            store.record("gone", fingerprints, relay = mapOf("tokyo" to success(10, 1)))
            store.record("legacy", emptyMap(), relay = mapOf("tokyo" to success(11, 1)))
            store.retain(
                mapOf(
                    // "paris" left the subscription; "tokyo" kept its tag and address.
                    "keep" to mapOf("tokyo" to fingerprints.getValue("tokyo")),
                    "legacy" to mapOf("tokyo" to fingerprints.getValue("tokyo")),
                ),
            )
            assertEquals(setOf("keep", "legacy"), store.entries.value.keys)
            assertEquals(setOf("tokyo"), store.forProfile("keep").keys)
            assertEquals(
                "an entry without fingerprint adopts the current one",
                fingerprints.getValue("tokyo"),
                store.forProfile("legacy").getValue("tokyo").fingerprint,
            )
            // Same tag, different server after a subscription refresh.
            store.retain(mapOf("keep" to mapOf("tokyo" to ServerLatencyFingerprint.of("vless", "other:443"))))
            assertTrue(store.entries.value.isEmpty())
            store.awaitPersisted()
            val restored = store()
            restored.awaitPersisted()
            assertTrue(restored.entries.value.isEmpty())
        }
    }

    @Test
    fun `corrupt or foreign files are ignored`() = runBlocking {
        file.writeText("{not json")
        assertTrue(ServerLatencyCodec.decode(file.readText()).isEmpty())
        assertTrue(ServerLatencyCodec.decode("""{"v":99,"profiles":{}}""").isEmpty())
        val store = store()
        store.awaitPersisted()
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `persisted pings fill only untested runtime items`() {
        val persisted = mapOf(
            "tokyo" to PersistedServerLatency(
                fingerprint = fingerprints.getValue("tokyo"),
                relay = PersistedProbeResult(42, null, 1_000),
                icmp = PersistedProbeResult(null, LatencyFailure.NoResponse, 1_000),
            ),
            "paris" to PersistedServerLatency(
                fingerprint = "someone-else",
                relay = PersistedProbeResult(99, null, 1_000),
            ),
        )
        val fresh = success(15, 9_000)
        val groups = listOf(
            RuntimeSelectorGroup(
                tag = "zapret-proxy",
                type = "selector",
                selected = "tokyo",
                selectable = true,
                items = listOf(
                    RuntimeOutboundItem("tokyo", "vless", null, relay = LatencyProbeState.NotTested),
                    RuntimeOutboundItem("paris", "hysteria2", null),
                    RuntimeOutboundItem("berlin", "vless", null, relay = fresh),
                ),
            ),
        )
        val items = ServerLatencyReducer.hydrate(groups, persisted, fingerprints).single().items
        assertEquals(42, (items[0].relay as LatencyProbeState.Success).sample.millis)
        assertEquals(LatencyFailure.NoResponse, (items[0].icmp as LatencyProbeState.Failed).reason)
        assertEquals("address changed: no stale ping", LatencyProbeState.NotTested, items[1].relay)
        assertEquals(fresh, items[2].relay)
    }
}
