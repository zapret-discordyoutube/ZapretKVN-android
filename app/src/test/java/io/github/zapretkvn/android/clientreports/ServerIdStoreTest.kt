package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.clientreports.ServerRef
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerIdStoreTest {
    private val dir: File = Files.createTempDirectory("server-ids").toFile()
    private val file = File(dir, "server-ids.json")

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun header(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

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
}
