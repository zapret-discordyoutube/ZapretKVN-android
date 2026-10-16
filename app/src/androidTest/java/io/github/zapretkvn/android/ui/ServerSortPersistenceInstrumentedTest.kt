package io.github.zapretkvn.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Сортировка вкладки «Серверы» переживает перезапуск, а не сбрасывается на «как в подписке». */
@RunWith(AndroidJUnit4::class)
class ServerSortPersistenceInstrumentedTest {
    @Test
    fun chosenSortIsReadBackByAFreshStore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = UiSettingsStore(context)
        val before = store.settings.first().serverSort
        try {
            store.setServerSort("Fastest")
            // Новый экземпляр читает тот же файл — как приложение после перезапуска.
            assertEquals("Fastest", UiSettingsStore(context).settings.first().serverSort)
            store.setServerSort("Name")
            assertEquals("Name", UiSettingsStore(context).settings.first().serverSort)
        } finally {
            store.setServerSort(before)
        }
    }
}
