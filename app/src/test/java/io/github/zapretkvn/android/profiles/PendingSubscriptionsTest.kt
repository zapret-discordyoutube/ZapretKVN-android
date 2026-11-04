package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.SubscriptionSource
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Подписки, не загрузившиеся без VPN: запоминаются, не плодятся, не растут без предела и переживают перезапуск. */
class PendingSubscriptionsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun queue(root: File, limit: Int = PendingSubscriptions.DEFAULT_LIMIT) =
        PendingSubscriptions(SubscriptionSourceStore(root, JvmAtomicProfileWriter()), limit)

    private fun source(name: String) = SubscriptionSource("https://sub.example/$name?token=secret-$name")

    @Test
    fun `a remembered subscription is listed with its source`() = runBlocking {
        val pending = queue(temporaryFolder.newFolder())

        assertTrue(pending.remember(source("a")))

        val all = pending.all()
        assertEquals(1, all.size)
        assertEquals(source("a").url, all.single().second.url)
        assertTrue(all.single().first.startsWith("pending-"))
    }

    @Test
    fun `the same link twice is one entry`() = runBlocking {
        val pending = queue(temporaryFolder.newFolder())

        assertTrue(pending.remember(source("a")))
        assertTrue(pending.remember(source("a")))

        assertEquals(1, pending.all().size)
    }

    @Test
    fun `different links keep a stable order`() = runBlocking {
        val pending = queue(temporaryFolder.newFolder())
        listOf("a", "b", "c").forEach { pending.remember(source(it)) }

        val first = pending.all().map { it.first }
        val second = pending.all().map { it.first }

        assertEquals(3, first.size)
        assertEquals(first, second)
        assertEquals(first.sorted(), first)
    }

    @Test
    fun `the queue does not grow past its limit but a known link is still replaced`() = runBlocking {
        val pending = queue(temporaryFolder.newFolder(), limit = 2)
        assertTrue(pending.remember(source("a")))
        assertTrue(pending.remember(source("b")))

        assertFalse(pending.remember(source("c")))
        assertTrue(pending.remember(source("a")))

        assertEquals(2, pending.all().size)
        assertFalse(pending.all().any { it.second.url == source("c").url })
    }

    @Test
    fun `forgetting removes only that entry`() = runBlocking {
        val pending = queue(temporaryFolder.newFolder())
        pending.remember(source("a"))
        pending.remember(source("b"))
        val keep = pending.all().first { it.second.url == source("b").url }.first
        val drop = pending.all().first { it.second.url == source("a").url }.first

        pending.forget(drop)

        assertEquals(listOf(keep), pending.all().map { it.first })
    }

    @Test
    fun `entries survive a restart`() = runBlocking {
        val root = temporaryFolder.newFolder()
        queue(root).remember(source("a"))

        val after = queue(root).all()

        assertEquals(listOf(source("a").url), after.map { it.second.url })
    }

    @Test
    fun `identity settings of the source are kept`() = runBlocking {
        val pending = queue(temporaryFolder.newFolder())
        val withHwid = SubscriptionSource("https://sub.example/x", sendHwid = true, hwid = "ABCDEF0123456789")

        pending.remember(withHwid)

        val stored = pending.all().single().second
        assertTrue(stored.sendHwid)
        assertEquals("ABCDEF0123456789", stored.hwid)
    }
}
