package io.github.zapretkvn.android.vpn

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.R
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.ui.QuickSettingsTilePresentation
import io.github.zapretkvn.android.ui.toQuickSettingsTilePresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ZapretQuickSettingsTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { (application as ZapretApplication).container }
    private var stateJob: Job? = null
    private var clickJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            container.vpnController.state.collectLatest(::render)
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (clickJob?.isActive == true) return
        when (container.vpnController.state.value) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Starting,
            is VpnConnectionState.Reconnecting,
            -> container.vpnController.stop()
            is VpnConnectionState.Paused -> container.vpnController.resumePaused()
            is VpnConnectionState.Stopping -> Unit
            VpnConnectionState.Stopped,
            is VpnConnectionState.Error,
            -> startSelectedProfile()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startSelectedProfile() {
        clickJob = serviceScope.launch {
            val profileId = withContext(Dispatchers.IO) {
                val selectedId = container.uiSettingsStore.settings.first().activeProfileId
                val profiles = container.profileStore.initialize()
                selectedId?.takeIf { id -> profiles.any { it.id == id } }
            }
            if (profileId == null) {
                openApp()
                return@launch
            }
            val needsNotificationPermission =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@ZapretQuickSettingsTileService,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
            if (needsNotificationPermission || container.vpnController.permissionIntent() != null) {
                openApp(profileId)
            } else {
                container.vpnController.start(profileId)
            }
        }
    }

    private fun render(state: VpnConnectionState) {
        val tile = qsTile ?: return
        val presentation = state.toQuickSettingsTilePresentation()
        val subtitle = getString(
            when (presentation.subtitle) {
                QuickSettingsTilePresentation.Subtitle.Off -> R.string.quick_settings_tile_off
                QuickSettingsTilePresentation.Subtitle.Starting ->
                    R.string.quick_settings_tile_starting
                QuickSettingsTilePresentation.Subtitle.On -> R.string.quick_settings_tile_on
                QuickSettingsTilePresentation.Subtitle.Stopping ->
                    R.string.quick_settings_tile_stopping
                QuickSettingsTilePresentation.Subtitle.Paused ->
                    R.string.quick_settings_tile_paused
                QuickSettingsTilePresentation.Subtitle.Reconnecting ->
                    R.string.quick_settings_tile_reconnecting
                QuickSettingsTilePresentation.Subtitle.Error -> R.string.quick_settings_tile_error
            },
        )
        tile.label = getString(R.string.quick_settings_tile_label)
        tile.state = if (presentation.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = subtitle
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openApp(profileId: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (profileId != null) {
            intent
                .setAction(MainActivity.ACTION_REQUEST_VPN_START)
                .putExtra(MainActivity.EXTRA_PROFILE_ID, profileId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                OPEN_APP_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val OPEN_APP_REQUEST_CODE = 1002
    }
}
