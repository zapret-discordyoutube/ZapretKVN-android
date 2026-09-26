package io.github.zapretkvn.android.network.probes

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpsProbeVerdictTest {
    @Test
    fun `failure kinds follow the cause chain`() {
        assertEquals(HttpsProbeFailureKind.Timeout, HttpsProbeFailureKind.of(SocketTimeoutException("Read timed out")))
        assertEquals(
            HttpsProbeFailureKind.Timeout,
            HttpsProbeFailureKind.of(IOException("wrapped", SocketTimeoutException())),
        )
        assertEquals(HttpsProbeFailureKind.Tls, HttpsProbeFailureKind.of(SSLHandshakeException("Trust anchor")))
        assertEquals(HttpsProbeFailureKind.Dns, HttpsProbeFailureKind.of(UnknownHostException("cp")))
        assertEquals(HttpsProbeFailureKind.Reset, HttpsProbeFailureKind.of(ConnectException("refused")))
        assertEquals(HttpsProbeFailureKind.Reset, HttpsProbeFailureKind.of(SocketException("Connection reset")))
        assertEquals(HttpsProbeFailureKind.HttpStatus, HttpsProbeFailureKind.of(IOException("HTTP 503")))
        assertEquals(HttpsProbeFailureKind.Other, HttpsProbeFailureKind.of(SocketException("Socket closed")))
    }

    @Test
    fun `verdict is the common kind or mixed`() {
        val t = HttpsProbeFailureKind.Timeout
        assertEquals(HttpsProbeVerdict.Timeout, HttpsProbeVerdict.of(listOf(t, t, t, t)))
        assertEquals(HttpsProbeVerdict.Mixed, HttpsProbeVerdict.of(listOf(t, HttpsProbeFailureKind.Tls)))
        assertEquals(HttpsProbeVerdict.Mixed, HttpsProbeVerdict.of(emptyList()))
    }

    @Test
    fun `parse reads only a leading verdict token`() {
        assertEquals(HttpsProbeVerdict.Tls, HttpsProbeVerdict.parse("verdict=tls cloudflare:tls@12"))
        assertEquals(HttpsProbeVerdict.Timeout, HttpsProbeVerdict.parse("verdict=timeout"))
        assertNull(HttpsProbeVerdict.parse("cloudflare:Read timed out"))
        assertNull(HttpsProbeVerdict.parse("x verdict=tls"))
        assertNull(HttpsProbeVerdict.parse("verdict=bogus"))
        assertNull(HttpsProbeVerdict.parse(null))
    }
}
