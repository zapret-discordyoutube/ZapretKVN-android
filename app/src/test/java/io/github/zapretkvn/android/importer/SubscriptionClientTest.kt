package io.github.zapretkvn.android.importer

import com.sun.net.httpserver.HttpServer
import io.github.zapretkvn.android.profiles.AtomicProfileWriter
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
            assertEquals(VALID_JSON, HttpSubscriptionFetcher().fetch(SubscriptionSource(url)))
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

        store.put(profileId, SubscriptionSource(url))

        assertEquals(url, store.get(profileId)?.url)
        assertEquals(setOf(profileId), store.ids())
        assertFalse(File(root, "profiles/index.json").exists())
        store.retain(emptySet())
        assertEquals(emptySet<String>(), store.ids())
        store.put(profileId, SubscriptionSource(url))
        store.remove(profileId)
        assertEquals(null, store.get(profileId))
    }

    @Test
    fun `identity headers reach the subscription server`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seen: Map<String, String> = emptyMap()
        server.createContext("/subscription") { exchange ->
            seen = exchange.requestHeaders.entries.associate { (name, values) ->
                name.lowercase() to values.first()
            }
            val body = VALID_JSON.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val source = SubscriptionSource(
                url = "http://127.0.0.1:${server.address.port}/subscription",
                clientProfile = SubscriptionClientProfile.Happ,
                sendHwid = true,
                hwid = "device-42",
            )

            val fetched = HttpSubscriptionFetcher(
                device = DeviceIdentity("Android", "14", "Pixel 8", "ru-RU"),
                appVersion = "0.2.18",
            ).fetch(source)

            assertEquals(VALID_JSON, fetched)
            assertEquals("device-42", seen["x-hwid"])
            assertEquals("Android", seen["x-device-os"])
            assertEquals("14", seen["x-ver-os"])
            assertEquals("Pixel 8", seen["x-device-model"])
            assertEquals("Happ/3.13.0", seen["user-agent"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `device limit response is explained instead of raw status`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/subscription") { exchange ->
            exchange.responseHeaders.add("X-HWID-Max-Devices-Reached", "3")
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val source = SubscriptionSource(
                url = "http://127.0.0.1:${server.address.port}/subscription",
                sendHwid = true,
                hwid = "device-42",
            )

            val error = assertThrows(ImportException::class.java) {
                HttpSubscriptionFetcher().fetch(source)
            }

            assertTrue(error.message.orEmpty().contains("лимит устройств"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `identity settings survive a store round trip`() = runBlocking {
        val root = temporaryFolder.newFolder("identity-subscriptions")
        val store = SubscriptionSourceStore(root, JvmWriter())
        val profileId = "0123456789abcdef0123456789abcdef"
        val source = SubscriptionSource(
            url = "https://sub.example/profile?token=secret",
            clientProfile = SubscriptionClientProfile.Incy,
            sendHwid = true,
            hwid = "device-42",
        )

        store.put(profileId, source)

        assertEquals(source, store.get(profileId))
    }

    /** Записи, созданные до появления настроек идентификации, читаются как обычный URL. */
    @Test
    fun `legacy string entries keep working`() = runBlocking {
        val root = temporaryFolder.newFolder("legacy-subscriptions")
        val profileId = "0123456789abcdef0123456789abcdef"
        File(root, "index.json").apply {
            parentFile?.mkdirs()
            writeText("""{"$profileId":"https://sub.example/profile"}""")
        }
        val store = SubscriptionSourceStore(root, JvmWriter())

        val restored = store.get(profileId)

        assertEquals("https://sub.example/profile", restored?.url)
        assertEquals(SubscriptionClientProfile.Zapret, restored?.clientProfile)
        assertFalse(restored?.sendHwid ?: true)

        // Первая же запись переводит хранилище в новый формат без потери URL.
        store.put(profileId, checkNotNull(restored))
        assertEquals("https://sub.example/profile", store.get(profileId)?.url)
    }

    @Test
    fun `hwid is not persisted when sending is disabled`() = runBlocking {
        val root = temporaryFolder.newFolder("disabled-hwid-subscriptions")
        val store = SubscriptionSourceStore(root, JvmWriter())
        val profileId = "0123456789abcdef0123456789abcdef"

        store.put(
            profileId,
            SubscriptionSource(
                url = "https://sub.example/profile",
                sendHwid = false,
                hwid = "device-42",
            ),
        )

        assertEquals("", store.get(profileId)?.hwid)
        assertFalse(File(root, "index.json").readText().contains("device-42"))
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
