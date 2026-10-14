package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import io.github.zapretkvn.core.designsystem.Spacing

/**
 * Четыре источника профиля: файл, буфер, ссылка, QR. Один набор лаунчеров и
 * диалогов на оба экрана — и пустую «Серверы», и «Профили».
 */
@Stable
class ProfileImporter(
    val pickFile: () -> Unit,
    val pasteClipboard: () -> Unit,
    val askUrl: () -> Unit,
    val askQr: () -> Unit,
)

/** Четыре крупные кнопки источников: «Файл», «Буфер», «Ссылка», «QR». */
@Composable
fun ImportSourceButtons(importer: ProfileImporter, enabled: Boolean, modifier: Modifier = Modifier) {
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
