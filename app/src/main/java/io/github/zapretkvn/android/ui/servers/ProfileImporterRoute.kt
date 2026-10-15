package io.github.zapretkvn.android.ui.servers

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import io.github.zapretkvn.android.importer.qrImportScanOptions
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.profiles.SubscriptionLinkPrompt

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
    subscriptionLinkPrompt: SubscriptionLinkPrompt?,
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
    subscriptionLinkPrompt?.let { prompt ->
        UrlImportDialog(
            initialUrl = prompt.url,
            title = prompt.origin.title,
            hideServerAddresses = hideServerAddresses,
            onDismiss = viewModel::closeSubscriptionLinkPrompt,
            onImport = viewModel::importPromptedSubscription,
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
