package io.github.zapretkvn.android.hardening

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

sealed interface RuntimeHardeningResult {
    data class Ready(val root: JsonObject) : RuntimeHardeningResult
    data class Blocked(val message: String) : RuntimeHardeningResult
}

/**
 * Applies rootless protections that can be enforced before libbox starts.
 *
 * This module deliberately does not claim to hide Android's VpnService, TUN interface or
 * NetworkCapabilities: those objects are owned by the OS and cannot be spoofed by a regular app.
 */
object VpnRuntimeHardening {
    const val NORMALIZED_MTU = 1500

    fun apply(root: JsonObject, options: VpnHidingOptions): RuntimeHardeningResult {
        if (options.blockLocalEndpoints) {
            extraInbounds(root).takeIf(List<String>::isNotEmpty)?.let { types ->
                return RuntimeHardeningResult.Blocked(
                    "Защита от localhost-чекеров запрещает дополнительные inbounds: " +
                        types.joinToString() + ". Оставьте только Android TUN.",
                )
            }
        }

        var hardened = root
        if (options.blockLocalEndpoints) {
            hardened = removeLocalControlEndpoints(hardened)
        }
        val wireGuardMtu = minimumUserspaceWireGuardMtu(hardened)
        val runtimeTunMtu = when {
            options.tunMtuMode == TunMtuMode.Normalize1500 ->
                wireGuardMtu?.coerceAtMost(NORMALIZED_MTU) ?: NORMALIZED_MTU
            wireGuardMtu != null && !hasExplicitTunMtu(hardened) -> wireGuardMtu
            else -> null
        }
        if (runtimeTunMtu != null) hardened = replaceTunMtu(hardened, runtimeTunMtu)
        return RuntimeHardeningResult.Ready(hardened)
    }

    fun sessionName(options: VpnHidingOptions): String =
        if (options.neutralSessionName) "Системная сеть" else "Zapret KVN"

    private fun extraInbounds(root: JsonObject): List<String> =
        (root["inbounds"] as? JsonArray)
            ?.mapIndexedNotNull { index, element ->
                val inbound = element as? JsonObject
                val type = inbound?.string("type")
                if (type == "tun") null else type?.take(40) ?: "#$index без type"
            }
            .orEmpty()

    private fun removeLocalControlEndpoints(root: JsonObject): JsonObject {
        val experimental = (root["experimental"] as? JsonObject)?.toMutableMap()
            ?: return root
        val clash = experimental["clash_api"] as? JsonObject
        if (clash != null) {
            experimental["clash_api"] = JsonObject(
                clash.filterKeys { key -> key !in CLASH_LISTENER_FIELDS },
            )
        }
        // sing-box v2ray_api is a network gRPC control surface. It has no in-process-only mode.
        experimental.remove("v2ray_api")
        return JsonObject(root.toMutableMap().apply {
            this["experimental"] = JsonObject(experimental)
        })
    }

    private fun replaceTunMtu(root: JsonObject, mtu: Int): JsonObject {
        val inbounds = root["inbounds"] as? JsonArray ?: return root
        val runtimeInbounds = JsonArray(inbounds.map { element ->
            val inbound = element as? JsonObject
            if (inbound?.string("type") == "tun") {
                JsonObject(inbound.toMutableMap().apply {
                    this["mtu"] = JsonPrimitive(mtu)
                })
            } else {
                element
            }
        })
        return JsonObject(root.toMutableMap().apply { this["inbounds"] = runtimeInbounds })
    }

    /**
     * The Android TUN and an inner userspace WireGuard endpoint are separate packet
     * planes, but the outer interface must not admit packets larger than the inner
     * endpoint can carry. Otherwise handshakes and small DNS packets can pass while
     * TCP/TLS stalls. RuntimeConfigBuilder materializes the endpoint default before
     * calling this function; the stored JSON is never changed.
     */
    private fun minimumUserspaceWireGuardMtu(root: JsonObject): Int? =
        (root["endpoints"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { endpoint ->
                endpoint.string("type") == "wireguard" &&
                    (endpoint["system"] as? JsonPrimitive)?.booleanOrNull != true
            }
            ?.mapNotNull { endpoint ->
                (endpoint["mtu"] as? JsonPrimitive)
                    ?.intOrNull
                    ?.takeIf { it in MIN_WIREGUARD_MTU..MAX_WIREGUARD_MTU }
            }
            ?.minOrNull()

    private fun hasExplicitTunMtu(root: JsonObject): Boolean =
        (root["inbounds"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.any { inbound ->
                inbound.string("type") == "tun" &&
                    (inbound["mtu"] as? JsonPrimitive)?.intOrNull != null
            } == true

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private const val MIN_WIREGUARD_MTU = 1280
    private const val MAX_WIREGUARD_MTU = 9000

    private val CLASH_LISTENER_FIELDS = setOf(
        "external_controller",
        "external_controller_tls",
        "secret",
        "external_ui",
        "external_ui_download_url",
        "external_ui_download_detour",
        "access_control_allow_origin",
        "access_control_allow_private_network",
    )
}
