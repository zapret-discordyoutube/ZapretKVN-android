package io.github.zapretkvn.android.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseWaitClientTest {
    private class Run(answers: List<String?>, report: Map<String, String> = emptyMap()) {
        val urls = mutableListOf<String>()
        val timeouts = mutableListOf<Int>()
        val pauses = mutableListOf<Long>()
        val grants = mutableListOf<String>()
        val delivered = mutableListOf<Map<String, String>>()
        var pending = report
        var now = 0L
        private val left = ArrayDeque(answers)

        val client = ReleaseWaitClient(
            currentVersion = "0.5.4",
            http = { url, timeout ->
                urls += url
                timeouts += timeout
                now += 1_000
                if (left.isEmpty()) throw CancellationException("конец сценария")
                left.removeFirst() ?: throw java.io.IOException("нет связи")
            },
            report = { pending },
            reportDelivered = { delivered += it; pending = emptyMap() },
            onGrant = { grants += it },
            clock = { now },
            pause = { pauses += it; now += it },
        )

        fun go() = runBlocking { runCatching { client.run() } }
    }

    @Test
    fun `first question is answered at once, then the service holds it`() {
        val run = Run(listOf("""{"version":"0.5.4","changed":false,"ticket":7}""", """{"changed":false}"""))
        run.go()

        assertTrue(run.urls[0].contains("hold=0"))
        assertEquals(ReleaseWaitClient.PROBE_TIMEOUT_MILLIS, run.timeouts[0])
        // Разогрев: короткое ожидание; номер в очереди возвращается службе.
        assertTrue(run.urls[1].contains("hold=45") && run.urls[1].contains("ticket=7"))
        assertEquals(ReleaseWaitClient.READ_TIMEOUT_MILLIS, run.timeouts[1])
        assertTrue(run.urls.all { it.contains("channel=stable") && it.contains("known=0.5.4") })
    }

    @Test
    fun `grant is passed on once per version and never for the installed one`() {
        val yes = """{"version":"0.5.5","changed":true}"""
        val run = Run(listOf("""{"version":"0.5.4","changed":true}""", yes, yes, """{"version":"0.5.6","changed":true}"""))
        run.go()

        assertEquals(listOf("0.5.5", "0.5.6"), run.grants)
    }

    @Test
    fun `while the granted version installs the listener does not hammer the service`() {
        // Служба повторяет «есть 0.5.5» сразу на каждый вопрос, пока программа не обновилась.
        val yes = """{"version":"0.5.5","changed":true}"""
        val run = Run(listOf(yes, yes, yes, yes))
        run.go()

        assertEquals(listOf("0.5.5"), run.grants)
        assertEquals("пауза после каждого ответа", 4, run.pauses.size)
        assertTrue(run.pauses.all { it == ReleaseWaitClient.QUICK_ANSWER_PAUSE_MILLIS })
    }

    @Test
    fun `report rides on the question and is dropped only after the service answered`() {
        val report = mapOf("prev" to "0.5.3", "took" to "40", "via" to "parts", "dl" to "190")
        val run = Run(listOf(null, """{"changed":false}""", """{"changed":false}"""), report)
        run.go()

        assertTrue(run.urls[0].contains("prev=0.5.3&took=40&via=parts&dl=190"))
        assertTrue("после сбоя отчёт повторяется", run.urls[1].contains("prev=0.5.3"))
        assertEquals(listOf(report), run.delivered)
        assertTrue(!run.urls[2].contains("prev="))
    }

    @Test
    fun `failures back off up to three minutes`() {
        val run = Run(listOf(null, null, null, null))
        run.go()

        assertEquals(listOf(60_000L, 120_000L, 180_000L, 180_000L), run.pauses)
    }

    @Test
    fun `an empty answer that came back too quickly is not repeated at once`() {
        val run = Run(listOf("""{"changed":false}""", """{"changed":false}""", """{"changed":false}"""))
        run.go()

        // Первый вопрос без ожидания паузы не требует; быстрые пустые ответы на ожидание — требуют.
        assertEquals(listOf(ReleaseWaitClient.QUICK_ANSWER_PAUSE_MILLIS, ReleaseWaitClient.QUICK_ANSWER_PAUSE_MILLIS), run.pauses)
        assertTrue("после разогрева служба сама выбирает срок", !run.urls[2].contains("hold="))
    }
}
