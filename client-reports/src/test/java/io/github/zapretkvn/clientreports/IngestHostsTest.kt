package io.github.zapretkvn.clientreports

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestHostsTest {
    private val now = 1_795_000_000_000L

    /** targets.json of zapret-links (gist / Apps Script ?format=json) as published on 2026-11-19. */
    private val entryPage = """
        {"schema": 1,
         "domains": ["zapret.moe", "fuckingspeedtest.com", "osjcpjzipyb.click"],
         "families": {"git": {"name": "Zapret Git", "hosts": ["vaavlvfila.fuckingspeedtest.com"]}},
         "services": {"reports": {"hosts": ["oeifnxndqq.fuckingspeedtest.com", "wkjjjdcycb.osjcpjzipyb.click"]}}}
    """.trimIndent()

    @Test
    fun feedGivesTheReportNamesOnlyUnderItsOwnDomains() {
        assertEquals(listOf("oeifnxndqq.fuckingspeedtest.com", "wkjjjdcycb.osjcpjzipyb.click"), IngestHosts.parseFeed(entryPage))
        val hostile = """{"domains": ["fuckingspeedtest.com"], "services": {"reports": {"hosts":
            ["ok.fuckingspeedtest.com", "evil.example.org", "fuckingspeedtest.com.evil.org", "Bad Name", 7, "OK.FUCKINGSPEEDTEST.COM"]}}}"""
        assertEquals(listOf("ok.fuckingspeedtest.com"), IngestHosts.parseFeed(hostile))
        // Without `domains` only the seed's domains are trusted.
        assertEquals(
            listOf("aaaaaaaaaa.osjcpjzipyb.click"),
            IngestHosts.parseFeed("""{"services": {"reports": {"hosts": ["aaaaaaaaaa.osjcpjzipyb.click", "x.other.top"]}}}"""),
        )
        // The old entry page (no services) and junk give nothing.
        assertTrue(IngestHosts.parseFeed("""{"schema": 1, "families": {}}""").isEmpty())
        assertTrue(IngestHosts.parseFeed("<html>").isEmpty())
    }

    @Test
    fun orderSpreadsOverDomainsKeepsTheCutNameLastAndFailedNamesAtTheEnd() {
        val learned = listOf("aaaaaaaaaa.fuckingspeedtest.com", "bbbbbbbbbb.fuckingspeedtest.com", "cccccccccc.osjcpjzipyb.click")
        repeat(20) { seed ->
            val order = IngestHosts.order(learned, emptySet(), Random(seed))
            assertEquals((learned + IngestHosts.SEED).distinct().toSet(), order.toSet())
            assertEquals(IngestHosts.FIXED, order.last())
            // The first two tries hit two different domains.
            assertTrue(IngestHosts.domainOf(order[0]) != IngestHosts.domainOf(order[1]))
        }
        val failed = setOf("oeifnxndqq.fuckingspeedtest.com", IngestHosts.FIXED)
        val order = IngestHosts.order(emptyList(), failed, Random(1))
        assertEquals(listOf("wkjjjdcycb.osjcpjzipyb.click"), order.take(1))
        assertEquals(failed, order.drop(1).toSet())
        // Different batches start at different names.
        val starts = (0 until 20).map { IngestHosts.order(learned, emptySet(), Random(it)).first() }.toSet()
        assertTrue(starts.size > 1)
    }

    @Test
    fun batchStaysUnderTheByteCeiling() {
        val records = List(50) { it }
        val batch = IngestHosts.takeBatch(records, 50, IngestHosts.MAX_BODY_BYTES) { it.size * 400 }
        assertEquals(20, batch.size)
        assertEquals(listOf(0, 1, 2), IngestHosts.takeBatch(records, 3, IngestHosts.MAX_BODY_BYTES) { it.size * 10 })
        // One oversized record still goes alone instead of blocking the queue.
        assertEquals(listOf(0), IngestHosts.takeBatch(records, 50, 100) { it.size * 1_000 })
        assertTrue(IngestHosts.takeBatch(emptyList<Int>(), 50, 100) { 0 }.isEmpty())
    }

    @Test
    fun realBatchOfTwentyReportsFitsTheCeiling() {
        val app = AppIdentity("zapret-kvn", "0.6.34", "android")
        val record = AttemptRecord(
            "4stok", "vless", ReportOutcome.Failed, "handshake", "handshake_timeout", 15_000, now,
            "v1." + "a".repeat(120) + "." + "b".repeat(43),
        )
        val body = { list: List<AttemptRecord> -> ClientReportJson.batch("0123456789abcdef0123456789abcdef", app, list, "tunnel").toByteArray().size }
        val batch = IngestHosts.takeBatch(List(50) { record }, ClientReportContract.MAX_BATCH, IngestHosts.MAX_BODY_BYTES, body)
        assertTrue(batch.size in 10..40)
        assertTrue(body(batch) <= IngestHosts.MAX_BODY_BYTES)
    }

    @Test
    fun cacheAsksTheFeedDailyOrWhenNothingAnsweredButAtMostHourly() {
        val dir = Files.createTempDirectory("ingest").toFile()
        try {
            val file = File(dir, "ingest-hosts")
            val cache = IngestHostCache(file)
            assertTrue(cache.feedDue(now, nothingAnswered = false))
            cache.noteFeedAttempt(now)
            assertFalse(cache.feedDue(now + 60_000, nothingAnswered = true))
            cache.store(IngestHosts.parseFeed(entryPage), now)
            val reloaded = IngestHostCache(file)
            assertEquals(listOf("oeifnxndqq.fuckingspeedtest.com", "wkjjjdcycb.osjcpjzipyb.click"), reloaded.hosts())
            assertFalse(reloaded.feedDue(now + 3_600_000, nothingAnswered = false))
            assertTrue(reloaded.feedDue(now + 3_600_000, nothingAnswered = true))
            assertTrue(reloaded.feedDue(now + IngestHostCache.MAX_AGE_MILLIS, nothingAnswered = false))
            cache.store(emptyList(), now + 1)
            assertEquals(2, IngestHostCache(file).hosts().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
