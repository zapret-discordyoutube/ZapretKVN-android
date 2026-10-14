package io.github.zapretkvn.android.profiles

/** Что делать с работающим VPN после подтверждённого обновления подписки. */
internal enum class VpnFollowUp { None, Restart, Stop }

internal data class RefreshOutcome(val followUp: VpnFollowUp, val message: String)

/** Исход обновления одного профиля подписки. */
internal fun refreshOutcome(
    restartConnected: Boolean,
    connectedNow: Boolean,
    activeRefresh: Boolean,
    selectionChanged: Boolean,
): RefreshOutcome = when {
    restartConnected && connectedNow -> RefreshOutcome(
        VpnFollowUp.Restart,
        if (selectionChanged) {
            "Выбранный сервер исчез: выбран первый доступный; VPN контролируемо перезапущен."
        } else {
            "Подписка сохранена; подтверждён контролируемый перезапуск VPN."
        },
    )
    restartConnected && activeRefresh -> RefreshOutcome(
        VpnFollowUp.None,
        "Подписка сохранена; VPN уже отключён, перезапуск не требуется.",
    )
    selectionChanged -> RefreshOutcome(
        VpnFollowUp.None,
        "Выбранный сервер исчез: выбран первый доступный. VPN не перезапущен.",
    )
    else -> RefreshOutcome(VpnFollowUp.None, "Подписка обновлена. Работающий VPN не изменён.")
}

/** Исход обновления группы раздельной подписки. */
internal fun splitRefreshOutcome(
    plan: SplitRefreshPlan,
    createdCount: Int,
    restartConnected: Boolean,
    connectedBefore: String?,
    connectedStill: Boolean,
    activeRefresh: Boolean,
): RefreshOutcome = when {
    restartConnected && connectedBefore in plan.removedProfileIds && connectedStill ->
        RefreshOutcome(
            VpnFollowUp.Stop,
            "Группа подписки обновлена; удалённый подключённый профиль отключается.",
        )
    restartConnected && connectedBefore != null && connectedStill ->
        RefreshOutcome(
            VpnFollowUp.Restart,
            "Группа подписки обновлена; VPN контролируемо перезапущен.",
        )
    restartConnected && activeRefresh ->
        RefreshOutcome(VpnFollowUp.None, "Группа подписки обновлена; VPN уже отключён.")
    else -> RefreshOutcome(
        VpnFollowUp.None,
        "Группа подписки обновлена: ${plan.updatedJson.size} обновлено, " +
            "$createdCount добавлено, ${plan.removedProfileIds.size} удалено.",
    )
}
