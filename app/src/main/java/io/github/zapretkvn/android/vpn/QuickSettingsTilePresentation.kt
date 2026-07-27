package io.github.zapretkvn.android.vpn

internal data class QuickSettingsTilePresentation(
    val active: Boolean,
    val subtitle: Subtitle,
) {
    enum class Subtitle {
        Off,
        Starting,
        On,
        Stopping,
        Reconnecting,
        Error,
    }
}

internal fun VpnConnectionState.toQuickSettingsTilePresentation(): QuickSettingsTilePresentation =
    when (this) {
        VpnConnectionState.Stopped -> QuickSettingsTilePresentation(
            active = false,
            subtitle = QuickSettingsTilePresentation.Subtitle.Off,
        )
        is VpnConnectionState.Starting -> QuickSettingsTilePresentation(
            active = true,
            subtitle = QuickSettingsTilePresentation.Subtitle.Starting,
        )
        is VpnConnectionState.Connected -> QuickSettingsTilePresentation(
            active = true,
            subtitle = QuickSettingsTilePresentation.Subtitle.On,
        )
        is VpnConnectionState.Stopping -> QuickSettingsTilePresentation(
            active = false,
            subtitle = QuickSettingsTilePresentation.Subtitle.Stopping,
        )
        is VpnConnectionState.Reconnecting -> QuickSettingsTilePresentation(
            active = true,
            subtitle = QuickSettingsTilePresentation.Subtitle.Reconnecting,
        )
        is VpnConnectionState.Error -> QuickSettingsTilePresentation(
            active = false,
            subtitle = QuickSettingsTilePresentation.Subtitle.Error,
        )
    }
