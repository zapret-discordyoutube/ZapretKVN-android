package io.github.zapretkvn.android.engines.hysteria

import io.github.zapretkvn.android.engines.failover.OutboundFailureLogParser
import io.github.zapretkvn.android.engines.singbox.consume
import io.github.zapretkvn.android.vpn.VpnFailureCodeSanitizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaRuntimeContractTest {
    @Test
    fun `public failure sanitizer preserves typed Hysteria taxonomy`() {
        assertEquals(
            HysteriaFailureCode.TRANSITION_DEADLINE_EXCEEDED.name,
            VpnFailureCodeSanitizer.sanitize("transition_deadline_exceeded"),
        )
        assertEquals("VPN-200", VpnFailureCodeSanitizer.sanitize("vpn-200"))
        assertEquals("", VpnFailureCodeSanitizer.sanitize("arbitrary error"))
    }

    @Test
    fun `Android capability matches shared golden vectors`() {
        goldenVectors().forEach { vector ->
            val id = vector.string("id")
            val expected = vector["expected"] as JsonObject
            val result = HysteriaCapabilityClassifier.classify(vector.string("uri"))

            assertEquals(id, expected.boolean("valid"), result.valid)
            assertEquals(id, expected.string("obfs_kind"), result.obfsKind)
            assertEquals(id, expected.string("tls_kind"), result.tlsKind)
            assertEquals(id, expected.string("endpoint_kind"), result.endpointKind)
            assertEquals(id, expected.strings("requirements").toSet(), result.runtimeRequirements)
            assertEquals(id, expected.string("android_execution"), result.executionKind.wireValue)
            assertEquals(id, expected.string("android_switch"), result.switchKind.wireValue)
            assertEquals(id, expected.optionalString("failure"), result.failureCode?.name)
        }
    }

    @Test
    fun `failure taxonomy keeps security failures out of automatic switch`() {
        val cases = mapOf(
            "no recent network activity" to HysteriaFailureCode.TARGET_NETWORK_TIMEOUT,
            "connect: connection refused" to HysteriaFailureCode.TARGET_CONNECTION_REFUSED,
            "tls: internal error" to HysteriaFailureCode.TARGET_TLS_INTERNAL,
            "certificate signed by unknown authority" to HysteriaFailureCode.TARGET_TLS_UNKNOWN_AUTHORITY,
            "certificate pin mismatch" to HysteriaFailureCode.TARGET_PIN_MISMATCH,
            "authentication failed" to HysteriaFailureCode.TARGET_AUTH_REJECTED,
            "obfs rejected" to HysteriaFailureCode.TARGET_OBFS_REJECTED,
            "address already in use" to HysteriaFailureCode.LOCAL_BIND_COLLISION,
        )
        cases.forEach { (message, expected) ->
            assertEquals(message, expected, HysteriaFailureClassifier.classify(message))
        }
        assertTrue(HYSTERIA_SECURITY_FAILURES.intersect(AUTOMATIC_HYSTERIA_SWITCH_FAILURES).isEmpty())
    }

    @Test
    fun `state reducer fences stale generation after replacement commit`() {
        var now = 10L
        val reducer = HysteriaStateReducer { ++now }
        reducer.begin(12, "old", setOf("old", "replacement"))
        listOf(
            HysteriaRuntimeState.STARTING_FRONT,
            HysteriaRuntimeState.WAITING_RELAY,
            HysteriaRuntimeState.READY,
            HysteriaRuntimeState.SWITCH_REQUESTED,
            HysteriaRuntimeState.PREPARING_REPLACEMENT,
            HysteriaRuntimeState.REPLACEMENT_READY,
            HysteriaRuntimeState.COMMITTING_SWITCH,
            HysteriaRuntimeState.STOPPING_OLD,
            HysteriaRuntimeState.READY,
        ).forEach { state -> assertTrue(reducer.advance(12, state)) }

        assertFalse(reducer.advance(11, HysteriaRuntimeState.FAILED))
        assertEquals(HysteriaRuntimeState.READY, reducer.session.state)
    }

    @Test
    fun `late old-target operational and security logs are fenced after commit`() {
        val fence = HysteriaTargetGenerationFence(42, "old")
        val oldOperational = checkNotNull(
            OutboundFailureLogParser.first(
                listOf("outbound/hysteria2[old]: no recent network activity"),
            ),
        ).let { line ->
            fence.event(line.outboundTag, checkNotNull(HysteriaFailureClassifier.classifyRuntime(line.message)), 1_000)
        }
        val oldSecurity = checkNotNull(
            OutboundFailureLogParser.first(
                listOf("outbound/hysteria2[old]: x509: certificate signed by unknown authority"),
            ),
        ).let { line ->
            fence.event(line.outboundTag, checkNotNull(HysteriaFailureClassifier.classifyRuntime(line.message)), 1_001)
        }

        fence.commit("replacement")

        assertFalse(fence.accepts(checkNotNull(oldOperational)))
        assertFalse(fence.accepts(checkNotNull(oldSecurity)))
        val current = checkNotNull(
            fence.event(
                "replacement",
                HysteriaFailureCode.TARGET_NETWORK_TIMEOUT,
                1_002,
            ),
        )
        assertTrue(fence.accepts(current))
    }

    @Test
    fun `security failure cannot remove pin or enable insecure`() {
        val uri = "hy2://auth@pinned.example:443/?pinSHA256=" + "a".repeat(64)
        val before = HysteriaCapabilityClassifier.classify(uri)
        assertEquals(HysteriaFailureCode.TARGET_PIN_MISMATCH, HysteriaFailureClassifier.classify("pin mismatch"))
        assertEquals("pinned", before.tlsKind)
        assertFalse("pin failure is terminal for this profile", HysteriaFailureCode.TARGET_PIN_MISMATCH in AUTOMATIC_HYSTERIA_SWITCH_FAILURES)
        assertEquals(before, HysteriaCapabilityClassifier.classify(uri))
    }

    private fun goldenVectors(): List<JsonObject> {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream("hysteria_golden_vectors.json"))
        val root = resource.bufferedReader().use { Json.parseToJsonElement(it.readText()) } as JsonObject
        return (root["vectors"] as JsonArray).map { it as JsonObject }
    }

    private fun JsonObject.string(key: String): String =
        checkNotNull((this[key] as? JsonPrimitive)?.contentOrNull)

    private fun JsonObject.optionalString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean =
        checkNotNull(this[key] as? JsonPrimitive).boolean

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as JsonArray).map { checkNotNull((it as? JsonPrimitive)?.contentOrNull) }
}
