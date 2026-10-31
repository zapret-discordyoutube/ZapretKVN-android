package io.github.zapretkvn.android.ui

import androidx.compose.material3.SnackbarDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class AppMessageTimingTest {
    @Test
    fun `short confirmation goes away quickly`() {
        assertEquals(SnackbarDuration.Short, AppMessageTiming.duration("Подписка обновлена"))
    }

    @Test
    fun `explanation in two lines stays long enough to read`() {
        val message = "Сервер не ответил. Соединение осталось на прежнем сервере, попробуйте другой."
        assertEquals(SnackbarDuration.Long, AppMessageTiming.duration(message))
    }

    @Test
    fun `surrounding spaces do not make a message long`() {
        val message = "  " + "x".repeat(AppMessageTiming.SHORT_MAX_CHARS) + "  "
        assertEquals(SnackbarDuration.Short, AppMessageTiming.duration(message))
    }
}
