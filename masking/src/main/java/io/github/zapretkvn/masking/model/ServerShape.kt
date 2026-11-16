package io.github.zapretkvn.masking.model

/**
 * What the merge needs to know about one server to decide which masking applies to it.
 * Read from the runtime outbound/endpoint JSON (see `MaskingApplier.shapeOf`), never stored.
 */
data class ServerShape(
    val core: Core,
    /** sing-box outbound type: vless, trojan, wireguard, ... */
    val protocol: String,
    /** Xray stream network for VLESS (tcp, raw, ws, grpc, xhttp, ...); null when unknown. */
    val network: String? = null,
    /** tls, reality or none. */
    val security: String? = null,
    val flow: String? = null,
) {
    /** Native core owning the protocol (workspace AGENTS.md; Windows `protocol_core.py`). */
    enum class Core { Xray, Amnezia, Hysteria, SingBox }

    val isVision: Boolean get() = flow?.startsWith("xtls-rprx-vision") == true

    /** XHTTP, gRPC and HTTP/2 already multiplex streams; mux.cool on top of them is advised against. */
    val isMultiplexedTransport: Boolean get() = network in MULTIPLEXED

    val isXhttp: Boolean get() = network == "xhttp" || network == "splithttp"

    val isTcpStream: Boolean get() = network == null || network in TCP_STREAMS

    private companion object {
        val MULTIPLEXED = setOf("xhttp", "splithttp", "grpc", "h2", "http")
        val TCP_STREAMS = setOf("tcp", "raw", "ws", "httpupgrade", "xhttp", "splithttp", "grpc", "h2", "http")
    }
}
