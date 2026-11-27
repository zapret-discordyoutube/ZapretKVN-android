package io.github.zapretkvn.android.config

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The WARP edges the service sends in the subscription header `zapret-warp-edges` (owner 2026-11-27).
 *
 * The edges are real Cloudflare addresses: HTTP/2 MASQUE answers on 162.159.198.2-50 (checked against the real edge from the
 * host, and the patched core came up through 162.159.198.7 in 6 s with that very list), WireGuard on 188.114.96-99.x.
 */
class WarpEdgesTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("kvn-edges").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun header(json: String) = Base64.getEncoder().encodeToString(json.toByteArray())

    private val good = """{"v":1,"edges":[
        {"transport":"masque-http2","address":"162.159.198.7","sni":"www.cloudflare.com"},
        {"transport":"masque-quic","address":"2606:4700:103::2","port":443},
        {"transport":"wireguard","address":"188.114.97.5","port":2408}]}"""

    @Test
    fun `a good list is kept and read back as the objects the core reads`() {
        assertEquals("written", WarpEdges.apply(header(good), directory).status)
        val account = File(directory, WarpAccountDelivery.ACCOUNT_FILE)
        val edges = WarpEdges.load(account)
        assertEquals(3, edges.size)
        assertEquals("162.159.198.7", edges[0].getValue("address").jsonPrimitive.content)
        assertEquals("same", WarpEdges.apply(header(good), directory).status)
    }

    @Test
    fun `names, bad transports and bad names are dropped and the list is bounded`() {
        val many = (1..40).joinToString(",") { """{"transport":"wireguard","address":"188.114.96.$it"}""" }
        val text = """{"v":1,"edges":[
            {"transport":"wireguard","address":"engage.cloudflareclient.com"},
            {"transport":"carrier-pigeon","address":"1.2.3.4"},
            {"transport":"masque-http2","address":"162.159.198.9","sni":"bad name!"},
            {"transport":"wireguard","address":"1.2.3.4","sni":"x.example"},
            {"transport":"wireguard","address":"999.1.1.1"},
            $many]}"""
        val edges = WarpEdges.valid(Json.parseToJsonElement(text).jsonObject.getValue("edges") as JsonArray)
        assertEquals(WarpEdges.MAX_EDGES, edges.size)
        assertTrue(edges.all { it.getValue("address").jsonPrimitive.content.startsWith("188.114.96.") })
    }

    @Test
    fun `garbage writes nothing and an empty list removes the file`() {
        for (value in listOf("%%%", header("not json"), header("""{"v":2,"edges":[]}"""), header("""{"v":1}"""), "A".repeat(33 * 1024))) {
            assertEquals(value.take(10), "invalid", WarpEdges.apply(value, directory).status)
        }
        assertEquals(0, directory.listFiles().orEmpty().size)
        WarpEdges.apply(header(good), directory)
        assertEquals("written", WarpEdges.apply(header("""{"v":1,"edges":[]}"""), directory).status)
        assertFalse(WarpEdges.fileFor(File(directory, WarpAccountDelivery.ACCOUNT_FILE)).exists())
        assertEquals("none", WarpEdges.apply(null, directory).status)
    }

    @Test
    fun `a broken file is no list at all`() {
        val account = File(directory, WarpAccountDelivery.ACCOUNT_FILE)
        WarpEdges.fileFor(account).writeText("{broken")
        assertEquals(emptyList<Any>(), WarpEdges.load(account))
    }
}
