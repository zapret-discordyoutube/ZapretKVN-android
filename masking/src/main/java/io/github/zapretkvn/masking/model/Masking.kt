package io.github.zapretkvn.masking.model

/**
 * Masking settings: one object globally, optionally one per server ([MaskingOverride]).
 *
 * Every section is optional, and a missing section means "do nothing", never "defaults":
 * the app must not invent values. A section present with `enabled = false` switches that
 * section off. Field names match the Windows package `xray_fluent/masking` one to one, so
 * an exported set is read on either platform.
 *
 * Ownership (workspace AGENTS.md, native cores): each section only produces fields that its
 * native core already parses. [xray] goes to Xray-core, [wireguard] to the Amnezia core,
 * [singbox] to sing-box's own TLS client for the protocols sing-box owns.
 */
data class Masking(
    val wireguard: WireGuardMasking? = null,
    val xray: XrayShaping? = null,
    val singbox: SingboxFragment? = null,
) {
    val isEmpty: Boolean get() = wireguard == null && xray == null && singbox == null

    companion object {
        const val SCHEMA = 1
        val NONE = Masking()
    }
}

/**
 * WireGuard / AmneziaWG masking. Only the fields amneziawg-go sends from the initiator
 * before the handshake (junk packets and the I1-I5 signature packets): a server never
 * checks them, so they work against a plain WireGuard server too.
 *
 * The header fields H1-H4 and S1-S4 are deliberately absent: they change the wire format and
 * must equal the server's values, so a client-side "masking" value can only break the
 * connection. They stay what the imported profile says.
 */
data class WireGuardMasking(
    val enabled: Boolean = true,
    /** Junk packet count, amneziawg-go `jc`. */
    val jc: Int? = null,
    /** Smallest junk packet size in bytes, `jmin`. */
    val jmin: Int? = null,
    /** Largest junk packet size in bytes, `jmax`. */
    val jmax: Int? = null,
    /** Signature packets `i1`..`i5`, each a tag chain such as `<r 2><b 0x0100>`; blank = not sent. */
    val signatures: Map<String, String> = emptyMap(),
) {
    val hasJunk: Boolean get() = jc != null || jmin != null || jmax != null

    companion object {
        val SIGNATURE_KEYS = listOf("i1", "i2", "i3", "i4", "i5")
    }
}

/** Xray-core connection shaping for VLESS, VMess and Trojan (mux.cool, XHTTP xmux, finalmask TCP fragment, desync). */
data class XrayShaping(
    val enabled: Boolean = true,
    val mux: MuxCool? = null,
    val xmux: Xmux? = null,
    val fragment: XrayFragment? = null,
    val desync: Desync? = null,
)

/**
 * Packet tricks on the TCP socket to the VPN server (masking-design/04), run by the protected
 * dialer the app owns, not by Xray (Android core patch 0017, `common/kvndesync`): no root, no raw
 * socket, no driver. VLESS only for now. Experiment, behind «Подобрать».
 *
 * - `fake`: a decoy ClientHello with [fakeSni] and a low [fakeTtl] that dies before the server;
 *   the kernel then resends the real bytes.
 * - `disorder`: the first piece (cut at [splitAt]) goes with TTL 1 and is resent after the rest.
 * - `split`: the first write in two TCP segments, cut at [splitAt].
 * - `oob`: one urgent byte after the first piece; breaks on paths that clear the URG flag.
 */
data class Desync(
    val enabled: Boolean = true,
    val method: String = "fake",
    /** `sni` (one byte into the server name) or a byte offset 1..[MAX_OFFSET]. */
    val splitAt: String = "sni",
    val fakeTtl: Int = 3,
    val fakeSni: String = "vk.com",
) {
    companion object {
        val METHODS = listOf("fake", "disorder", "split", "oob")
        const val MAX_OFFSET = 1400
        const val MAX_TTL = 32

        /** Above this, a decoy is likely to reach the server and break the connection. */
        const val SAFE_TTL = 8
    }
}

/**
 * Xray `mux` of an outbound (infra/conf/xray.go MuxConfig). The core treats
 * `concurrency` < 0 as "mux off" and raises every value from 0 to 128 to 128
 * (app/proxyman/outbound/handler.go), so the emitter never writes a bare 0.
 */
data class MuxCool(
    val enabled: Boolean = true,
    val concurrency: Int = 8,
    /** -1 = XUDP off, 0 = UDP rides the TCP mux, > 0 = its own XUDP connection pool. */
    val xudpConcurrency: Int = 16,
    val xudpProxyUDP443: String = "reject",
) {
    companion object {
        val UDP443_MODES = listOf("reject", "allow", "skip")
    }
}

/** XHTTP `xmux` (infra/conf/transport_method.go XmuxConfig): how connections are pooled and rotated. */
data class Xmux(
    val maxConcurrency: Span? = null,
    val maxConnections: Span? = null,
    val cMaxReuseTimes: Span? = null,
    val hMaxRequestTimes: Span? = null,
    val hMaxReusableSecs: Span? = null,
    val hKeepAlivePeriod: Long? = null,
)

/** finalmask TCP `fragment` (infra/conf/transport_finalmask.go FragmentMask). Experiment. */
data class XrayFragment(
    val enabled: Boolean = true,
    /** `"tlshello"` or a range of TCP write numbers such as `"1-3"`. */
    val packets: String = "tlshello",
    val length: Span = Span(100, 200),
    val delay: Span = Span(10, 20),
    val maxSplit: Span? = null,
)

/**
 * sing-box's own TLS fragmentation (option/tls.go OutboundTLSOptions `fragment`,
 * `record_fragment`, `fragment_fallback_delay`) for the TLS protocols sing-box owns
 * (AnyTLS, HTTP, ShadowTLS; Trojan and VMess moved to Xray on 2026-11-16, which has `finalmask`).
 * Client side only; REALITY ignores it. Experiment.
 */
data class SingboxFragment(
    val enabled: Boolean = true,
    val fragment: Boolean = true,
    val recordFragment: Boolean = false,
    val fallbackDelayMs: Int? = null,
)

/** The per-server choice. Stored only when it is not [Inherit]. */
sealed interface MaskingOverride {
    data object Inherit : MaskingOverride

    data object Off : MaskingOverride

    /** Replaces the global settings section by section; a section absent here falls back to global. */
    data class Custom(val masking: Masking) : MaskingOverride
}
