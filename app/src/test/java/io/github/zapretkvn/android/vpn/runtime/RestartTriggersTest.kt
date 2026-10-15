package io.github.zapretkvn.android.vpn.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartTriggersTest {
    @Test
    fun `no known reason maps to the generic restart trigger`() {
        RestartTriggers.KNOWN.forEach { (reason, trigger) ->
            assertFalse("reason '$reason' falls into the generic trigger", trigger == RestartTriggers.GENERIC)
            assertEquals(trigger, RestartTriggers.fromReason(reason))
        }
    }

    @Test
    fun `every known reason has its own trigger`() {
        val triggers = RestartTriggers.KNOWN.values
        assertEquals("duplicate trigger", triggers.size, triggers.toSet().size)
        triggers.forEach { assertTrue(it, it.matches(Regex("[a-z][a-z0-9_]{2,39}"))) }
    }

    @Test
    fun `unknown text keeps the generic trigger`() {
        assertEquals("restart", RestartTriggers.fromReason("что-то новое"))
    }

    /**
     * Причины перезапуска разбросаны по коду литералами. Тест находит их в исходниках: новая
     * причина без записи в [RestartTriggers.KNOWN] роняет тест, а не молча попадает в `restart`.
     */
    @Test
    fun `every reason literal in the sources has a dedicated trigger`() {
        val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val patterns = listOf(
            Regex("""restartIfConnected\("([^"]+)"\)"""),
            Regex("""\bapply\("([^"]+)"\)"""),
            Regex("""ifBlank \{ "([^"]+)" \}"""),
            Regex("""restartIntent\([^")]*"([^"]+)"\)"""),
            Regex("""reason = (?:if \([^)]*\) )?"([^"]+)"(?: else "([^"]+)")?"""),
        )
        val reasonFiles = setOf(
            "MainActivity.kt", "ProfileSettingsActions.kt", "ImportController.kt", "RoutingViewModel.kt",
            "ZapretVpnService.kt", "VpnController.kt", "RecoveryCoordinator.kt", "ServerSwitchCoordinator.kt",
            "NetworkAutomationCoordinator.kt", "VpnRuntime.kt",
        )
        val found = sortedSetOf<String>()
        sources.filter { it.name in reasonFiles }.forEach { file ->
            val text = file.readText()
            patterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    match.groupValues.drop(1).filter(String::isNotEmpty).forEach(found::add)
                }
            }
        }
        // Эти литералы - не причины перезапуска.
        val ignored = setOf("restart_unused")
        val reasons = found - ignored
        assertTrue("source scan found nothing: $found", reasons.size >= 15)
        val unmapped = reasons.filter { RestartTriggers.fromReason(it) == RestartTriggers.GENERIC }
        assertTrue("reasons without a trigger: $unmapped", unmapped.isEmpty())
        // Известные причины из постановки задачи найдены сканированием.
        listOf(
            "Перезапуск пользователем", "Перезапуск из уведомления", "Группа подписки обновлена пользователем",
            "Смена DNS-переопределения", "Временный маршрут updater", "Завершение маршрута updater",
            "Подключение до смены сети", "Автоматическое подключение по сети",
            "Перезапуск после сбоя управления ядром", "Перезапуск VPN",
        ).forEach { assertTrue("not found in sources: $it", it in reasons) }
    }
}
