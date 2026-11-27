package io.github.zapretkvn.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.core.uimodel.ProviderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Разбор имени провайдера на устройстве (движок регулярных выражений Android — ICU) читает настоящие имена владельца так
 * же, как Windows-версия. Тот же файл проверяет JVM-тест `ProviderNameTest`: на JVM он прошёл, а на устройстве падал, потому
 * что ICU не принимает флаг `UNICODE_CHARACTER_CLASS` (2026-11-27).
 */
@RunWith(AndroidJUnit4::class)
class ProviderNameDeviceInstrumentedTest {
    @Test
    fun everyRealNameIsReadAsTheWindowsParserReadsIt() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val rows = assets.open("provider_names.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .split('\n').filter { it.isNotEmpty() }
        assertTrue("names: ${rows.size}", rows.size > 1500)
        val wrong = rows.mapNotNull { line ->
            val c = line.split('\t', limit = 5)
            val expected = ProviderName(flag = c[1], server = c[2], variant = c[3], via = c[4])
            val got = ProviderName.parse(c[0])
            if (got == expected) null else "${c[0]} -> $got, expected $expected"
        }
        assertEquals("names read differently on the device: ${wrong.take(6)}", 0, wrong.size)
    }
}
