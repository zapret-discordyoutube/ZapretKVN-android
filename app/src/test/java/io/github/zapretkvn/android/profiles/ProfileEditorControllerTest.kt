package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigValidationResult
import io.github.zapretkvn.android.config.ConfigValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditorControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun TestScope.controller(
        state: MutableStateFlow<ProfilesUiState>,
        validator: ConfigValidator = JsonSyntaxValidator,
    ): ProfileEditorController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ProfileEditorController(
            state = state,
            operations = ProfilesOperations(state, this),
            scope = this,
            store = store(),
            validator = validator,
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            textDebounceMillis = 250,
            searchDebounceMillis = 80,
        )
    }

    private fun store() = ProfileStore(
        root = temporaryFolder.newFolder("profiles-${System.nanoTime()}"),
        validator = JsonSyntaxValidator,
        writer = JvmAtomicProfileWriter(),
    )

    private fun stateWithEditor(text: String = TWO_SERVERS) = MutableStateFlow(
        ProfilesUiState(
            editor = ProfileEditorState(
                profileId = "p1",
                profileName = "Профиль",
                originalText = text,
                text = text,
            ),
        ),
    )

    @Test
    fun `typing updates text at once but analysis waits for the pause`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)

        controller.updateText(ONE_SERVER)

        assertEquals(ONE_SERVER, state.value.editor?.text)
        assertTrue(state.value.editor!!.selectors.isEmpty())
        advanceTimeBy(249)
        runCurrent()
        assertTrue(state.value.editor!!.selectors.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf("a"), state.value.editor?.serverTags)
        assertEquals(listOf("a"), state.value.editor?.selectors?.single()?.outbounds)
    }

    @Test
    fun `burst of keystrokes keeps only the result for the latest text`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)

        controller.updateText(TWO_SERVERS)
        advanceTimeBy(100)
        controller.updateText(ONE_SERVER)
        advanceTimeBy(100)
        controller.updateText("{broken")
        advanceTimeBy(100)
        controller.updateText(TWO_SERVERS)
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), state.value.editor?.serverTags)
        assertEquals(TWO_SERVERS, state.value.editor?.text)
    }

    @Test
    fun `typing clears an earlier validation result`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)
        controller.validate()
        advanceUntilIdle()
        assertTrue(state.value.editor!!.validationSuccessful)

        controller.updateText(ONE_SERVER)

        assertNull(state.value.editor?.validationMessage)
        assertFalse(state.value.editor!!.validationSuccessful)
        advanceUntilIdle()
    }

    @Test
    fun `search matches are counted off the keystroke path and follow the text`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)

        controller.updateSearch("EXAMPLE")
        assertEquals(0, state.value.editor?.searchMatches)
        advanceUntilIdle()
        assertEquals(2, state.value.editor?.searchMatches)

        controller.updateText(ONE_SERVER)
        advanceUntilIdle()
        assertEquals(1, state.value.editor?.searchMatches)

        controller.updateSearch("")
        assertEquals(0, state.value.editor?.searchMatches)
        advanceUntilIdle()
        assertEquals(0, state.value.editor?.searchMatches)
    }

    @Test
    fun `search alone does not reparse an unchanged text`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)
        controller.updateText(ONE_SERVER)
        advanceUntilIdle()
        val selectors = state.value.editor!!.selectors

        controller.updateSearch("a")
        advanceUntilIdle()

        assertTrue(selectors === state.value.editor!!.selectors)
    }

    @Test
    fun `format reports a syntax error without touching the text`() = runTest {
        val state = stateWithEditor("{broken")
        val controller = controller(state)

        controller.format()

        assertEquals("{broken", state.value.editor?.text)
        assertEquals("Сначала исправьте синтаксис JSON.", state.value.editor?.validationMessage)
        assertFalse(state.value.editor!!.validationSuccessful)
        advanceUntilIdle()
    }

    @Test
    fun `format rewrites the text and reports success`() = runTest {
        val state = stateWithEditor("{\"outbounds\":[]}")
        val controller = controller(state)

        controller.format()

        assertTrue(state.value.editor!!.text.contains("\n"))
        assertEquals("JSON отформатирован.", state.value.editor?.validationMessage)
        assertTrue(state.value.editor!!.validationSuccessful)
        advanceUntilIdle()
    }

    @Test
    fun `validate shows core message for invalid config and does not mark busy`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state, ConfigValidator { ConfigValidationResult.Invalid("нет outbound") })

        controller.validate()
        runCurrent()
        assertFalse(state.value.busy)
        advanceUntilIdle()

        assertEquals("нет outbound", state.value.editor?.validationMessage)
        assertFalse(state.value.editor!!.validationSuccessful)
    }

    @Test
    fun `selecting a server rewrites default and refreshes selectors immediately after`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)

        controller.selectServer("zapret-proxy", "b")
        advanceUntilIdle()

        val editor = state.value.editor!!
        assertEquals("b", editor.selectors.single().default)
        assertTrue(editor.validationSuccessful)
        assertEquals("Выбор записан в selector.default. Нажмите «Сохранить».", editor.validationMessage)
    }

    @Test
    fun `selecting an unknown selector reports an error`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)

        controller.selectServer("missing", "b")

        assertFalse(state.value.editor!!.validationSuccessful)
        assertEquals(TWO_SERVERS, state.value.editor?.text)
        advanceUntilIdle()
    }

    @Test
    fun `close refuses unsaved changes unless forced`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)
        controller.updateText(ONE_SERVER)

        assertFalse(controller.close())
        assertTrue(state.value.editor != null)
        assertTrue(controller.close(force = true))
        assertNull(state.value.editor)
        advanceUntilIdle()
    }

    @Test
    fun `closed editor ignores late analysis`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)
        controller.updateText(ONE_SERVER)
        controller.close(force = true)

        advanceUntilIdle()

        assertNull(state.value.editor)
    }

    @Test
    fun `rename of the open profile updates the editor title`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)

        controller.onProfileRenamed("p1", "  Новое  ")
        controller.onProfileRenamed("other", "Чужое")

        assertEquals("Новое", state.value.editor?.profileName)
    }

    @Test
    fun `delete of the open profile closes the editor even with unsaved changes`() = runTest {
        val state = stateWithEditor()
        val controller = controller(state)
        controller.updateText(ONE_SERVER)

        controller.onProfileDeleted("other")
        assertTrue(state.value.editor != null)
        controller.onProfileDeleted("p1")
        assertNull(state.value.editor)
        advanceUntilIdle()
    }

    @Test
    fun `open save and restore backup round trip through the store`() = runBlocking {
        val state = MutableStateFlow(ProfilesUiState())
        val scope = CoroutineScope(Dispatchers.Default + Job()).also(scopes::add)
        val store = store()
        store.initialize()
        val metadata = store.create("Профиль", TWO_SERVERS, ProfileSource.RawJson)
        val controller = ProfileEditorController(
            state, ProfilesOperations(state, scope), scope, store, JsonSyntaxValidator,
            textDebounceMillis = 1, searchDebounceMillis = 1,
        )

        controller.open(metadata.id)
        awaitCondition { state.value.editor != null }
        val opened = state.value.editor!!
        assertEquals(listOf("a", "b"), opened.serverTags)
        assertFalse(opened.hasBackup)

        controller.updateText(ONE_SERVER)
        awaitCondition { state.value.editor?.serverTags == listOf("a") }
        assertTrue(state.value.editor!!.hasUnsavedChanges)

        controller.save()
        awaitCondition { state.value.message == "Профиль сохранён." }
        val saved = state.value.editor!!
        assertFalse(saved.hasUnsavedChanges)
        assertTrue(saved.hasBackup)
        assertEquals(ONE_SERVER, store.read(metadata.id).json)

        controller.restoreBackup()
        awaitCondition { state.value.message?.startsWith("Backup восстановлен") == true }
        assertEquals(TWO_SERVERS, state.value.editor?.text)
        assertEquals(listOf("a", "b"), state.value.editor?.serverTags)
    }

    private companion object {
        const val ONE_SERVER = """{"outbounds":[
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"selector","tag":"zapret-proxy","outbounds":["a"],"default":"a"}]}"""
        const val TWO_SERVERS = """{"outbounds":[
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"vless","tag":"b","server":"b.example","server_port":443},
            {"type":"selector","tag":"zapret-proxy","outbounds":["a","b"],"default":"a"}]}"""
    }
}
