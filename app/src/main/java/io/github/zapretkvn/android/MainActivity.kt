package io.github.zapretkvn.android

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.ZapretRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import io.github.zapretkvn.android.apps.AppsViewModel
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.routing.RoutingViewModel
import io.github.zapretkvn.android.ui.ThemeMode
import io.github.zapretkvn.core.designsystem.ZapretTheme
import io.github.zapretkvn.android.updates.UpdateState
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnController

class MainActivity : ComponentActivity() {
    private val vpnController: VpnController
        get() = (application as ZapretApplication).container.vpnController
    private var pendingProfileId: String? = null
    private var pendingUpdateInstall = false
    private var installWhenReady = false
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
    // Итог подтверждения приходит ещё и широковещательным сообщением сессии установки: здесь он не нужен.
    private val updateConfirmationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {}
    private val unknownSourceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val shouldContinue = pendingUpdateInstall
        pendingUpdateInstall = false
        if (shouldContinue && canRequestPackageInstalls()) {
            performUpdateInstall()
        } else if (shouldContinue) {
            vpnController.publishMessage("Android не разрешил установку из этого источника.")
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
        val container = (application as ZapretApplication).container
        val dependencies = AppDependencies(
            profiles = profilesViewModel,
            apps = appsViewModel,
            routing = routingViewModel,
            vpn = vpnController,
            updates = updateController,
            startVpn = ::requestVpnStart,
            restartVpn = {
                if (vpnController.state.value is VpnConnectionState.Paused) {
                    vpnController.resumePaused()
                } else {
                    vpnController.restartIfConnected("Перезапуск пользователем")
                }
            },
            installUpdate = ::requestUpdateInstall,
            createDiagnosticShare = { container.diagnosticExporter.createShareIntent() },
        )
        observeUpdates()
        val initialSettings = profilesViewModel.state.value.settings
        val initialAppearance = initialSettings.themeMode to initialSettings.dynamicColor
        setContent {
            // Корень читает только оформление: остальное собирают сами экраны.
            val appearance by remember {
                profilesViewModel.state
                    .map { it.settings.themeMode to it.settings.dynamicColor }
                    .distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = initialAppearance)
            val (themeMode, dynamicColor) = appearance
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.Amoled -> true
            }
            DisposableEffect(darkTheme) {
                val systemBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = systemBarStyle, navigationBarStyle = systemBarStyle)
                onDispose {}
            }
            ZapretTheme(
                darkTheme = darkTheme,
                amoledTheme = themeMode == ThemeMode.Amoled,
                dynamicColor = dynamicColor,
            ) {
                ZapretRoot(dependencies)
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    /**
     * Разовая проверка обновлений при запуске (в Stable обновление скачивается само) и открытие
     * подтверждения установки, когда пользователь уже нажал «Установить».
     */
    private fun observeUpdates() {
        lifecycleScope.launch {
            val settings = profilesViewModel.state.first { it.initialized }.settings
            updateController.checkOnce(settings.updateChannel)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateController.state.collect { updateState ->
                    if (updateState is UpdateState.Ready && installWhenReady) {
                        installWhenReady = false
                        requestUpdateInstall()
                    }
                    if (updateState is UpdateState.Ready && updateState.needsConfirmation &&
                        updateController.consumeConfirmationLaunchRequest()
                    ) {
                        launchUpdateConfirmation()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == ACTION_INSTALL_UPDATE) {
            intent.action = null
            // Процесс мог быть запущен самим уведомлением: проверка ещё не закончилась, установим, когда APK будет готов.
            if (updateController.state.value is UpdateState.Ready) requestUpdateInstall() else installWhenReady = true
            return
        }
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

    /** Установка по нажатию пользователя: плашка на Главной, страница настроек или уведомление. */
    private fun requestUpdateInstall() {
        val state = updateController.state.value as? UpdateState.Ready ?: return
        // Подтверждение уже запрошено системой: разрешение на неизвестные источники проверяет сама Android.
        if (state.needsConfirmation) {
            performUpdateInstall()
            return
        }
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
                vpnController.publishMessage("Android не открыл настройку установки из источника.")
            } catch (_: SecurityException) {
                pendingUpdateInstall = false
                vpnController.publishMessage("Android запретил открыть настройку установки.")
            }
            return
        }
        performUpdateInstall()
    }

    private fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun performUpdateInstall() {
        val state = updateController.state.value as? UpdateState.Ready ?: return
        if (state.needsConfirmation) launchUpdateConfirmation() else updateController.install(userInitiated = true)
    }

    private fun launchUpdateConfirmation() {
        val confirmation = updateController.confirmationIntent() ?: return
        try {
            updateConfirmationLauncher.launch(confirmation)
        } catch (_: ActivityNotFoundException) {
            vpnController.publishMessage("Системный установщик APK не найден.")
        } catch (_: SecurityException) {
            vpnController.publishMessage("Android запретил запуск системной установки.")
        }
    }

    companion object {
        const val ACTION_REQUEST_VPN_START =
            "io.github.zapretkvn.android.action.REQUEST_VPN_START"
        const val EXTRA_PROFILE_ID = "profile_id"

        /** Нажатие на уведомление «Обновление готово — нажмите, чтобы установить». */
        const val ACTION_INSTALL_UPDATE = "io.github.zapretkvn.android.action.INSTALL_UPDATE"
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
