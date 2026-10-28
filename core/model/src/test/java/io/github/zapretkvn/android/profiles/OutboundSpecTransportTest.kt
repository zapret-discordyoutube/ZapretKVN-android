package io.github.zapretkvn.android.profiles

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Trojan/VMess/VLESS поверх WebSocket: «подключается, но трафика нет» (проверено вживую на Windows). */
class OutboundSpecTransportTest {
    private fun trojan(alpn: List<String>, transport: TransportSettings): JsonObject =
        OutboundSpec.Trojan(
            server = "h.example",
            serverPort = 443,
            password = "pw",
            tls = TlsSettings(enabled = true, serverName = "h.example", alpn = alpn),
            transport = transport,
        ).toSingBoxJson()

    @Test
    fun `websocket keeps only http1 alpn`() {
        val json = trojan(listOf("h2", "http/1.1"), TransportSettings(type = "ws", path = "/ws"))
        val alpn = json["tls"]!!.jsonObject["alpn"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("http/1.1"), alpn)
    }

    @Test
    fun `websocket with only h2 lets the core choose http1`() {
        val json = trojan(listOf("h2"), TransportSettings(type = "ws", path = "/ws"))
        assertFalse(json["tls"]!!.jsonObject.containsKey("alpn"))
    }

    @Test
    fun `grpc keeps h2`() {
        val json = trojan(listOf("h2"), TransportSettings(type = "grpc", serviceName = "g"))
        val alpn = json["tls"]!!.jsonObject["alpn"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("h2"), alpn)
    }

    @Test
    fun `early data moves from the websocket path to options`() {
        val transport = trojan(emptyList(), TransportSettings(type = "ws", path = "/ws?ed=2048&x=1"))["transport"]!!.jsonObject
        assertEquals("/ws?x=1", transport["path"]!!.jsonPrimitive.content)
        assertEquals(2048, transport["max_early_data"]!!.jsonPrimitive.int)
        assertEquals("Sec-WebSocket-Protocol", transport["early_data_header_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `httpupgrade host goes to its own field`() {
        val transport = trojan(emptyList(), TransportSettings(type = "httpupgrade", path = "/up", host = "cdn.example"))["transport"]!!.jsonObject
        assertEquals("cdn.example", transport["host"]!!.jsonPrimitive.content)
        assertFalse(transport.containsKey("headers"))
    }
}
