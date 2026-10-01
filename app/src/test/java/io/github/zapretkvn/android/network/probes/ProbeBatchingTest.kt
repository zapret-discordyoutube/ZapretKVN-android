package io.github.zapretkvn.android.network.probes

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeBatchingTest {
    @Test
    fun `results arriving together are published as one batch`() = runBlocking {
        val results = Channel<Map<String, Int>>(Channel.UNLIMITED)
        repeat(25) { results.trySend(mapOf("s$it" to it)) }
        results.close()
        val batches = mutableListOf<Map<String, Int>>()

        withTimeout(10_000) { results.collectBatched(windowMillis = 200) { batches += it } }

        assertEquals(1, batches.size)
        assertEquals(25, batches.single().size)
    }

    @Test
    fun `a batch is published within the window without waiting for the slow tail`() = runBlocking {
        // Раньше Relay публиковался пачками по 10: последние результаты висели,
        // пока не ответит самый медленный сервер.
        val results = Channel<Map<String, Int>>(Channel.UNLIMITED)
        val publishedAt = mutableListOf<Long>()
        val batches = mutableListOf<Map<String, Int>>()
        val started = System.currentTimeMillis()
        val producer = launch {
            results.send(mapOf("fast" to 1))
            delay(1_200)
            results.send(mapOf("slow" to 2))
            results.close()
        }

        withTimeout(10_000) {
            results.collectBatched(windowMillis = 100) {
                batches += it
                publishedAt += System.currentTimeMillis() - started
            }
        }
        producer.join()

        assertEquals(listOf(mapOf("fast" to 1), mapOf("slow" to 2)), batches)
        assertTrue("first batch waited for the slow one: ${publishedAt.first()} ms", publishedAt.first() < 900)
    }

    @Test
    fun `no result is lost when it arrives right as the window closes`() = runBlocking {
        // withTimeout вокруг receive терял элемент, вынутый из канала в момент
        // таймаута: такой сервер оставался «Проверяется» навсегда.
        val total = 3_000
        val results = Channel<Map<Int, Int>>(Channel.UNLIMITED)
        val seen = HashSet<Int>()
        val producer = launch(kotlinx.coroutines.Dispatchers.Default) {
            repeat(total) {
                results.send(mapOf(it to it))
                if (it % 7 == 0) delay(1)
            }
            results.close()
        }

        withTimeout(60_000) { results.collectBatched(windowMillis = 1) { seen += it.keys } }
        producer.join()

        assertEquals(total, seen.size)
    }

    @Test
    fun `closing the channel publishes what is left and stops`() = runBlocking {
        val results = Channel<Map<String, Int>>(Channel.UNLIMITED)
        results.close()
        var published = 0

        withTimeout(10_000) { results.collectBatched(windowMillis = 50) { published++ } }

        assertEquals(0, published)
    }

    @Test
    fun `servers sharing a host are probed once`() {
        val targets = listOf(
            ServerPingTarget("a", "vpn.example.com"),
            ServerPingTarget("b", "VPN.example.com"),
            ServerPingTarget("c", "other.example.com"),
            ServerPingTarget("d", "vpn.example.com "),
        )

        val groups = targets.groupedByHost(shuffle = { it })

        assertEquals(
            listOf(listOf("a", "b", "d"), listOf("c")),
            groups.map { group -> group.map(ServerPingTarget::outboundTag) },
        )
    }

    @Test
    fun `probe order does not follow the list order`() {
        val targets = List(40) { ServerPingTarget("s$it", "host$it.example.com") }

        val orders = List(5) { targets.groupedByHost().map { it.single().outboundTag } }

        assertTrue(orders.all { it.sorted() == targets.map(ServerPingTarget::outboundTag).sorted() })
        assertTrue(orders.any { it != targets.map(ServerPingTarget::outboundTag) })
    }
}
