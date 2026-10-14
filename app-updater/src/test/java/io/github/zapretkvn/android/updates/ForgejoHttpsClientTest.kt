package io.github.zapretkvn.android.updates

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgejoHttpsClientTest {
    private class BoundConnection(url: URL, private val body: String) : HttpsURLConnection(url) {
        val headers = mutableMapOf<String, String>()
        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun getResponseCode(): Int = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getContentLengthLong(): Long = body.length.toLong()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getCipherSuite(): String = ""
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test
    fun `direct route uses the connection bound to the physical network`() {
        val opened = mutableListOf<URL>()
        val client = ForgejoHttpsClient(
            direct = UpdateDirectConnections { url ->
                opened += url
                BoundConnection(url, "[]")
            },
            route = UpdateRoute.Direct,
        )

        assertEquals("[]", client.readText(URL_TEXT, maxBytes = 16))
        assertEquals(listOf(URL(URL_TEXT)), opened)
    }

    @Test
    fun `requests carry a neutral user agent and no compression`() {
        var connection: BoundConnection? = null
        val client = ForgejoHttpsClient(
            direct = UpdateDirectConnections { url -> BoundConnection(url, "[]").also { connection = it } },
        )
        client.readText(URL_TEXT, maxBytes = 16)

        val headers = connection!!.headers
        assertEquals(ForgejoHttpsClient.USER_AGENT, headers["User-Agent"])
        assertTrue("Zapret" !in headers.getValue("User-Agent"))
        assertEquals("identity", headers["Accept-Encoding"])
    }

    @Test
    fun `physical network failure is retryable through the vpn route`() {
        val client = ForgejoHttpsClient(
            direct = UpdateDirectConnections { throw IOException("bind failed: EPERM") },
        )

        val failure = assertThrows(UpdateException::class.java) { client.readText(URL_TEXT, maxBytes = 16) }
        assertTrue(failure.retryViaVpn)
    }

    @Test
    fun `content range is parsed strictly`() {
        assertEquals(Triple(100L, 199L, 1000L), ForgejoHttpsClient.parseContentRange("bytes 100-199/1000"))
        assertEquals(Triple(0L, 9L, -1L), ForgejoHttpsClient.parseContentRange("bytes 0-9/*"))
        assertNull(ForgejoHttpsClient.parseContentRange("bytes 9-0/10"))
        assertNull(ForgejoHttpsClient.parseContentRange("items 0-9/10"))
        assertNull(ForgejoHttpsClient.parseContentRange(null))
    }

    private companion object {
        const val URL_TEXT = "https://git.zapret.moe/api/v1/repos/zapretkvn/ZapretKVN-android/releases?limit=20"
    }
}
