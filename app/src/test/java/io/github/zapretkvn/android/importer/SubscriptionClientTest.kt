package io.github.zapretkvn.android.importer

import com.sun.net.httpserver.HttpServer
import io.github.zapretkvn.android.profiles.AtomicProfileWriter
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `manual fetch follows bounded relative redirect`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/from") { exchange ->
            exchange.responseHeaders.add("Location", "/subscription")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/subscription") { exchange ->
            val body = VALID_JSON.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/from"
            assertEquals(VALID_JSON, HttpSubscriptionFetcher().fetch(url))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `invalid non-http source is rejected before network`() {
        assertThrows(ImportException::class.java) {
            HttpSubscriptionFetcher.validatedUrl("file:///tmp/profile.json")
        }
    }

    @Test
    fun `subscription fragment is removed before fetch and persistence`() {
        assertEquals(
            "https://sub.example/profile?token=secret",
            HttpSubscriptionFetcher.validatedUrl(
                "https://sub.example/profile?token=secret#client-label",
            ),
        )
    }

    @Test
    fun `refresh url is outside profile index and removable`() = runBlocking {
        val root = temporaryFolder.newFolder("subscriptions")
        val store = SubscriptionSourceStore(root, JvmWriter())
        val profileId = "0123456789abcdef0123456789abcdef"
        val url = "https://sub.example/profile?token=secret"

        store.put(profileId, url)

        assertEquals(url, store.get(profileId))
        assertEquals(setOf(profileId), store.ids())
        assertFalse(File(root, "profiles/index.json").exists())
        store.retain(emptySet())
        assertEquals(emptySet<String>(), store.ids())
        store.put(profileId, url)
        store.remove(profileId)
        assertEquals(null, store.get(profileId))
    }

    private class JvmWriter : AtomicProfileWriter {
        override fun writeAtomic(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val temporary = File(target.path + ".new")
            temporary.writeBytes(bytes)
            check(temporary.renameTo(target) || run {
                target.delete()
                temporary.renameTo(target)
            })
        }

        override fun writeProfile(target: File, bytes: ByteArray) = writeAtomic(target, bytes)

        override fun rollbackProfile(target: File): Boolean = false
    }

    private companion object {
        const val VALID_JSON = """{"outbounds":[{"type":"direct","tag":"direct"}]}"""
    }
}
