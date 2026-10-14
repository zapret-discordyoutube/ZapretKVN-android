package io.github.zapretkvn.android.hardening

enum class TunMtuMode {
    CoreDefault,
    Normalize1500,
}

data class VpnHidingOptions(
    val blockLocalEndpoints: Boolean = true,
    val neutralSessionName: Boolean = false,
    val tunMtuMode: TunMtuMode = TunMtuMode.Normalize1500,
)
