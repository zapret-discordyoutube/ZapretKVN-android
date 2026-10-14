package io.github.zapretkvn.android.profiles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesOperationsTest {
    @Test
    fun `busy is set while a marked operation runs and cleared after`() = runTest {
        val state = MutableStateFlow(ProfilesUiState())
        val operations = ProfilesOperations(state, this)
        val gate = CompletableDeferred<Unit>()

        operations.run { gate.await() }
        runCurrent()
        assertTrue(state.value.busy)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(state.value.busy)
    }

    @Test
    fun `unmarked operation never touches busy`() = runTest {
        val state = MutableStateFlow(ProfilesUiState())
        val operations = ProfilesOperations(state, this)
        val gate = CompletableDeferred<Unit>()

        operations.run(markBusy = false) { gate.await() }
        runCurrent()
        assertFalse(state.value.busy)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `failure becomes a redacted message and clears busy`() = runTest {
        val state = MutableStateFlow(ProfilesUiState())
        val operations = ProfilesOperations(state, this)

        operations.run { error("Сбой операции") }
        advanceUntilIdle()

        assertEquals("Сбой операции", state.value.message)
        assertFalse(state.value.busy)
    }

    @Test
    fun `blank error message falls back and long message is cut`() {
        assertEquals("запас", IllegalStateException(" ").userMessage("запас"))
        assertEquals(320, IllegalStateException("x".repeat(500)).userMessage("запас").length)
    }

    @Test
    fun `consumeMessage clears the message`() = runTest {
        val state = MutableStateFlow(ProfilesUiState(message = "привет"))
        ProfilesOperations(state, this).consumeMessage()

        assertNull(state.value.message)
    }
}
