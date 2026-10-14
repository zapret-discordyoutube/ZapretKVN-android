package io.github.zapretkvn.android.diagnostics

/**
 * The only connection identity which is allowed to cross the diagnostics
 * boundary.  Endpoint is deliberately an opaque reference: a support report
 * can distinguish two connections without containing a host, URI or port
 * credentials.
 */
data class DiagnosticTargetContext(
    val profileRef: String? = null,
    val profileName: String? = null,
    val outboundTag: String? = null,
    val protocol: String? = null,
    val endpoint: String? = null,
)
