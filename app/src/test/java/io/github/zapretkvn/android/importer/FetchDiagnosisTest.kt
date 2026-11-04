package io.github.zapretkvn.android.importer

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchDiagnosisTest {
    private val ok = HostProbe(true)
    private fun fail(stage: String, how: String = "timeout", detail: String = "") =
        HostProbe(false, stage, how, listOf("dns" to 5), detail)
    private val host = "url.globalinetforall.example.com"

    private fun verdict(direct: Pair<HostProbe, HostProbe>, vpn: Pair<HostProbe, HostProbe>? = null) =
        FetchJudge.judge(Evidence(host, 443, direct, vpn))

    @Test
    fun `host is recognisable but never complete`() {
        assertEquals("url.gl***.ex***.com", FetchMasking.maskHost(host))
        assertEquals("203.0.*.*", FetchMasking.maskHost("203.0.113.9"))
        val tag = FetchMasking.hostTag(host, 443)
        assertFalse(tag.contains("globalinetforall"))
        assertTrue(Regex(""":443#[0-9a-f]{6}$""").containsMatchIn(tag))
        assertEquals(tag, FetchMasking.hostTag(host.uppercase(), 443))
        assertFalse(FetchMasking.redactHost("connect to $host failed", host).contains(host))
    }

    @Test
    fun `tls hang with vpn working is a certain block`() {
        val d = verdict(fail("tls") to ok, ok to ok)
        assertEquals(Culprit.Block, d.culprit)
        assertTrue(d.certain)
        assertTrue(d.where.contains("SNI"))
    }

    @Test
    fun `tls hang without vpn is a probable block with a hint`() {
        val d = verdict(fail("tls", "reset") to ok)
        assertEquals(Culprit.Block, d.culprit)
        assertFalse(d.certain)
        assertTrue(d.advice.contains("Через VPN не пробовали"))
        assertTrue(d.message().contains("(вероятно)"))
    }

    @Test
    fun `probable block with profiles says to connect and add the subscription again and keeps the note`() {
        val d = FetchJudge.judge(Evidence(host, 443, fail("tls") to ok, null, serversAvailable = true))
        assertTrue(d.advice.contains("Подключитесь к любому серверу"))
        assertTrue(d.advice.contains("подписка запомнена и загрузится через него сама"))
        assertTrue(d.advice.contains("Через VPN не пробовали"))
        assertFalse(d.advice.contains("Включите VPN"))
    }

    @Test
    fun `probable block without any profile asks for a single key and not for a vpn`() {
        val d = FetchJudge.judge(Evidence(host, 443, fail("tls") to ok, null, serversAvailable = false))
        assertEquals(Culprit.Block, d.culprit)
        assertFalse(d.certain)
        assertTrue(d.advice.contains("хотя бы один сервер"))
        assertTrue(d.advice.contains("одиночный ключ"))
        assertTrue(d.advice.contains("не подписку"))
        assertTrue(d.advice.contains("«Буфер»"))
        assertTrue(d.advice.contains("запомненная подписка загрузится через этот сервер сама"))
        assertFalse(d.advice.contains("Включите VPN"))
        assertFalse(d.advice.contains("Через VPN не пробовали"))
    }

    @Test
    fun `the server flag changes nothing once the vpn is connected or for other verdicts`() {
        for (flag in listOf(true, false)) {
            val blocked = FetchJudge.judge(Evidence(host, 443, fail("tls") to ok, ok to ok, serversAvailable = flag))
            assertEquals(Culprit.Block, blocked.culprit)
            assertTrue(blocked.certain)
            val server = FetchJudge.judge(Evidence(host, 443, fail("tcp") to ok, fail("tcp") to ok, serversAvailable = flag))
            assertEquals(Culprit.Server, server.culprit)
        }
    }

    @Test
    fun `a diagnosis without the vpn route marks the failure as not final`() {
        assertTrue(FetchJudge.judge(Evidence(host, 443, fail("tls") to ok, null)).vpnUntried)
        assertFalse(FetchJudge.judge(Evidence(host, 443, fail("tls") to ok, ok to ok)).vpnUntried)
        assertFalse(FetchJudge.judge(Evidence(host, 443, fail("tcp") to ok, fail("tcp") to ok)).vpnUntried)
    }

    @Test
    fun `evidence carries whether any profile exists`() {
        val prober = { _: android.net.Network?, _: String, _: Int -> ok }
        assertFalse(FetchProbes.collect(host, 443, null, null, prober, serversAvailable = false).serversAvailable)
        assertTrue(FetchProbes.collect(host, 443, null, null, prober).serversAvailable)
    }

    @Test
    fun `vpn up but control fails through it is the vpn`() {
        val broken = fail("tcp")
        val d = verdict(fail("tcp") to ok, broken to broken)
        assertEquals(Culprit.Vpn, d.culprit)
        assertTrue(d.certain)
    }

    @Test
    fun `everything down is the phone network`() {
        val d = verdict(fail("dns", "dns") to fail("dns", "dns"), fail("tcp") to fail("tcp"))
        assertEquals(Culprit.Network, d.culprit)
    }

    @Test
    fun `server down everywhere is the bot`() {
        val d = verdict(fail("tcp") to ok, fail("tcp") to ok)
        assertEquals(Culprit.Server, d.culprit)
        assertTrue(d.certain)
    }

    @Test
    fun `handshake ok but silent is a probable server stall`() {
        val d = verdict(ok to ok, ok to ok)
        assertEquals(Culprit.Server, d.culprit)
        assertFalse(d.certain)
        assertTrue(d.where.contains("ответа на запрос нет"))
    }

    @Test
    fun `log names the masked host and every probe but not the full address`() {
        val d = verdict(fail("tls") to ok, ok to ok)
        val joined = d.logLines.joinToString("\n")
        assertTrue(joined.contains(FetchMasking.hostTag(host, 443)))
        assertFalse(joined.contains("globalinetforall"))
        assertTrue(joined.contains("напрямую, сервер: сбой на tls"))
        assertTrue(joined.contains("через VPN, контроль: ok"))
        assertTrue(joined.contains("вердикт block"))
    }

    @Test
    fun `http codes map to culprits`() {
        assertEquals(Culprit.Refused, FetchJudge.blameForHttp(403).first)
        assertEquals(Culprit.Refused, FetchJudge.blameForHttp(200, "Достигнут лимит устройств подписки").first)
        assertEquals(Culprit.Server, FetchJudge.blameForHttp(404).first)
        assertTrue(FetchJudge.blameForHttp(404).second.contains("недействительна"))
        assertTrue(FetchJudge.blameForHttp(503).second.contains("сломан"))
        assertTrue(FetchJudge.answeredMessage(404, "HTTP 404").contains("Виноват: сервер подписки (бот)"))
    }

    @Test
    fun `exceptions are classified by stage cause`() {
        assertEquals("dns", FetchProbes.classify(UnknownHostException("x")))
        assertEquals("timeout", FetchProbes.classify(SocketTimeoutException("connect timed out")))
        assertEquals("refused", FetchProbes.classify(ConnectException("Connection refused")))
        assertEquals("reset", FetchProbes.classify(EOFException()))
        assertEquals("cert", FetchProbes.classify(SSLHandshakeException("x").apply { initCause(CertificateException("bad")) }))
        assertEquals("tls_error", FetchProbes.classify(SSLHandshakeException("handshake failure")))
    }

    @Test
    fun `evidence is collected for both routes and skips vpn when it is down`() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val prober = { _: android.net.Network?, probeHost: String, _: Int -> seen += probeHost; ok }
        val withoutVpn = FetchProbes.collect(host, 443, null, null, prober)
        assertEquals(null, withoutVpn.vpn)
        assertEquals(setOf(host, FetchJudge.CONTROL_HOST), seen.toSet())
    }
}
