package io.github.zapretkvn.android.config

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Регистрация аккаунта WARP (владелец 2026-11-25). Без выдуманных данных: X25519 сверяется с векторами RFC 7748,
 * точка разреза берётся из настоящего ClientHello стека TLS, а обмен с API идёт по настоящей сети (по запросу,
 * `KVN_REAL_NETWORK=1`: API ограничивает частоту регистраций с одного адреса, в обычном прогоне это тест-флейки).
 */
class WarpAccountRegistrationTest {
    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun x25519MatchesTheRfc7748Vectors() {
        // RFC 7748 section 5.2, first vector
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val point = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val expected = hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")
        assertEquals(expected.toList(), WarpAccountRegistration.X25519.scalarMult(scalar, point).toList())
        // RFC 7748 section 6.1: Alice's public key from her private key
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        assertEquals(alicePublic.toList(), WarpAccountRegistration.X25519.publicKey(alicePrivate).toList())
    }

    @Test
    fun theSplitPointIsInsideTheServerNameOfARealClientHello() {
        val engine = javax.net.ssl.SSLContext.getDefault().createSSLEngine(WarpAccountRegistration.API_HOST, 443)
        engine.useClientMode = true
        engine.sslParameters = engine.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(WarpAccountRegistration.API_HOST)) }
        engine.beginHandshake()
        val hello = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize * 2)
        engine.wrap(java.nio.ByteBuffer.allocate(0), hello)
        hello.flip()
        val bytes = ByteArray(hello.remaining()).also(hello::get)
        val name = WarpAccountRegistration.API_HOST.toByteArray()
        val at = (0..bytes.size - name.size).first { start -> name.indices.all { bytes[start + it] == name[it] } }
        val cut = WarpAccountRegistration.nameOffset(bytes)
        assertTrue(cut > at && cut < at + name.size)
    }

    @Test
    fun aRegistrationKeyPairIsAWireGuardKeyPair() {
        val (private, public) = WarpAccountRegistration.newKey()
        assertEquals(32, Base64.getDecoder().decode(private).size)
        assertEquals(32, Base64.getDecoder().decode(public).size)
    }

    @Test
    fun theRealApiRegistersAnAccountOverBothRoutes() {
        assumeTrue(System.getenv("KVN_REAL_NETWORK") == "1")
        val file = File.createTempFile("warp-account", ".json").also { it.delete() }
        try {
            val outcome = WarpAccountRegistration.ensureAccount(file)
            assertTrue(outcome.toString(), outcome.ok)
            assertTrue(WarpAccountRegistration.accountFileReady(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun theRealApiAnswersToAClientHelloCutInsideTheName() {
        assumeTrue(System.getenv("KVN_REAL_NETWORK") == "1")
        val body = WarpAccountRegistration.registrationBody(WarpAccountRegistration.newKey().second)
        val raw = WarpAccountRegistration.exchange(null, body, true, 8000)
        assertTrue(String(raw).contains("\"config\""))
    }

    @Test
    fun registrationEndingsUseTheReportContractVocabulary() {
        // the real detail texts: the owner's provider reset (Windows wording), and this module's own failures
        fun result(ok: Boolean, detail: String) =
            WarpHopFacts.resultOf(WarpAccountRegistration.Outcome(ok, "api", detail, 5))
        assertEquals("answered", result(true, "registered"))
        assertEquals("reset", result(false, "SocketException: Connection reset"))
        assertEquals("reset", result(false, "EOFException: closed by the remote host"))
        assertEquals("timeout", result(false, "SocketTimeoutException: no answer in time"))
        assertEquals("refused", result(false, "ConnectException: Connection refused"))
        assertEquals("unknown", result(false, "IllegalArgumentException: API answered 429"))
    }

    @Test
    fun theFactsOmitTheRegistrationProbeWhenTheAccountFileWasThere() {
        WarpHopFacts.record(true, WarpAccountRegistration.Outcome(true, "cached", "account file is there", 0))
        assertEquals(null, WarpHopFacts.current().register)
        WarpHopFacts.record(true, WarpAccountRegistration.Outcome(true, "api", "registered", 1900))
        assertEquals(WarpHopFacts.Probe("answered", 1900), WarpHopFacts.current().register)
        WarpHopFacts.record(false, null)
        assertEquals(false, WarpHopFacts.current().enabled)
    }

    /**
     * Field report 2026-11-26 (Windows 0.8.121, the same code path): a network that swallows every packet made every route run
     * into its own timeout and the sum passed the time the connect has. A black hole is real network behaviour: 192.0.2.1
     * (TEST-NET-1, reserved for documentation) never answers.
     */
    @Test
    fun aBlackHoleCannotStallTheConnectAndTheNextConnectSkipsTheDirectRoutes() {
        WarpAccountRegistration.forgetFailure()
        val folder = java.nio.file.Files.createTempDirectory("warp-budget").toFile()
        try {
            val started = System.nanoTime()
            val outcome = WarpAccountRegistration.ensureAccount(
                File(folder, "account.json"),
                timeoutMs = 8000,
                budgetMs = 4_000,
                exchanger = { _, body, fragmented, timeout -> WarpAccountRegistration.exchange("192.0.2.1", body, fragmented, timeout) },
            )
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertFalse(outcome.ok)
            assertTrue("the registration took $tookMs ms with a budget of 4000 ms", tookMs < 6_000)
            val again = WarpAccountRegistration.ensureAccount(File(folder, "account.json"), exchanger = { _, _, _, _ -> error("must not be tried") })
            assertEquals("skipped", again.route)
        } finally {
            WarpAccountRegistration.forgetFailure()
            folder.deleteRecursively()
        }
    }
}
