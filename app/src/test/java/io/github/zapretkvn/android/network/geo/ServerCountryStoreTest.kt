package io.github.zapretkvn.android.network.geo

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCountryStoreTest {
    private val dir: File = Files.createTempDirectory("country-store").toFile()
    private val job = Job()
    private val scope = CoroutineScope(job)

    @After
    fun cleanup() {
        job.cancel()
        dir.deleteRecursively()
    }

    private class FakeGeo(
        private val byAddress: Map<String, String>,
        override val databaseId: String = "zgc1-202610",
    ) : CountryLookup {
        override fun lookup(address: ByteArray): String? =
            byAddress[InetAddress.getByAddress(address).hostAddress]
    }

    private class FakeResolver(val answers: Map<String, List<String>?>) : HostAddressResolver {
        val calls = mutableListOf<String>()
        private val inFlight = AtomicInteger()
        var maxInFlight = 0
        var latencyMillis = 0L

        @Synchronized private fun enter() {
            maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
        }

        override suspend fun resolve(host: String): List<InetAddress>? {
            synchronized(calls) { calls += host }
            enter()
            try {
                if (latencyMillis > 0) delay(latencyMillis)
                if (!answers.containsKey(host)) return emptyList()
                return answers[host]?.map(InetAddress::getByName)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private var clock = 1_000_000_000L
    private val geo = FakeGeo(
        mapOf(
            "185.12.34.56" to "NL",
            "95.216.1.1" to "FI",
            "46.4.1.1" to "DE",
            "2a01:4f8:0:0:0:0:0:1" to "DE",
        ),
    )

    private fun store(
        resolver: HostAddressResolver,
        lookup: CountryLookup = geo,
        random: Random = Random(1),
        file: File = File(dir, "network/server-countries.json"),
    ) = ServerCountryStore(file, lookup, resolver, scope, now = { clock }, random = random, pause = {})

    @Test
    fun `ip literals are resolved offline without touching the resolver`() = runBlocking {
        val resolver = FakeResolver(emptyMap())
        val store = store(resolver)
        store.refresh(setOf("185.12.34.56", "2a01:4f8::1", "10.0.0.1"))
        assertEquals(mapOf("185.12.34.56" to "NL", "2a01:4f8::1" to "DE"), store.countries.value)
        assertTrue(resolver.calls.isEmpty())
    }

    @Test
    fun `a name is resolved once and then kept for seven days`() = runBlocking {
        val resolver = FakeResolver(mapOf("fast.example.com" to listOf("95.216.1.1")))
        val store = store(resolver)
        store.refresh(setOf("fast.example.com"))
        assertEquals(mapOf("fast.example.com" to "FI"), store.countries.value)
        assertEquals(1, resolver.calls.size)

        clock += ServerCountryStore.REFRESH_MILLIS - 1
        store.refresh(setOf("fast.example.com"))
        assertEquals("not yet due", 1, resolver.calls.size)

        clock += 1
        store.refresh(setOf("fast.example.com"))
        assertEquals("due after seven days", 2, resolver.calls.size)
    }

    @Test
    fun `a failed name is retried only after a day and keeps no wrong flag`() = runBlocking {
        val resolver = FakeResolver(mapOf("dead.example.com" to emptyList()))
        val store = store(resolver)
        store.refresh(setOf("dead.example.com"))
        assertTrue(store.countries.value.isEmpty())
        clock += ServerCountryStore.RETRY_MILLIS - 1
        store.refresh(setOf("dead.example.com"))
        assertEquals(1, resolver.calls.size)
        clock += 1
        store.refresh(setOf("dead.example.com"))
        assertEquals(2, resolver.calls.size)
    }

    @Test
    fun `without a network nothing is attempted or remembered`() = runBlocking {
        val offline = FakeResolver(mapOf("a.example.com" to null, "b.example.com" to null))
        val store = store(offline)
        store.refresh(setOf("a.example.com", "b.example.com"))
        assertEquals("the first miss stops the batch", 1, offline.calls.size)

        val online = FakeResolver(
            mapOf("a.example.com" to listOf("46.4.1.1"), "b.example.com" to listOf("95.216.1.1")),
        )
        val again = store(online)
        again.refresh(setOf("a.example.com", "b.example.com"))
        assertEquals("no wait after a no-network miss", 2, online.calls.size)
        assertEquals(mapOf("a.example.com" to "DE", "b.example.com" to "FI"), again.countries.value)
    }

    @Test
    fun `each host once with at most three in parallel`() = runBlocking {
        val names = (1..12).map { "n$it.example.com" }
        val resolver = FakeResolver(names.associateWith { listOf("46.4.1.1") }).apply { latencyMillis = 15 }
        val store = store(resolver)
        store.refresh(names.toSet() + names) // дубли схлопываются множеством
        assertEquals(names.toSet(), resolver.calls.toSet())
        assertEquals(12, resolver.calls.size)
        assertTrue("parallelism ${resolver.maxInFlight}", resolver.maxInFlight in 2..ServerCountryStore.PARALLELISM)
    }

    @Test
    fun `order is random and the active profile goes first`() = runBlocking {
        val names = (1..20).map { "n$it.example.com" }
        val priority = setOf("n19.example.com", "n20.example.com")
        val orders = listOf(1, 2).map { seed ->
            val resolver = FakeResolver(names.associateWith { listOf("46.4.1.1") })
            val fresh = store(resolver, random = Random(seed), file = File(dir, "seed$seed.json"))
            fresh.refresh(names.toSet(), priority)
            resolver.calls.toList()
        }
        orders.forEach { order ->
            assertEquals(names.toSet(), order.toSet())
            assertEquals(priority, order.take(2).toSet())
        }
        assertTrue("different seeds give different order", orders[0].drop(2) != orders[1].drop(2))
        assertTrue("not input order", orders[0].drop(2) != names.filter { it !in priority })
    }

    @Test
    fun `one batch is capped`() = runBlocking {
        val names = (1..(ServerCountryStore.MAX_PER_RUN + 10)).map { "h$it.example.com" }
        val resolver = FakeResolver(names.associateWith { listOf("46.4.1.1") })
        val store = store(resolver)
        store.refresh(names.toSet())
        assertEquals(ServerCountryStore.MAX_PER_RUN, resolver.calls.size)
        store.refresh(names.toSet())
        assertEquals("the rest goes to the next batch", names.size, resolver.calls.size)
    }

    @Test
    fun `cache survives a restart and does not ask again`() = runBlocking {
        val file = File(dir, "network/server-countries.json")
        val first = FakeResolver(mapOf("fast.example.com" to listOf("95.216.1.1")))
        store(first, file = file).refresh(setOf("fast.example.com", "185.12.34.56"))
        assertTrue(file.isFile)

        val second = FakeResolver(emptyMap())
        val restarted = store(second, file = file)
        restarted.refresh(setOf("fast.example.com", "185.12.34.56"))
        assertTrue(second.calls.isEmpty())
        assertEquals(mapOf("fast.example.com" to "FI", "185.12.34.56" to "NL"), restarted.countries.value)
    }

    @Test
    fun `new table recomputes countries from the saved address without resolving again`() = runBlocking {
        val file = File(dir, "network/server-countries.json")
        store(FakeResolver(mapOf("fast.example.com" to listOf("95.216.1.1"))), file = file)
            .refresh(setOf("fast.example.com"))

        val newer = FakeGeo(mapOf("95.216.1.1" to "SE"), databaseId = "zgc1-202611")
        val resolver = FakeResolver(emptyMap())
        val restarted = store(resolver, lookup = newer, file = file)
        restarted.refresh(setOf("fast.example.com"))
        assertTrue(resolver.calls.isEmpty())
        assertEquals(mapOf("fast.example.com" to "SE"), restarted.countries.value)
    }

    @Test
    fun `an unreadable table records nothing`() = runBlocking {
        val resolver = FakeResolver(mapOf("fast.example.com" to listOf("95.216.1.1")))
        val store = store(resolver, lookup = FakeGeo(emptyMap(), databaseId = "unavailable"))
        store.refresh(setOf("fast.example.com", "185.12.34.56"))
        assertTrue(resolver.calls.isEmpty())
        assertTrue(store.countries.value.isEmpty())
        assertFalse(File(dir, "network/server-countries.json").exists())
    }

    @Test
    fun `most common country of several addresses wins and private ones are ignored`() = runBlocking {
        val resolver = FakeResolver(
            mapOf("multi.example.com" to listOf("10.0.0.1", "46.4.1.1", "95.216.1.1", "46.4.1.1")),
        )
        val store = store(resolver)
        store.refresh(setOf("multi.example.com"))
        assertEquals("DE", store.countries.value["multi.example.com"])
        assertNull(store.countries.value["10.0.0.1"])
    }

    @Test
    fun `request is asynchronous and conflates repeated calls`() = runBlocking {
        val resolver = FakeResolver(mapOf("fast.example.com" to listOf("95.216.1.1")))
        val store = store(resolver)
        repeat(5) { store.request(setOf("fast.example.com", "185.12.34.56")) }
        val deadline = System.currentTimeMillis() + 5_000
        while (store.countries.value.size < 2 && System.currentTimeMillis() < deadline) delay(10)
        assertEquals(mapOf("fast.example.com" to "FI", "185.12.34.56" to "NL"), store.countries.value)
        assertEquals(1, resolver.calls.size)
    }
}
