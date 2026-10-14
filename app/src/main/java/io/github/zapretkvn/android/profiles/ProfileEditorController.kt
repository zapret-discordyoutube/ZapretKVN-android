package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.ConfigValidationResult
import io.github.zapretkvn.android.config.ConfigValidator
import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Редактор сырого JSON профиля: текст, поиск, проверка, сохранение и backup.
 *
 * Сам текст меняется мгновенно, а производные данные (selector-группы, теги серверов,
 * число совпадений поиска) пересчитываются в [defaultDispatcher] после паузы [textDebounceMillis];
 * в состояние попадает только результат для самого свежего текста и запроса.
 */
internal class ProfileEditorController(
    private val state: MutableStateFlow<ProfilesUiState>,
    private val operations: ProfilesOperations,
    private val scope: CoroutineScope,
    private val store: ProfileStore,
    private val validator: ConfigValidator,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val textDebounceMillis: Long = TEXT_DEBOUNCE_MILLIS,
    private val searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
) {
    private var analysisJob: Job? = null

    /** Текст, для которого в состоянии лежат актуальные selectors/serverTags. */
    private var structureText: String? = null

    fun open(id: String) = operations.run {
        val profile = store.read(id)
        val editor = load(profile)
        state.update { it.copy(editor = editor, message = null) }
    }

    fun close(force: Boolean = false): Boolean {
        val editor = state.value.editor ?: return true
        if (editor.hasUnsavedChanges && !force) return false
        analysisJob?.cancel()
        state.update { it.copy(editor = null) }
        return true
    }

    fun updateText(text: String) = setText(text, textDebounceMillis)

    fun updateSearch(search: String) {
        var changed = false
        state.update { current ->
            val editor = current.editor ?: return@update current
            changed = true
            val matches = if (search.isBlank()) 0 else editor.searchMatches
            current.copy(editor = editor.copy(search = search, searchMatches = matches))
        }
        if (changed) scheduleAnalysis(searchDebounceMillis)
    }

    fun format() {
        val editor = state.value.editor ?: return
        try {
            setText(JsonConfig.format(editor.text), delayMillis = 0)
            setValidation("JSON отформатирован.", true)
        } catch (_: Exception) {
            setValidation("Сначала исправьте синтаксис JSON.", false)
        }
    }

    fun validate() = operations.run(markBusy = false) {
        val editor = state.value.editor ?: return@run
        when (val result = withContext(ioDispatcher) { validator.validate(editor.text) }) {
            ConfigValidationResult.Valid -> setValidation("Конфигурация корректна.", true)
            is ConfigValidationResult.Invalid -> setValidation(result.message, false)
        }
    }

    fun save() = operations.run {
        val editor = state.value.editor ?: return@run
        store.update(editor.profileId, editor.text)
        reload(editor.profileId)
        operations.showMessage("Профиль сохранён.")
    }

    fun restoreBackup() = operations.run {
        val editor = state.value.editor ?: return@run
        store.restoreBackup(editor.profileId)
        reload(editor.profileId)
        operations.showMessage("Backup восстановлен; прежняя текущая версия стала backup.")
    }

    fun selectServer(selectorTag: String, serverTag: String) {
        val editor = state.value.editor ?: return
        try {
            setText(ConfigAnalyzer.selectServer(editor.text, selectorTag, serverTag), delayMillis = 0)
            setValidation("Выбор записан в selector.default. Нажмите «Сохранить».", true)
        } catch (error: Exception) {
            setValidation(error.userMessage("Не удалось выбрать сервер."), false)
        }
    }

    fun createManagedSelector() {
        val editor = state.value.editor ?: return
        try {
            // Пока идёт пауза после ввода, serverTags в состоянии могут отставать от текста.
            val serverTags = if (structureText == editor.text) {
                editor.serverTags
            } else {
                EditorTextAnalysis.structure(editor.text).serverTags
            }
            setText(ConfigAnalyzer.addManagedSelector(editor.text, serverTags), delayMillis = 0)
            setValidation("zapret-proxy добавлен явно. Нажмите «Сохранить».", true)
        } catch (error: Exception) {
            setValidation(error.userMessage("Не удалось создать selector."), false)
        }
    }

    /** Профиль переименован в списке: открытый редактор показывает новое имя. */
    fun onProfileRenamed(id: String, name: String) {
        state.update { current ->
            val editor = current.editor?.takeIf { it.profileId == id } ?: return@update current
            current.copy(editor = editor.copy(profileName = name.trim()))
        }
    }

    /** Профиль удалён: если он открыт в редакторе, редактор закрывается без вопросов. */
    fun onProfileDeleted(id: String) {
        if (state.value.editor?.profileId == id) close(force = true)
    }

    private suspend fun reload(profileId: String) {
        val stored = store.read(profileId)
        val editor = load(stored)
        state.update { it.copy(editor = editor) }
    }

    private suspend fun load(profile: StoredProfile): ProfileEditorState {
        analysisJob?.cancel()
        val structure = withContext(defaultDispatcher) { EditorTextAnalysis.structure(profile.json) }
        structureText = profile.json
        return ProfileEditorState(
            profileId = profile.metadata.id,
            profileName = profile.metadata.name,
            originalText = profile.json,
            text = profile.json,
            selectors = structure.selectors,
            serverTags = structure.serverTags,
            hasBackup = profile.hasBackup,
        )
    }

    private fun setText(text: String, delayMillis: Long) {
        var changed = false
        state.update { current ->
            val editor = current.editor ?: return@update current
            changed = true
            current.copy(
                editor = editor.copy(
                    text = text,
                    validationMessage = null,
                    validationSuccessful = false,
                ),
            )
        }
        if (changed) scheduleAnalysis(delayMillis)
    }

    private fun setValidation(message: String, successful: Boolean) {
        state.update { current ->
            current.copy(
                editor = current.editor?.copy(
                    validationMessage = message,
                    validationSuccessful = successful,
                ),
            )
        }
    }

    /** Пересчитывает производные данные для последнего текста и запроса; предыдущий расчёт отменяется. */
    private fun scheduleAnalysis(delayMillis: Long) {
        analysisJob?.cancel()
        analysisJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            val snapshot = state.value.editor ?: return@launch
            val text = snapshot.text
            val search = snapshot.search
            val reuseStructure = structureText == text
            val result = withContext(defaultDispatcher) {
                val structure = if (reuseStructure) null else EditorTextAnalysis.structure(text)
                structure to EditorTextAnalysis.countMatches(text, search)
            }
            val (structure, matches) = result
            var applied = false
            state.update { current ->
                applied = false
                val editor = current.editor
                if (editor == null || editor.profileId != snapshot.profileId ||
                    editor.text != text || editor.search != search
                ) {
                    return@update current
                }
                applied = true
                current.copy(
                    editor = editor.copy(
                        selectors = structure?.selectors ?: editor.selectors,
                        serverTags = structure?.serverTags ?: editor.serverTags,
                        searchMatches = matches,
                    ),
                )
            }
            if (applied && structure != null) structureText = text
        }
    }

    companion object {
        const val TEXT_DEBOUNCE_MILLIS = 250L
        const val SEARCH_DEBOUNCE_MILLIS = 80L
    }
}
