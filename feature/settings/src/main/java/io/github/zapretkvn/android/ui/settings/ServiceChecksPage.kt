package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.zapretkvn.android.network.probes.services.ServiceCatalog
import io.github.zapretkvn.android.network.probes.services.ServiceId
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons

/**
 * «Проверка сервисов»: какие сервисы проверяет карточка на Главной. Чем меньше отмечено,
 * тем меньше запросов в одной проверке. Последний отмеченный сервис выключить нельзя.
 */
@Composable
fun ServiceChecksScreen(
    contentPadding: PaddingValues,
    selected: Set<ServiceId>,
    onToggle: (ServiceId, Boolean) -> Unit,
) {
    SettingsPageList(contentPadding, tag = "settings-page-services") {
        item(key = "intro", contentType = "card") {
            ZapretCard {
                CardTitle("Какие сервисы проверять", icon = ZapretIcons.Globe)
                HintText(
                    "Карточка «Доступность сервисов» на Главной проверяет только отмеченные сервисы. " +
                        "Чем их меньше, тем меньше запросов. Проверка идёт только по нажатию; " +
                        "адреса берутся из этого списка, а не из ваших профилей. Результаты нигде не сохраняются.",
                )
            }
        }
        item(key = "services", contentType = "rows") {
            RowsCard {
                ServiceCatalog.all.forEachIndexed { index, definition ->
                    if (index > 0) RowDivider()
                    val checked = definition.id in selected
                    SwitchRow(
                        title = definition.id.title,
                        subtitle = serviceCheckHosts(definition.id),
                        checked = checked,
                        // Хотя бы один сервис остаётся: пустая проверка ничего бы не показала.
                        enabled = !(checked && selected.size <= 1),
                        onCheckedChange = { onToggle(definition.id, it) },
                        modifier = Modifier.testTag("service-check-${definition.id.token}"),
                        switchDescription = "Проверять ${definition.id.title}",
                    )
                }
            }
        }
    }
}
