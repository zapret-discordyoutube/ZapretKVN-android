package io.github.zapretkvn.clientreports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockDiagnosisTest {
    private val ok = ProbeResult.Answered
    private val silent = ProbeResult.Timeout
    private val reset = ProbeResult.Reset

    /** Сеть телефона работает (контроль ответил), если тест не говорит иного. */
    private fun code(facts: DiagnosisFacts) =
        BlockDiagnosis.diagnose(facts.copy(control = ok))?.let { it.failedStep to it.detail }

    @Test
    fun noVerdictWhenThePhonesOwnNetworkIsDown() {
        // Портал входа или нет интернета: молчит и сервер, и наш фронт — это не блокировка сервера.
        val silentEverywhere = DiagnosisFacts(control = silent, tcpTarget = silent, tcpOtherPort = silent)
        assertNull(BlockDiagnosis.diagnose(silentEverywhere))
        assertNull(BlockDiagnosis.diagnoseUdp(silentEverywhere.copy(udpTransport = true)))
        assertNull(BlockDiagnosis.diagnose(silentEverywhere.copy(control = ProbeResult.Unknown)))
        assertEquals("ip_blocked", BlockDiagnosis.diagnose(silentEverywhere.copy(control = ok))?.detail)
        assertEquals(
            "Проверка пути не удалась: сеть телефона не открывает даже рабочий адрес.",
            BlockDiagnosis.describe(null, silentEverywhere),
        )
    }

    @Test
    fun aMissingNameIsTheServersNameNotABlock() {
        assertEquals("resolve" to "dns_nxdomain", code(DiagnosisFacts(dohNameMissing = true, systemDns = emptySet())))
    }

    @Test
    fun aFreezeAfterASuccessfulCheckTurnsConnectedIntoFailed() {
        val frozen = BlockDiagnosis.freeze(16_000)
        assertEquals("freeze_16k", frozen?.detail)
        assertNull(BlockDiagnosis.freeze(65_536))   // прочитано всё, что проба просит
        assertNull(BlockDiagnosis.freeze(null))
        val verdict = AttemptClassifier.classify(AttemptFacts(succeeded = true), frozen)
        assertEquals(ReportOutcome.Failed, verdict?.outcome)
        assertEquals("request" to "freeze_16k", verdict?.let { it.failedStep to it.detail })
        // Другой вывод не портит удачу: подключение прошло.
        assertEquals(ReportOutcome.Connected, AttemptClassifier.classify(AttemptFacts(succeeded = true), Diagnosis("connect", "ip_blocked"))?.outcome)
    }

    @Test
    fun theScreenSaysWhatWasFound() {
        assertEquals("Закрыто имя сервера (SNI): с другим именем тот же адрес отвечает.", BlockDiagnosis.describe(Diagnosis("handshake", "sni_blocked"), null))
        val open = DiagnosisFacts(control = ok, tcpTarget = ok, usesTls = true, tlsRealName = ok)
        assertEquals("Путь до сервера открыт: адрес, порт и имя отвечают.", BlockDiagnosis.describe(null, open))
        assertEquals("Проверка пути до сервера не проводилась.", BlockDiagnosis.describe(null, null))
    }

    @Test
    fun clientHelloCarriesTheChosenNameInAWellFormedRecord() {
        val hello = TlsProbe.clientHello("vpn.example.com", kotlin.random.Random(7))
        assertEquals(0x16, hello[0].toInt())
        val recordLength = (hello[3].toInt() and 0xff shl 8) or (hello[4].toInt() and 0xff)
        assertEquals(hello.size - 5, recordLength)
        assertEquals(1, hello[5].toInt())   // ClientHello
        val handshakeLength = (hello[6].toInt() and 0xff shl 16) or (hello[7].toInt() and 0xff shl 8) or (hello[8].toInt() and 0xff)
        assertEquals(hello.size - 9, handshakeLength)
        assertEquals("vpn.example.com", TlsProbe.serverNameOf(hello))
        assertEquals("ya.ru", TlsProbe.serverNameOf(TlsProbe.clientHello("ya.ru.", kotlin.random.Random(1))))
        // Байт в байт как у Windows (diagnosis.client_hello) при нулевой случайной части.
        val zero = TlsProbe.clientHello("vpn.example.com", object : kotlin.random.Random() { override fun nextBits(bitCount: Int) = 0 })
        assertEquals(248, zero.size)
        assertEquals(
            "6dc7b9266543b53282983212404e292e840180a17d9a13e7e009202ccdcd5f66",
            java.security.MessageDigest.getInstance("SHA-256").digest(zero).joinToString("") { "%02x".format(it) },
        )
        // Случайная часть разная: два ClientHello не совпадают байт в байт.
        assertEquals(false, hello.contentEquals(TlsProbe.clientHello("vpn.example.com", kotlin.random.Random(8))))
        assertEquals(ProbeResult.Answered, TlsProbe.judgeFirstByte(0x15))
        assertEquals(ProbeResult.Reset, TlsProbe.judgeFirstByte(-1))
    }

    @Test
    fun tcpSwapTellsAPortBlockFromAWholeIpBlock() {
        assertEquals("connect" to "port_blocked", code(DiagnosisFacts(tcpTarget = silent, tcpOtherPort = ok)))
        assertEquals("connect" to "ip_blocked", code(DiagnosisFacts(tcpTarget = silent, tcpOtherPort = silent)))
        // Сброс — адрес жив: это не закрытый IP.
        assertEquals("connect" to "tcp_reset", code(DiagnosisFacts(tcpTarget = reset, tcpOtherPort = silent)))
        assertEquals("connect" to "port_blocked", code(DiagnosisFacts(tcpTarget = silent, tcpOtherPort = reset)))
        // The server already listens on 443: nothing to swap, only the kind of silence is known.
        assertEquals("connect" to "tcp_timeout", code(DiagnosisFacts(tcpTarget = silent)))
        assertEquals("connect" to "tcp_reset", code(DiagnosisFacts(tcpTarget = reset)))
    }

    @Test
    fun tlsSwapTellsAnSniBlockFromAnIpLevelTlsBlock() {
        val base = DiagnosisFacts(tcpTarget = ok, usesTls = true, tlsRealName = silent)
        assertEquals("handshake" to "sni_blocked", code(base.copy(tlsNeutralName = ok)))
        assertEquals("handshake" to "sni_blocked", code(base.copy(tlsNeutralName = ok, tlsRealNameElsewhere = ok)))
        assertEquals("handshake" to "sni_blocked_everywhere", code(base.copy(tlsNeutralName = ok, tlsRealNameElsewhere = silent)))
        // Наш фронт закрывает соединение без ответа на чужое имя (проверено 19.11.2026): сброс там не фильтр.
        assertEquals("handshake" to "sni_blocked", code(base.copy(tlsNeutralName = ok, tlsRealNameElsewhere = reset)))
        assertEquals("handshake" to "tls_blocked", code(base.copy(tlsNeutralName = silent)))
        assertNull(code(base.copy(tlsNeutralName = reset)))
        // The neutral probe could not run: no claim.
        assertNull(code(base))
        // The real name answered (even with an alert): TLS is not the blocked step.
        assertNull(code(base.copy(tlsRealName = ok, tlsNeutralName = ok)))
    }

    @Test
    fun freezeIsNamedOnlyForAStallNearSixteenKilobytes() {
        val up = DiagnosisFacts(tcpTarget = ok, usesTls = true, tlsRealName = ok)
        assertEquals("request" to "freeze_16k", code(up.copy(stalledAfterBytes = 16_384)))
        assertEquals("request" to "freeze_16k", code(up.copy(stalledAfterBytes = 4_800)))   // mobile: ~8 packets
        assertNull(code(up.copy(stalledAfterBytes = 200_000)))
        assertNull(code(up.copy(stalledAfterBytes = 0)))
    }

    @Test
    fun dnsIsNamedOnlyWhenNothingBelowItIsClosed() {
        val poisoned = DiagnosisFacts(systemDns = setOf("10.10.34.35"), dohDns = setOf("185.1.2.3"))
        assertEquals("resolve" to "dns_poisoned", code(poisoned))
        assertEquals("resolve" to "dns_no_answer", code(DiagnosisFacts(systemDns = emptySet(), dohDns = setOf("185.1.2.3"))))
        assertNull(code(DiagnosisFacts(systemDns = setOf("185.1.2.3", "185.1.2.4"), dohDns = setOf("185.1.2.3"))))
        // The app connected by our DoH address and TLS is cut there: that is the blocker.
        assertEquals(
            "handshake" to "tls_blocked",
            code(poisoned.copy(tcpTarget = ok, usesTls = true, tlsRealName = silent, tlsNeutralName = silent)),
        )
    }

    @Test
    fun udpServersAreJudgedByTheirIpOnly() {
        assertEquals("connect" to "ip_blocked", BlockDiagnosis.diagnoseUdp(DiagnosisFacts(control = ok, udpTransport = true, tcpOtherPort = silent))?.let { it.failedStep to it.detail })
        assertNull(BlockDiagnosis.diagnoseUdp(DiagnosisFacts(control = ok, udpTransport = true, tcpOtherPort = ok)))
        assertNull(BlockDiagnosis.diagnoseUdp(DiagnosisFacts(control = ok, udpTransport = true, tcpOtherPort = reset)))
        // TLS facts never apply to a UDP server.
        assertNull(code(DiagnosisFacts(udpTransport = true, usesTls = true, tcpTarget = ok, tlsRealName = silent, tlsNeutralName = silent)))
    }

    @Test
    fun diagnosisRefinesTheVerdictAndTheButtonSendsLocalErrorsMarked() {
        val sni = Diagnosis("handshake", "sni_blocked")
        val verdict = AttemptClassifier.classify(AttemptFacts(coreCode = "TARGET_NETWORK_TIMEOUT"), sni)
        assertEquals("handshake" to "sni_blocked", verdict?.let { it.failedStep to it.detail })
        // No core code and no traffic facts: not reported on its own, but the diagnosis proves a block.
        assertEquals("sni_blocked", AttemptClassifier.classify(AttemptFacts(), sni)?.detail)
        // A local failure never becomes a server failure, even with a diagnosis.
        assertNull(AttemptClassifier.classify(AttemptFacts(coreCode = "LOCAL_BIND_COLLISION"), sni))
        assertNull(AttemptClassifier.classify(AttemptFacts(networkLost = true)))
        // The button sends them marked local_*.
        assertEquals("local_bind_collision", AttemptClassifier.classifyForced(AttemptFacts(coreCode = "LOCAL_BIND_COLLISION"))?.detail)
        assertEquals("local_network", AttemptClassifier.classifyForced(AttemptFacts(networkLost = true))?.detail)
        assertEquals("local_unknown", AttemptClassifier.classifyForced(AttemptFacts())?.detail)
        assertEquals("connect", AttemptClassifier.classifyForced(AttemptFacts())?.failedStep)
        assertNull(AttemptClassifier.classifyForced(AttemptFacts(succeeded = true))?.takeIf { it.outcome == ReportOutcome.Failed })
        val long = AttemptClassifier.localCode("LOCAL_" + "X".repeat(80))
        assertEquals(true, long.length <= 48 && ClientReportContract.isValid(
            AttemptRecord("x1", "vless", ReportOutcome.Failed, "connect", long, 1, 1_795_000_000_000L, ""),
        ))
    }
}
