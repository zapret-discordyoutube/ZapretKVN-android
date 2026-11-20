package io.github.zapretkvn.clientreports

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorsTest {
    private val installId = "0123456789abcdef0123456789abcdef"

    @Test
    fun codesAndFramesKeepTheContractsShape() {
        assertEquals("start:VPN-120", AppErrors.code("start", "VPN-120"))
        assertEquals("crash:Outer_Inner", AppErrors.code("crash", "Outer\$Inner"))
        assertNull(AppErrors.code("Crash", "x"))
        assertEquals("SessionHealthVerifier.verify:42", AppErrors.frame("SessionHealthVerifier.verify", 42))
        assertEquals("Foo.<init>:0", AppErrors.frame("Foo.<init>", -2))
        // No path, space or colon survives in a frame name.
        assertEquals("C__Users_x_a.py.run:3", AppErrors.frame("C:\\Users\\x a.py.run", 3))
        val frames = AppErrors.frames(Throwable().stackTrace)
        assertTrue(frames.isNotEmpty() && frames.size <= 16)
        assertTrue(frames.all { Regex("^[A-Za-z0-9_$.<>-]{1,72}:[0-9]{1,6}$").matches(it) })
        assertFalse(AppErrors.isValid(AppError("start", "sub:timeout", subscription = SubscriptionFact(true, "direct"))))
        assertTrue(AppErrors.isValid(AppError("subscription", "sub:timeout", subscription = SubscriptionFact(true, "direct"))))
    }

    @Test
    fun theRecordCarriesExactlyTheContractsFields() {
        val record = AppErrorRecord(
            reportId = "abababababababababababababababab",
            error = AppError("subscription", "sub:http_5xx", subscription = SubscriptionFact(ours = true, route = "tunnel")),
            count = 3,
            occurredAtMillis = 1_763_640_000_000L,
            appVersion = "0.6.46",
            networkToken = "v1.abc.def",
            details = AttemptDetails(serverIp = "203.0.113.5", port = 443, networkType = "wifi", versions = mapOf("os" to "15")),
        )
        val json = AppErrors.json(installId, "zapret-kvn", "android", record, "direct", clockOffsetMillis = -3_600_000)
        assertEquals(
            setOf("kind", "schema", "install_id", "report_id", "app", "step", "code", "count", "occurred_at", "sent_via",
                "network_token", "subscription", "network_type", "versions"),
            json.keys,
        )
        assertEquals("app_error", json["kind"]!!.jsonPrimitive.content)
        assertEquals("2025-11-20T11:00:00Z", json["occurred_at"]!!.jsonPrimitive.content)
        assertEquals("tunnel", json["subscription"]!!.jsonObject["route"]!!.jsonPrimitive.content)
        val crash = AppErrors.json(installId, "zapret-kvn", "android",
            record.copy(error = AppError("crash", "crash:IllegalStateException", listOf("A.b:1", "C.d:0"))), "tunnel", 0)
        assertEquals(listOf("A.b:1", "C.d:0"), crash["frames"]!!.jsonArray.map { (it as JsonPrimitive).content })
        assertFalse("subscription" in crash)
    }

    @Test
    fun repeatsAreCountedAndACodeGoesOnceADay() {
        val dir = Files.createTempDirectory("app-errors").toFile()
        var clock = 1_000_000_000L
        val store = AppErrorStore(File(dir, "app-errors.json")) { clock }
        val error = AppError("start", "start:VPN-120")
        store.add(error, "0.6.46", "", AttemptDetails())
        store.add(error, "0.6.46", "", AttemptDetails())
        val first = store.due(20).single()
        assertEquals(2, first.count)
        // A repeat while the first is on its way stays as a new one after the send.
        store.add(error, "0.6.46", "", AttemptDetails())
        store.sent(listOf(first))
        assertEquals(1, store.size())
        assertTrue(store.due(20).isEmpty())                                   // the same code waits a day
        clock += AppErrors.SEND_EVERY_MILLIS
        val next = store.due(20).single()
        assertEquals(1, next.count)
        assertTrue(next.reportId != first.reportId)
        // Another store on the same file sees the same (it survives a restart).
        assertEquals(1, AppErrorStore(File(dir, "app-errors.json")) { clock }.size())
        dir.deleteRecursively()
    }

    @Test
    fun anEarlierRunIsReadOnceAndOldErrorsExpire() {
        val dir = Files.createTempDirectory("app-errors").toFile()
        var clock = 1_000_000_000L
        val store = AppErrorStore(File(dir, "app-errors.json")) { clock }
        assertEquals(0, store.seenUntil())
        store.see(clock - 10)
        assertEquals(clock - 10, store.seenUntil())
        store.add(AppError("crash", "crash:anr"), "0.6.46", "", AttemptDetails(), atMillis = clock)
        clock += ClientReportContract.MAX_AGE_MS
        assertTrue(store.due(20).isEmpty())
        assertEquals(0, store.size())
        dir.deleteRecursively()
    }
}
