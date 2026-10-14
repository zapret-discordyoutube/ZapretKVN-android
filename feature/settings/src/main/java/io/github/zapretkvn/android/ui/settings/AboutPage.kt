package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.FactRow
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons

// Формулировки синхронизированы с docs/README.md и заметками к выпуску
// (scripts/verify-project.sh проверяет ключевые термины) — менять вместе.
val KnownLimitations: List<String> = listOf(
    "Android 8+; release APK разделены на arm64-v8a, armeabi-v7a и x86_64, интерфейс рассчитан на телефоны.",
    "Always-on/Lockdown и shared UID не поддерживаются как гарантированный per-app режим.",
    "Rootless-защита не скрывает системный TRANSPORT_VPN, TUN и установленный APK.",
    "Managed DNS перехватывает TCP/UDP 53, но не встроенный DoH, DoT или mDNS; FakeIP выключен.",
    "Domain-only block не является firewall: для гарантии нужен IP/CIDR rule-set.",
    "Clash YAML и Hysteria v1 URI пока не импортируются; raw JSON остаётся ответственностью пользователя.",
    "Обновления проверяются в фоне (при запуске и раз в сутки) и скачиваются сами, по возможности только " +
        "изменившимися частями. На Android 12+ установка проходит без подтверждения, когда приложение уже " +
        "ставило себя через этот установщик, иначе silent install недоступен и нужно одно нажатие. " +
        "Подписки не обновляются в фоне, core обновляется вместе с APK.",
    "При неработающем proxy/DNS VPN закрывается без бесконечного retry и plaintext DNS fallback.",
)

const val LICENSE_NOTICE =
    "Лицензия приложения: GPL-3.0-or-later. Распространение закрытых производных APK запрещено; " +
        "полный соответствующий исходный код обязателен. Название и фирменное оформление " +
        "Zapret KVN не лицензируются GPL. Лицензии ядра и библиотек включены в APK и NOTICE."

@Composable
fun AboutScreen(contentPadding: PaddingValues, appInfo: AppInfo) {
    var limitationsExpanded by rememberSaveable { mutableStateOf(false) }
    SettingsPageList(contentPadding, tag = "settings-page-about") {
        item(key = "app", contentType = "card") {
            ZapretCard {
                Text("Zapret KVN", style = MaterialTheme.typography.headlineSmall)
                FactRow("Версия", "${appInfo.versionName} (${appInfo.versionCode})")
                HintText(
                    "Независимый Android-клиент и GUI для sing-box-extended. " +
                        "Конфигурация хранится как настоящий sing-box JSON.",
                )
            }
        }
        item(key = "core", contentType = "card") {
            ZapretCard {
                CardTitle("Ядро", icon = ZapretIcons.Code)
                FactRow("Сборка ядра", appInfo.coreTag)
                FactRow("Ревизия", appInfo.coreCommit.take(12))
                HintText("Ядро встроено в APK из зафиксированной ревизии; динамической загрузки ядра нет.")
            }
        }
        item(key = "limitations", contentType = "collapsible") {
            CollapsibleCard(
                title = "Известные ограничения MVP",
                summary = "${KnownLimitations.size} пунктов",
                expanded = limitationsExpanded,
                onToggle = { limitationsExpanded = !limitationsExpanded },
            ) {
                KnownLimitations.forEach { item ->
                    Text("• $item", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item(key = "license", contentType = "text") { HintText(LICENSE_NOTICE) }
    }
}
