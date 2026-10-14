package io.github.zapretkvn.android.ui.servers

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import io.github.zapretkvn.android.importer.qrImportScanOptions
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.core.designsystem.Spacing

/**
 * Четыре источника профиля: файл, буфер, ссылка, QR. Один набор лаунчеров и
 * диалогов на оба экрана — и пустую «Серверы», и «Профили».
 */
@Stable
internal class ProfileImporter(
    val pickFile: () -> Unit,
    val pasteClipboard: () -> Unit,
    val askUrl: () -> Unit,
    val askQr: () -> Unit,
)

private val IMPORT_MIME_TYPES = arrayOf(
    "application/json",
    "application/x-wireguard-profile",
    "application/x-amneziawg-profile",
    "text/plain",
    "application/octet-stream",
)

/**
 * Возвращает действия импорта и сама рисует связанные с ними диалоги
 * (ссылка, выбор QR-источника, отказ в камере, подписка из QR).
 */
@Composable
internal fun rememberProfileImporter(
    viewModel: ProfilesViewModel,
    qrSubscriptionUrl: String?,
    hideServerAddresses: Boolean,
): ProfileImporter {
    var urlDialogOpen by remember { mutableStateOf(false) }
    var qrSourceDialogOpen by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importDocument)
    }
    val qrImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importQrImage)
    }
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf(String::isNotBlank)?.let(viewModel::importQr)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) qrLauncher.launch(qrImportScanOptions()) else cameraDenied = true
    }
    val requestCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            qrLauncher.launch(qrImportScanOptions())
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (urlDialogOpen) {
        UrlImportDialog(
            initialUrl = "",
            hideServerAddresses = hideServerAddresses,
            onDismiss = { urlDialogOpen = false },
            onImport = { url, identity ->
                urlDialogOpen = false
                viewModel.importUrl(url, identity)
            },
        )
    }
    qrSubscriptionUrl?.let { qrUrl ->
        UrlImportDialog(
            initialUrl = qrUrl,
            title = "Подписка из QR",
            hideServerAddresses = hideServerAddresses,
            onDismiss = viewModel::closeQrSubscription,
            onImport = viewModel::importQrSubscription,
        )
    }
    if (qrSourceDialogOpen) {
        AlertDialog(
            onDismissRequest = { qrSourceDialogOpen = false },
            title = { Text("Считать QR-код") },
            text = { Text("Выберите камеру или изображение из галереи.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        qrSourceDialogOpen = false
                        requestCamera()
                    },
                ) { Text("Камера") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        qrSourceDialogOpen = false
                        qrImageLauncher.launch(arrayOf("image/*"))
                    },
                ) { Text("Галерея") }
            },
        )
    }
    if (cameraDenied) {
        AlertDialog(
            onDismissRequest = { cameraDenied = false },
            title = { Text("Камера недоступна") },
            text = { Text("Разрешение камеры нужно только на время открытия QR-сканера.") },
            confirmButton = {
                TextButton(onClick = { cameraDenied = false }) { Text("Понятно") }
            },
        )
    }

    return remember(viewModel, fileLauncher) {
        ProfileImporter(
            pickFile = { fileLauncher.launch(IMPORT_MIME_TYPES) },
            pasteClipboard = viewModel::importClipboard,
            askUrl = { urlDialogOpen = true },
            askQr = { qrSourceDialogOpen = true },
        )
    }
}

/** Четыре крупные кнопки источников: «Файл», «Буфер», «Ссылка», «QR». */
@Composable
internal fun ImportSourceButtons(importer: ProfileImporter, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(onClick = importer.pickFile, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("Файл")
            }
            OutlinedButton(onClick = importer.pasteClipboard, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("Буфер")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedButton(onClick = importer.askUrl, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("Ссылка")
            }
            OutlinedButton(onClick = importer.askQr, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("QR")
            }
        }
    }
}
