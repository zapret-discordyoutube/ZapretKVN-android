package io.github.zapretkvn.android.config

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The WARP account delivered in the subscription header (owner 2026-11-27, `windows/docs/WARP_ACCOUNT_DELIVERY.md`).
 *
 * The payload is a REAL one (`app/src/test/resources/real_warp_delivery_payload.json`; not in `testdata/`: its fixture set is fixed by verify-fixtures.sh): the service's provisioning script registered two
 * devices at the real Cloudflare API, the app code wrote the files from it and the real core accepted both (WireGuard
 * handshake and MASQUE over HTTP/2, `warp=on` on Cloudflare's trace page); the devices were deleted afterwards, so the keys
 * in the fixture open nothing and only keep the real shape of the data.
 */
class WarpAccountDeliveryTest {
    private lateinit var directory: File
    private lateinit var payload: JsonObject

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("kvn-delivery").toFile()
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("real_warp_delivery_payload.json")).use { String(it.readBytes()) }
        payload = Json.parseToJsonElement(text).jsonObject
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun header(document: JsonObject = payload) =
        Base64.getEncoder().encodeToString(Json.encodeToString(JsonObject.serializer(), document).toByteArray())

    private fun account() = File(directory, WarpAccountDelivery.ACCOUNT_FILE)

    private fun json(file: File) = Json.parseToJsonElement(file.readText()).jsonObject

    @Test
    fun `the real payload makes both account files the app accepts`() {
        val outcome = WarpAccountDelivery.apply(header(), directory)
        assertEquals("written", outcome.status)
        assertTrue(outcome.wroteWireGuard && outcome.wroteMasque)
        assertTrue(WarpAccountRegistration.accountFileReady(account()))
        assertTrue(WarpMasqueAccount.ready(WarpMasqueAccount.fileFor(account())))
        assertEquals(payload.getValue("wireguard"), json(account()))
        assertEquals(payload.getValue("masque"), json(WarpMasqueAccount.fileFor(account())))
    }

    @Test
    fun `no header or an empty one does nothing`() {
        assertEquals("none", WarpAccountDelivery.apply(null, directory).status)
        assertEquals("none", WarpAccountDelivery.apply("  ", directory).status)
        assertFalse(account().exists())
    }

    @Test
    fun `a working account on the device is never replaced`() {
        WarpAccountDelivery.apply(header(), directory)
        val own = JsonObject(json(account()) + ("private_key" to JsonPrimitive("device-own-key")))
        account().writeText(own.toString())
        val other = JsonObject(payload + ("wireguard" to JsonObject(payload.getValue("wireguard").jsonObject + ("private_key" to JsonPrimitive("another-key")))))
        assertEquals("kept", WarpAccountDelivery.apply(header(other), directory).status)
        assertEquals("device-own-key", json(account()).getValue("private_key").jsonPrimitive.content)
    }

    @Test
    fun `only the missing half is filled`() {
        WarpAccountDelivery.apply(header(), directory)
        WarpMasqueAccount.fileFor(account()).delete()
        val outcome = WarpAccountDelivery.apply(header(), directory)
        assertEquals(Triple("written", false, true), Triple(outcome.status, outcome.wroteWireGuard, outcome.wroteMasque))
    }

    @Test
    fun `replace overwrites once per distinct payload`() {
        WarpAccountDelivery.apply(header(), directory)
        val newer = JsonObject(
            payload + ("replace" to JsonPrimitive(true)) +
                ("wireguard" to JsonObject(payload.getValue("wireguard").jsonObject + ("private_key" to JsonPrimitive("replacement-key")))),
        )
        assertEquals("written", WarpAccountDelivery.apply(header(newer), directory).status)
        assertEquals("replacement-key", json(account()).getValue("private_key").jsonPrimitive.content)
        account().writeText(JsonObject(json(account()) + ("private_key" to JsonPrimitive("later"))).toString())
        assertEquals("kept", WarpAccountDelivery.apply(header(newer), directory).status)
        assertEquals("later", json(account()).getValue("private_key").jsonPrimitive.content)
    }

    @Test
    fun `garbage is refused and writes nothing`() {
        val values = listOf(
            "%%%not base64%%%",
            Base64.getEncoder().encodeToString("not json".toByteArray()),
            Base64.getEncoder().encodeToString("""{"v":2}""".toByteArray()),
            header(JsonObject(payload + ("wireguard" to JsonObject(mapOf("private_key" to JsonPrimitive("x")))))),
            header(JsonObject(payload + ("masque" to JsonObject(mapOf("private_key" to JsonPrimitive("x")))))),
            "A".repeat(33 * 1024),
        )
        for (value in values) assertEquals(value.take(20), "invalid", WarpAccountDelivery.apply(value, directory).status)
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `an edge remembered for the old account is forgotten with it`() {
        File(account().path + ".edge").writeText("162.159.192.1:2408")
        WarpAccountDelivery.apply(header(), directory)
        assertFalse(File(account().path + ".edge").exists())
    }
}
