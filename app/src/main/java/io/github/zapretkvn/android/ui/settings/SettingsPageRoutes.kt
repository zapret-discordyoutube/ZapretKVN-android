package io.github.zapretkvn.android.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.network.CurrentWifiSsidReader
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/** Сторона Android: проверка разрешения и чтение имени текущей сети. */
@Composable
internal fun AutomationRoute(deps: AppDependencies, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var readingCurrentWifi by remember { mutableStateOf(false) }
    var wifiMessage by remember { mutableStateOf<WifiMessage?>(null) }
    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    fun addCurrentWifi() {
        scope.launch {
            readingCurrentWifi = true
            wifiMessage = null
            val ssid = withContext(Dispatchers.IO) {
                runCatching { CurrentWifiSsidReader(context).read() }.getOrNull()
            }
            wifiMessage = if (ssid == null) {
                WifiMessage.nameUnavailable()
            } else {
                deps.profiles.settings.addTrustedWifi(ssid)
                WifiMessage.added(ssid)
            }
            readingCurrentWifi = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationPermissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (locationPermissionGranted) addCurrentWifi() else wifiMessage = WifiMessage.permissionDenied()
    }

    AutomationScreen(
        contentPadding = padding,
        settings = settings.networkAutomation,
        readingCurrentWifi = readingCurrentWifi,
        wifiMessage = wifiMessage,
        onEnabledChange = deps.profiles.settings::setNetworkAutomationEnabled,
        onTransportChange = deps.profiles.settings::setUseVpnOnNetwork,
        onPauseOnTrustedChange = deps.profiles.settings::setPauseOnTrustedWifi,
        onAddCurrentWifi = {
            if (locationPermissionGranted) {
                addCurrentWifi()
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            }
        },
        onAddWifi = deps.profiles.settings::addTrustedWifi,
        onRemoveWifi = deps.profiles.settings::removeTrustedWifi,
    )
}

@Composable
internal fun DnsRoute(deps: AppDependencies, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    var cacheClearRequested by rememberSaveable { mutableStateOf(false) }
    DnsScreen(
        contentPadding = padding,
        dnsMode = settings.dnsMode,
        ipv4Only = settings.proxyIpv4Only,
        dnsOverride = settings.dnsOverride,
        cacheClearRequested = cacheClearRequested,
        onDnsModeChange = deps.profiles.settings::setDnsMode,
        onIpv4OnlyChange = deps.profiles.settings::setProxyIpv4Only,
        onOverrideEnabledChange = deps.profiles.settings::setDnsOverrideEnabled,
        onOverrideSave = deps.profiles.settings::setDnsOverride,
        onClearCache = {
            cacheClearRequested = true
            deps.vpn.clearDnsCache()
        },
    )
}

@Composable
internal fun ServiceChecksRoute(deps: AppDependencies, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    ServiceChecksScreen(
        contentPadding = padding,
        selected = settings.serviceChecks,
        onToggle = deps.profiles.settings::setServiceCheck,
    )
}

@Composable
internal fun HidingRoute(deps: AppDependencies, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    HidingScreen(
        contentPadding = padding,
        options = settings.vpnHiding,
        onBlockLocalEndpointsChange = deps.profiles.settings::setVpnHidingBlockLocalEndpoints,
        onNeutralSessionNameChange = deps.profiles.settings::setVpnHidingNeutralSessionName,
        onMtuModeChange = deps.profiles.settings::setVpnHidingTunMtuMode,
    )
}

@Composable
internal fun DiagnosticsRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    ReportDiagnosticsShown(nav)
    val vpnState by deps.vpn.state.collectAsStateWithLifecycle()
    val diagnostics by deps.vpn.diagnostics.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        contentPadding = padding,
        appInfo = CurrentAppInfo,
        vpnState = vpnState,
        diagnostics = diagnostics,
        onCreateDiagnosticShare = deps.createDiagnosticShare,
        onClearDnsCache = deps.vpn::clearDnsCache,
    )
}

@Composable
internal fun UpdatesRoute(deps: AppDependencies, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    // Презентер вызывается в потоке, а не в composable: при загрузке состояние
    // меняется часто, и пересчёт нужен один раз на значение.
    val ui by remember(deps.updates) {
        deps.updates.state.map(::presentUpdate)
    }.collectAsStateWithLifecycle(
        initialValue = remember(deps.updates) { presentUpdate(deps.updates.state.value) },
    )
    val channel = settings.updateChannel
    UpdatesScreen(
        contentPadding = padding,
        currentVersion = CurrentAppInfo.versionName,
        channel = channel,
        ui = ui,
        onChannelChange = deps.profiles.settings::setUpdateChannel,
        onCheck = { deps.updates.check(channel) },
        onDownload = deps.updates::download,
        onInstall = deps.installUpdate,
        onCancel = deps.updates::cancelAndDelete,
    )
}

/** «Дополнительно»: страницы и вид редактора JSON. */
@Composable
internal fun AdvancedRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    AdvancedScreen(
        contentPadding = padding,
        hidingSummary = vpnHidingSummary(settings.vpnHiding),
        rawEditorLineWrap = settings.rawEditorLineWrap,
        onOpenPage = { page -> nav.openSettingsPage(page.id) },
        onRawEditorLineWrapChange = deps.profiles.settings::setRawEditorLineWrap,
    )
}
