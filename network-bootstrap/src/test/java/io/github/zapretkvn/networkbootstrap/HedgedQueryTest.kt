package io.github.zapretkvn.networkbootstrap

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HedgedQueryTest {
    @Test
    fun `fast answer sends exactly one query`() = runBlocking<Unit> {
        val sent = AtomicInteger()
        val value = HedgedQuery.run<String>(hedgeAfterMillis = 200) { attempt ->
            sent.incrementAndGet()
            attempt.succeed("a")
            ({})
        }
        Thread.sleep(300)
        assertEquals("a", value)
        assertEquals(1, sent.get())
    }

    @Test
    fun `lost first packet is repeated once and second answer wins`() = runBlocking<Unit> {
        val sent = AtomicInteger()
        val firstCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val value = HedgedQuery.run<String>(hedgeAfterMillis = 50) { attempt ->
            when (sent.incrementAndGet()) {
                1 -> ({ firstCancelled.set(true) }) // ответа нет: пакет потерян
                else -> {
                    attempt.succeed("second")
                    ({})
                }
            }
        }
        assertEquals("second", value)
        assertEquals(2, sent.get())
        assertTrue("Проигравший запрос должен быть отменён.", firstCancelled.get())
    }

    @Test
    fun `never more than two queries per question`() = runBlocking<Unit> {
        val sent = AtomicInteger()
        val result = withTimeoutOrNull(400) {
            HedgedQuery.run<String>(hedgeAfterMillis = 20) { _ ->
                sent.incrementAndGet()
                ({})
            }
        }
        assertNull(result)
        assertEquals(2, sent.get())
    }

    @Test
    fun `fast failure is final and does not start the repeat`() = runBlocking<Unit> {
        val sent = AtomicInteger()
        try {
            HedgedQuery.run<String>(hedgeAfterMillis = 100) { attempt ->
                sent.incrementAndGet()
                attempt.fail(IOException("refused"))
                ({})
            }
            fail("Ожидался отказ.")
        } catch (error: IOException) {
            assertEquals("refused", error.message)
        }
        Thread.sleep(200)
        assertEquals(1, sent.get())
    }

    @Test
    fun `after the repeat the error is final only when both queries failed`() = runBlocking<Unit> {
        val sent = AtomicInteger()
        try {
            HedgedQuery.run<String>(hedgeAfterMillis = 30) { attempt ->
                val number = sent.incrementAndGet()
                if (number == 1) {
                    Thread {
                        Thread.sleep(80)
                        attempt.fail(IOException("first"))
                    }.start()
                } else {
                    Thread {
                        Thread.sleep(120)
                        attempt.fail(IOException("second"))
                    }.start()
                }
                ({})
            }
            fail("Ожидался отказ.")
        } catch (error: IOException) {
            assertEquals("second", error.message)
        }
        assertEquals(2, sent.get())
    }

    @Test
    fun `first failure does not mask the answer of the repeat`() = runBlocking<Unit> {
        val sent = AtomicInteger()
        val value = HedgedQuery.run<String>(hedgeAfterMillis = 30) { attempt ->
            val number = sent.incrementAndGet()
            Thread {
                Thread.sleep(if (number == 1) 70 else 100)
                if (number == 1) attempt.fail(IOException("first")) else attempt.succeed("ok")
            }.start()
            ({})
        }
        assertEquals("ok", value)
    }

    @Test
    fun `disabled hedge keeps a single query`() = runBlocking<Unit> {
        val sent = AtomicInteger()
        val result = withTimeoutOrNull(200) {
            HedgedQuery.run<String>(hedgeAfterMillis = 0) { _ ->
                sent.incrementAndGet()
                ({})
            }
        }
        assertNull(result)
        assertEquals(1, sent.get())
    }

    @Test
    fun `cancellation cancels outstanding queries`() = runBlocking<Unit> {
        val cancelled = AtomicInteger()
        withTimeoutOrNull(200) {
            HedgedQuery.run<String>(hedgeAfterMillis = 20) { _ -> ({ cancelled.incrementAndGet() }) }
        }
        assertEquals(2, cancelled.get())
        assertFalse(cancelled.get() > 2)
    }
}
