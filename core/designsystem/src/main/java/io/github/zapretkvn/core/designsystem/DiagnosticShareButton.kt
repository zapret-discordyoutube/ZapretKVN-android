package io.github.zapretkvn.core.designsystem

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.service.chooser.ChooserAction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val CHOOSER_ACTIONS_EXTRA = "io.github.zapretkvn.android.extra.CHOOSER_ACTIONS"

/** Создаёт redacted diagnostic JSON и открывает системное окно отправки. */
@Composable
fun DiagnosticShareButton(
    label: String,
    onCreateDiagnosticShare: suspend () -> Intent,
    modifier: Modifier = Modifier,
    testTag: String = "export-diagnostics",
    /** Окно отправки открыто: экран может предложить следующий шаг (например, страницу обращений). */
    onShared: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    Column(modifier = modifier) {
        OutlinedButton(
            enabled = !exporting,
            onClick = {
                scope.launch {
                    exporting = true
                    exportError = null
                    try {
                        val shareIntent = onCreateDiagnosticShare()
                        val chooser = Intent.createChooser(shareIntent, "Передать диагностику")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            // Действия окна отправки («Сохранить в Загрузки») кладёт экспортёр на передаваемый intent.
                            shareIntent.getParcelableArrayExtra(CHOOSER_ACTIONS_EXTRA, ChooserAction::class.java)?.let {
                                chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, it)
                            }
                        }
                        context.startActivity(chooser)
                        onShared()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: ActivityNotFoundException) {
                        exportError = "Не найдено приложение для передачи файла."
                    } catch (_: SecurityException) {
                        exportError = "Android запретил передачу файла."
                    } catch (_: Throwable) {
                        exportError = "Не удалось создать диагностический файл."
                    } finally {
                        exporting = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
        ) {
            if (exporting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(label)
        }
        exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
