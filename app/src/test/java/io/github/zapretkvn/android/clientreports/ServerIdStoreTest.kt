package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.clientreports.ServerRef
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ServerIdStoreTest {
    private val dir: File = Files.createTempDirectory("server-ids").toFile()
    private val file = File(dir, "server-ids.json")

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun header(json: String): String {
        val deflater = java.util.zip.Deflater()
        deflater.setInput(json.toByteArray())
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    @Test
    fun headerNamesBecomeServerTagsAndSurviveRestart() {
        val store = ServerIdStore(file)
        store.update(
            "https://sub.example/vless/token",
            serviceReply = true,
            headerValue = header("""{"🇳🇱 Velnor  [main]": {"server_id": "hipnl", "product": "vless"}}"""),
        )
        store.update(
            "https://sub.example/hy2/token",
            serviceReply = true,
            headerValue = header("""{"Hiphopgen": {"server_id": "hiphopgen", "product": "hysteria2"}}"""),
        )
        val reloaded = ServerIdStore(file)
        // The tag is built from the name exactly like the importer does (brackets become parentheses).
        assertEquals(ServerRef("hipnl", "vless"), reloaded.lookup("🇳🇱 Velnor (main)", "vless"))
        assertEquals(ServerRef("hiphopgen", "hysteria2"), reloaded.lookup("Hiphopgen", "hysteria2"))
        assertNull(reloaded.lookup("Hiphopgen", "vless"))
        assertNull(reloaded.lookup("Unknown", "vless"))
        // A repeated name got the tag «name · 2»; the header names it once, all its lines are one server.
        assertEquals(ServerRef("hiphopgen", "hysteria2"), reloaded.lookup("Hiphopgen · 2", "hysteria2"))
        // Trojan lines of the VLESS product subscription are product vless.
        assertEquals(ServerRef("hipnl", "vless"), reloaded.lookup("🇳🇱 Velnor (main)", "trojan"))
    }

    @Test
    fun replyWithoutHeaderOrFromAnotherProviderForgetsTheMap() {
        val store = ServerIdStore(file)
        val url = "https://sub.example/vless/token"
        store.update(url, true, header("""{"Velnor": {"server_id": "hipnl", "product": "vless"}}"""))
        assertEquals(1, store.mappedServers())
        store.update(url, serviceReply = false, headerValue = header("""{"Velnor": {"server_id": "hipnl", "product": "vless"}}"""))
        assertNull(store.lookup("Velnor", "vless"))
        store.update(url, true, header("""{"Velnor": {"server_id": "hipnl", "product": "vless"}}"""))
        store.update(url, true, null)
        assertEquals(0, ServerIdStore(file).mappedServers())
    }

    @Test
    fun namesThatCollideOnOneTagAreNotReported() {
        val store = ServerIdStore(file)
        store.update(
            "https://sub.example/vless/token",
            true,
            header("""{"Velnor [a]": {"server_id": "one", "product": "vless"}, "Velnor (a)": {"server_id": "two", "product": "vless"}}"""),
        )
        assertNull(store.lookup("Velnor (a)", "vless"))
    }

    @Test
    fun serviceSubscriptionWithoutIdsIsFetchedOnceForTheHeader() {
        // The owner's archive 2026-11-19: subscriptions fetched before the header existed kept no ids, so no report
        // could name a server; one fetch (body dropped) teaches the store, a second run does nothing.
        val store = ServerIdStore(file)
        val known = "https://sub.example/vless/token"
        val missing = "https://sub.example/hy2/token"
        store.update(known, serviceReply = true, headerValue = header("""{"Velnor": {"server_id": "hipnl", "product": "vless"}}"""))
        org.junit.Assert.assertTrue(store.knows(known))
        org.junit.Assert.assertFalse(store.knows(missing))
        val fetched = mutableListOf<String>()
        val backfill = ServerIdBackfill(
            serviceSources = { listOf(io.github.zapretkvn.android.importer.SubscriptionSource(known), io.github.zapretkvn.android.importer.SubscriptionSource(missing)) },
            knows = store::knows,
            fetch = { source ->
                fetched += source.url
                store.update(source.url, serviceReply = true, headerValue = header("""{"Velnor": {"server_id": "hipnl", "product": "hysteria2"}}"""))
            },
        )
        kotlinx.coroutines.runBlocking { backfill.runOnce(); backfill.runOnce() }
        assertEquals(listOf(missing), fetched)
        org.junit.Assert.assertTrue(store.knows(missing))
        // A failed fetch never breaks the start of the app.
        val failing = ServerIdBackfill({ listOf(io.github.zapretkvn.android.importer.SubscriptionSource("https://x.example/s")) }, { false }, { error("offline") })
        kotlinx.coroutines.runBlocking { failing.runOnce() }
    }

    @Test
    fun relayLineKeepsItsViaThroughTheStore() {
        // vpnbot 2026-11-19: a «… через Россию» line is {"relay_of", "product", "via"} with no server_id, so a parser
        // that does not know via skips it; the key must reach the report, or a relay failure would count against the
        // target itself.
        val store = ServerIdStore(file)
        val url = "https://sub.example/hy2/token"
        store.update(
            url,
            serviceReply = true,
            headerValue = header(
                """{"Velnor через Россию": {"relay_of": "hipnl", "product": "hysteria2", "via": "3f9a0c21be"},
                   "Velnor": {"server_id": "hipnl", "product": "hysteria2"},
                   "Broken": {"relay_of": "hipnl", "product": "hysteria2", "via": "RU-NODE-1"},
                   "No key": {"relay_of": "hipnl", "product": "hysteria2"},
                   "Interim": {"server_id": "hipnl", "product": "hysteria2", "via": "3f9a0c21be"},
                   "Both ids": {"server_id": "hipnl", "relay_of": "hipnl", "product": "hysteria2"}}""",
            ),
        )
        val restarted = ServerIdStore(file)
        fun tag(name: String) = io.github.zapretkvn.android.profiles.ManagedProfileFactory.serverTag(name)
        assertEquals(ServerRef("hipnl", "hysteria2", "3f9a0c21be"), restarted.lookup(tag("Velnor через Россию"), "hysteria2"))
        assertEquals(ServerRef("hipnl", "hysteria2"), restarted.lookup(tag("Velnor"), "hysteria2"))
        // A relay line that cannot be told apart from the target is never reported as the target.
        for (name in listOf("Broken", "No key", "Interim", "Both ids")) assertNull(name, restarted.lookup(tag(name), "hysteria2"))
    }

    @Test
    fun mapOfTheOldFormatIsDroppedAndLearnedAgain() {
        // 0.6.39 ignored via: its map may list a «через Россию» line as the target, so it is never read.
        val legacy = File(dir, "old-server-ids.json")
        val url = "https://sub.example/hy2/token"
        ServerIdStore(legacy).update(
            url,
            serviceReply = true,
            headerValue = header("""{"Velnor через Россию": {"server_id": "hipnl", "product": "hysteria2"}}"""),
        )
        val store = ServerIdStore(file, legacy = legacy)
        assertFalse(store.knows(url))
        assertFalse(legacy.exists())
        assertNull(store.lookup(io.github.zapretkvn.android.profiles.ManagedProfileFactory.serverTag("Velnor через Россию"), "hysteria2"))
    }
}
