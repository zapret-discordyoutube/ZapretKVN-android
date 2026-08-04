package io.github.zapretkvn.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.routing.RoutingViewModel
import io.github.zapretkvn.android.ui.ZapretApp
import io.github.zapretkvn.android.ui.ThemeMode
import io.github.zapretkvn.android.ui.theme.ZapretTheme
import io.github.zapretkvn.android.updates.UpdateState
import io.github.zapretkvn.android.vpn.AppsViewModel
import io.github.zapretkvn.android.vpn.VpnController
import io.github.zapretkvn.android.vpn.VpnConnectionState

class MainActivity : ComponentActivity() {
    private val vpnController: VpnController
        get() = (application as ZapretApplication).container.vpnController
    private var pendingProfileId: String? = null
    private var activityStarted = false
    private var homeSelected = false
    private var diagnosticsSelected = false
    private var pendingUpdateInstall = false
    private val updateController
        get() = (application as ZapretApplication).container.updateController
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val profileId = pendingProfileId
        pendingProfileId = null
        if (result.resultCode == Activity.RESULT_OK && profileId != null) {
            vpnController.start(profileId)
        } else {
            vpnController.publishMessage("Android не выдал разрешение VPN.")
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        continueVpnPermissionRequest()
    }
    private val updateInstallerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        updateController.onInstallerFinished(result.resultCode == Activity.RESULT_OK)
    }
    private val unknownSourceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val shouldContinue = pendingUpdateInstall
        pendingUpdateInstall = false
        if (shouldContinue && canRequestPackageInstalls()) {
            launchUpdateInstaller()
        } else if (shouldContinue) {
            updateController.failInstallation("Android не разрешил установку из этого источника.")
        }
    }
    private val profilesViewModel: ProfilesViewModel by viewModels {
        (application as ZapretApplication).container.profilesViewModelFactory
    }
    private val appsViewModel: AppsViewModel by viewModels {
        (application as ZapretApplication).container.appsViewModelFactory
    }
    private val routingViewModel: RoutingViewModel by viewModels {
        (application as ZapretApplication).container.routingViewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by profilesViewModel.state.collectAsState()
            val appsState by appsViewModel.state.collectAsState()
            val vpnState by vpnController.state.collectAsState()
            val selectorGroups by vpnController.selectorGroups.collectAsState()
            val sessionStats by vpnController.sessionStats.collectAsState()
            val diagnostics by vpnController.diagnostics.collectAsState()
            val routingState by routingViewModel.state.collectAsState()
            val vpnMessage by vpnController.message.collectAsState()
            val updateState by updateController.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            LaunchedEffect(state.initialized) {
                if (state.initialized) updateController.checkOnce(state.settings.updateChannel)
            }
            LaunchedEffect(updateState) {
                if (updateState is UpdateState.Ready &&
                    updateController.consumeAutomaticInstallRequest()
                ) {
                    requestUpdateInstall()
                }
            }
            val darkTheme = when (state.settings.themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.Amoled -> true
            }
            SideEffect {
                val systemBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle,
                    navigationBarStyle = systemBarStyle,
                )
            }
            ZapretTheme(
                darkTheme = darkTheme,
                amoledTheme = state.settings.themeMode == ThemeMode.Amoled,
            ) {
                ZapretApp(
                    profilesViewModel = profilesViewModel,
                    state = state,
                    appsViewModel = appsViewModel,
                    appsState = appsState,
                    routingViewModel = routingViewModel,
                    routingState = routingState,
                    vpnState = vpnState,
                    selectorGroups = selectorGroups,
                    sessionStats = sessionStats,
                    diagnostics = diagnostics,
                    vpnMessage = vpnMessage,
                    onVpnMessageConsumed = vpnController::consumeMessage,
                    onVpnStart = ::requestVpnStart,
                    onVpnStop = vpnController::stop,
                    onVpnRestart = {
                        if (vpnState is VpnConnectionState.Paused) {
                            vpnController.resumePaused()
                        } else {
                            vpnController.restartIfConnected("Перезапуск пользователем")
                        }
                    },
                    onSelectOutbound = vpnController::selectOutbound,
                    onMeasurePing = vpnController::measurePing,
                    onMeasureGroup = vpnController::measureGroup,
                    onHomeSelected = ::setHomeSelected,
                    onDiagnosticsSelected = ::setDiagnosticsSelected,
                    onCreateDiagnosticShare = {
                        (application as ZapretApplication).container.diagnosticExporter.createShareIntent()
                    },
                    onClearDnsCache = vpnController::clearDnsCache,
                    updateState = updateState,
                    onCheckUpdate = updateController::check,
                    onDownloadUpdate = updateController::download,
                    onInstallUpdate = ::requestUpdateInstall,
                    onCancelUpdate = updateController::cancelAndDelete,
                )
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        updateVisibleStreams()
    }

    override fun onStop() {
        activityStarted = false
        updateVisibleStreams()
        super.onStop()
    }

    private fun setHomeSelected(selected: Boolean) {
        homeSelected = selected
        updateVisibleStreams()
    }

    private fun setDiagnosticsSelected(selected: Boolean) {
        diagnosticsSelected = selected
        updateVisibleStreams()
    }

    private fun updateVisibleStreams() {
        vpnController.setHomeVisible(activityStarted && homeSelected)
        vpnController.setDiagnosticsVisible(activityStarted && diagnosticsSelected)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == ACTION_REQUEST_VPN_START) {
            val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
            intent.action = null
            intent.removeExtra(EXTRA_PROFILE_ID)
            if (profileId.isNotBlank()) requestVpnStart(profileId)
            return
        }
        val uri = intent.externalImportUri() ?: return
        profilesViewModel.importDocument(uri)
    }

    private fun requestVpnStart(profileId: String) {
        pendingProfileId = profileId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueVpnPermissionRequest()
        }
    }

    private fun continueVpnPermissionRequest() {
        val profileId = pendingProfileId ?: return
        val permissionIntent = vpnController.permissionIntent()
        if (permissionIntent == null) {
            pendingProfileId = null
            vpnController.start(profileId)
        } else {
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun requestUpdateInstall() {
        if (!canRequestPackageInstalls()) {
            pendingUpdateInstall = true
            try {
                unknownSourceLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:$packageName".toUri(),
                    ),
                )
            } catch (_: ActivityNotFoundException) {
                pendingUpdateInstall = false
                updateController.failInstallation("Android не открыл настройку установки из источника.")
            } catch (_: SecurityException) {
                pendingUpdateInstall = false
                updateController.failInstallation("Android запретил открыть настройку установки.")
            }
            return
        }
        launchUpdateInstaller()
    }

    private fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun launchUpdateInstaller() {
        try {
            updateInstallerLauncher.launch(updateController.createInstallIntent())
        } catch (_: ActivityNotFoundException) {
            updateController.failInstallation("Системный установщик APK не найден.")
        } catch (_: SecurityException) {
            updateController.failInstallation("Android запретил запуск системной установки.")
        } catch (error: Exception) {
            updateController.failInstallation(error.message ?: "Не удалось открыть системную установку.")
        }
    }

    companion object {
        const val ACTION_REQUEST_VPN_START =
            "io.github.zapretkvn.android.action.REQUEST_VPN_START"
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}

private fun Intent.externalImportUri(): Uri? {
    if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return null
    val streamUri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
    val clipUri = clipData
        ?.takeIf { it.itemCount == 1 }
        ?.getItemAt(0)
        ?.uri
    val uri = if (action == Intent.ACTION_VIEW) {
        data ?: streamUri ?: clipUri
    } else {
        streamUri ?: clipUri ?: data
    }
    return uri?.takeIf { it.scheme in setOf("content", "file", "android.resource") }
}
