package io.github.zapretkvn.android.ui.routing

import androidx.compose.runtime.Immutable
import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.apps.AppsUiState
import io.github.zapretkvn.android.routing.ManagedRoutingRule
import io.github.zapretkvn.android.routing.RoutingMatchType
import io.github.zapretkvn.android.routing.RoutingRuleAction
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.uimodel.countLabel

private const val OTHER_APPS_MARKER = "Остальные приложения:"
private const val EXCLUDE_SUMMARY_TAIL =
    "Приложения напрямую: вне VPN и TUN; все остальные входят в TUN."

/**
 * Итог маршрутов. Модель всегда пишет «Остальные приложения: напрямую, вне VPN»,
 * что верно только для режима «Только выбранные»; в режиме «Все кроме» хвост заменяется.
 */
internal fun routingSummary(scopeMode: AppScopeMode, summary: String): String = when (scopeMode) {
    AppScopeMode.Include -> summary
    AppScopeMode.Exclude -> summary.substringBefore(OTHER_APPS_MARKER) + EXCLUDE_SUMMARY_TAIL
}

/** Порядок применения правил; зависит только от того, есть ли блокировка приложений. */
internal fun evaluationOrder(hasBlockedApps: Boolean): String = if (hasBlockedApps) {
    "Порядок: блокировка package → reject → direct → VPN → final."
} else {
    "Порядок: reject → direct → VPN → final. Package route rules не создаются."
}

internal fun remoteRuleSetNote(count: Int): String? = if (count > 0) {
    "Пользовательских удалённых наборов правил: $count. Режимы их не загружают и не меняют."
} else {
    null
}

/** Карточка «Что идёт через VPN»: все строки и тона посчитаны заранее. */
@Immutable
internal data class ScopeSummary(
    val countText: String,
    val countTone: Tone,
    val caption: String,
    val captionTone: Tone,
    val missingText: String?,
    val pickerLabel: String,
    val pickerDescription: String,
    /** Область пуста и VPN ничего не охватит: показываем плашку с призывом выбрать приложения. */
    val needsSelection: Boolean,
    val warningText: String,
)

internal fun scopeSummary(state: AppsUiState): ScopeSummary {
    val allowed = state.allowedPackages.size
    val blocked = state.blockedPackages.size
    val include = state.scopeMode == AppScopeMode.Include
    val needs = state.needsAppSelection
    val countText = if (include) {
        when {
            allowed > 0 -> "Через VPN: $allowed"
            blocked > 0 -> "TUN используется только для блокировки"
            else -> "Приложения не выбраны"
        }
    } else {
        if (allowed > 0) "Напрямую вне TUN: $allowed" else "Приложения напрямую не выбраны: запуск заблокирован"
    }
    val countTone = when {
        needs -> Tone.Bad
        allowed > 0 || (include && blocked > 0) -> Tone.Accent
        else -> Tone.Neutral
    }
    val missing = state.missingAllowedPackages.size + state.missingBlockedPackages.size
    return ScopeSummary(
        countText = countText,
        countTone = countTone,
        caption = if (include) {
            "Остальные приложения ходят в интернет напрямую, без VPN."
        } else {
            "Режим «Приложения напрямую»: отмеченные приложения остаются вне VPN, " +
                "все остальные идут через VPN."
        },
        captionTone = if (include) Tone.Neutral else Tone.Warning,
        missingText = if (missing > 0) {
            "Не найдено на устройстве: " +
                countLabel(missing, "приложение", "приложения", "приложений") +
                ". Они будут пропущены при подключении."
        } else {
            null
        },
        pickerLabel = if (include) "Выбрать приложения" else "Выбрать приложения напрямую",
        pickerDescription = if (include) {
            "Открыть выбор приложений для VPN"
        } else {
            "Открыть выбор приложений напрямую вне VPN"
        },
        needsSelection = needs,
        warningText = if (include) {
            "VPN не будет применяться ни к одному приложению."
        } else {
            "Список пуст: подключиться не получится, пока не отмечено хотя бы одно приложение."
        },
    )
}

@Immutable
internal data class BlockSummary(val text: String, val tone: Tone)

internal fun blockSummary(blockedCount: Int): BlockSummary = if (blockedCount == 0) {
    BlockSummary("Список пуст", Tone.Neutral)
} else {
    BlockSummary("Заблокировано приложений: $blockedCount", Tone.Bad)
}

/** Строка правила: «<значение> · <тип словами> → <действие>». */
@Immutable
internal data class RuleRowModel(
    val rule: ManagedRoutingRule,
    val value: String,
    val kind: String,
    val actionTitle: String,
    val tone: Tone,
    val outbound: String?,
) {
    val sentence: String get() = "$value · $kind → $actionTitle"
}

internal fun ruleRow(rule: ManagedRoutingRule): RuleRowModel = RuleRowModel(
    rule = rule,
    value = ruleValueText(rule.values),
    kind = ruleKindWords(rule.matchType),
    actionTitle = rule.action.title,
    tone = ruleTone(rule.action),
    outbound = rule.outboundTag?.takeIf { rule.action == RoutingRuleAction.Proxy },
)

internal fun ruleKindWords(type: RoutingMatchType): String = when (type) {
    RoutingMatchType.Domain -> "точный домен"
    RoutingMatchType.DomainSuffix -> "суффикс домена"
    RoutingMatchType.IpCidr -> "IP-адрес или сеть"
    RoutingMatchType.DomainRuleSet -> "набор доменов"
    RoutingMatchType.IpRuleSet -> "набор IP-адресов"
}

internal fun ruleTone(action: RoutingRuleAction): Tone = when (action) {
    RoutingRuleAction.Proxy -> Tone.Accent
    RoutingRuleAction.Direct -> Tone.Neutral
    RoutingRuleAction.Block -> Tone.Bad
}

/** Несколько значений в строке не раздувают карточку: первые три и «и ещё N». */
internal fun ruleValueText(values: List<String>, limit: Int = 3): String = when {
    values.isEmpty() -> "…"
    values.size <= limit -> values.joinToString(", ")
    else -> values.take(limit).joinToString(", ") + " и ещё ${values.size - limit}"
}

/** Живой предпросмотр в редакторе: то же предложение, но по ещё не сохранённым значениям. */
internal fun rulePreview(
    type: RoutingMatchType,
    values: List<String>,
    action: RoutingRuleAction,
): String = "${ruleValueText(values)} · ${ruleKindWords(type)} → ${action.title}"

/** Блокировка по домену не ловит приложения со своим DoH: об этом предупреждаем в редакторе. */
internal fun isDomainBlock(type: RoutingMatchType, action: RoutingRuleAction): Boolean =
    action == RoutingRuleAction.Block && type in DomainMatchTypes

private val DomainMatchTypes = setOf(
    RoutingMatchType.Domain,
    RoutingMatchType.DomainSuffix,
    RoutingMatchType.DomainRuleSet,
)
