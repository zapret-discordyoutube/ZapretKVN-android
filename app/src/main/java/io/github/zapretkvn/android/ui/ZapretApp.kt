package io.github.zapretkvn.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.profiles.ProfileEditorState
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.core.uimodel.ScreenshotPrivacy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RawEditorScreen(
    viewModel: ProfilesViewModel,
    editor: ProfileEditorState,
    lineWrap: Boolean,
    hideServerAddresses: Boolean,
    busy: Boolean,
    snackbarHost: @Composable () -> Unit = {},
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    // Sensitive endpoints must be concealed again after Activity recreation.
    var addressesRevealed by remember(editor.profileId, hideServerAddresses) {
        mutableStateOf(!hideServerAddresses)
    }
    val requestClose = {
        if (!viewModel.closeEditor()) confirmDiscard = true
    }
    BackHandler(onBack = requestClose)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(editor.profileName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        // Кнопки — в нижней панели, а сообщения — над ней: снекбар больше не
        // перекрывает «Validate» и «Сохранить».
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Box(
                    Modifier
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = viewModel::formatEditor, enabled = !busy) { Text("Format") }
                        OutlinedButton(onClick = viewModel::validateEditor, enabled = !busy) { Text("Validate") }
                        if (editor.hasBackup) {
                            OutlinedButton(onClick = viewModel::restoreBackup, enabled = !busy) {
                                Text("Restore backup")
                            }
                        }
                        Button(
                            onClick = viewModel::saveEditor,
                            enabled = editor.hasUnsavedChanges && !busy,
                        ) { Text("Сохранить") }
                    }
                }
            }
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = editor.search,
                onValueChange = viewModel::updateSearch,
                label = { Text("Поиск") },
                supportingText = {
                    if (editor.search.isNotBlank()) Text("Совпадений: ${editor.searchMatches}")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SelectorControls(editor, viewModel, hideServerAddresses)

            if (hideServerAddresses) {
                OutlinedButton(onClick = { addressesRevealed = !addressesRevealed }) {
                    Text(
                        if (addressesRevealed) {
                            "Скрыть адреса серверов"
                        } else {
                            "Показать адреса и разрешить редактирование"
                        },
                    )
                }
            }

            val horizontalScroll = rememberScrollState()
            val editorTextVisible = !hideServerAddresses || addressesRevealed
            val displayedText = remember(editor.text, editorTextVisible) {
                if (editorTextVisible) {
                    editor.text
                } else {
                    ScreenshotPrivacy.redactServerAddressesInJson(editor.text)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (lineWrap) Modifier else Modifier.horizontalScroll(horizontalScroll)),
            ) {
                OutlinedTextField(
                    value = displayedText,
                    onValueChange = if (editorTextVisible) {
                        // IME/автозаполнение могут повторно прислать тот же текст; такой вызов не
                        // должен сбрасывать результат проверки.
                        { value -> if (value != editor.text) viewModel.updateEditorText(value) }
                    } else {
                        { _ -> }
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = {
                        Text(if (editorTextVisible) "sing-box JSON" else "sing-box JSON · адреса скрыты")
                    },
                    readOnly = !editorTextVisible,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (lineWrap) Modifier else Modifier.widthIn(min = 1200.dp)),
                )
            }

            editor.validationMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (editor.validationSuccessful) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Отменить изменения?") },
            text = { Text("Несохранённые изменения JSON будут потеряны.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        viewModel.closeEditor(force = true)
                    },
                ) { Text("Отменить изменения") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Продолжить редактирование") }
            },
        )
    }
}

@Composable
private fun SelectorControls(
    editor: ProfileEditorState,
    viewModel: ProfilesViewModel,
    hideServerAddresses: Boolean,
) {
    if (editor.selectors.isEmpty()) {
        if (editor.serverTags.isNotEmpty()) {
            OutlinedButton(onClick = viewModel::createManagedSelector) {
                Text("Создать zapret-proxy из ${editor.serverTags.size} серверов")
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        editor.selectors.forEach { selector ->
            Text(selector.tag, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                selector.outbounds.forEach { server ->
                    FilterChip(
                        selected = selector.default == server,
                        onClick = { viewModel.selectServer(selector.tag, server) },
                        label = {
                            Text(ScreenshotPrivacy.serverLabel(server, hideServerAddresses))
                        },
                    )
                }
            }
        }
        HorizontalDivider()
    }
}
