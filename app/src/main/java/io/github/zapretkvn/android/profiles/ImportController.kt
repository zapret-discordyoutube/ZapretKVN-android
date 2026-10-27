package io.github.zapretkvn.android.profiles

import android.net.Uri
import io.github.zapretkvn.android.importer.ImportException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Импорт профилей (файл, буфер, QR, ссылка на подписку), предпросмотр и ручное обновление подписки.
 * Хранит в [ProfilesUiState] только предпросмотр, итог импорта и форму QR-подписки.
 */
internal class ImportController(
    private val state: MutableStateFlow<ProfilesUiState>,
    private val operations: ProfilesOperations,
    private val service: ImportService,
    private val committer: ImportCommitter,
    private val splitCommitter: SplitRefreshCommitter,
    private val store: ProfileStore,
    private val vpn: ProfilesVpnPort,
    private val settings: ProfilesSettingsPort,
) {
    fun importDocument(uri: Uri) = operations.run {
        preview(service.documentRequest(uri))
    }

    fun importClipboard() = operations.run {
        val request = service.clipboardRequest()
        val value = request.raw.trim()
        if (service.isSubscriptionLink(value)) {
            promptSubscriptionLink(value, SubscriptionLinkOrigin.Clipboard)
        } else {
            preview(request)
        }
    }

    fun importQr(contents: String) = operations.run {
        handleQrContents(contents, "QR-код")
    }

    fun importQrImage(uri: Uri) = operations.run {
        handleQrContents(service.qrImageContents(uri), "QR-код из галереи")
    }

    fun closeSubscriptionLinkPrompt() {
        state.update { it.copy(subscriptionLinkPrompt = null) }
    }

    fun importPromptedSubscription(url: String, identity: SubscriptionIdentityInput) {
        closeSubscriptionLinkPrompt()
        importUrl(url, identity)
    }

    fun importUrl(url: String, identity: SubscriptionIdentityInput = SubscriptionIdentityInput()) =
        operations.run {
            preview(service.urlRequest(url, identity))
        }

    fun refreshSubscription(profileId: String) = operations.run {
        val preview = service.refreshPreview(profileId, state.value.profiles)
        state.update { it.copy(importPreview = preview, message = null) }
    }

    fun confirmImport(name: String) = operations.run {
        val pending = pendingImport()
        val metadata = committer.create(name, pending, hasActiveProfile())
        state.update {
            it.copy(
                importPreview = null,
                importCompletion = ImportCompletion(metadata.id, metadata.name),
                refreshableProfileIds = if (pending.source != null) {
                    it.refreshableProfileIds + metadata.id
                } else {
                    it.refreshableProfileIds
                },
            )
        }
        operations.showMessage("Профиль сохранён. Подключение не запускалось.")
    }

    fun confirmImportPerServer(baseName: String) = operations.run {
        val pending = pendingImport()
        val created = committer.createPerServer(baseName, pending, hasActiveProfile())
        val first = created.first()
        state.update {
            it.copy(
                importPreview = null,
                importCompletion = ImportCompletion(first.id, first.name),
                refreshableProfileIds = if (pending.source != null) {
                    it.refreshableProfileIds + created.map(ProfileMetadata::id)
                } else {
                    it.refreshableProfileIds
                },
            )
        }
        operations.showMessage("Создано профилей: ${created.size}. Подключение не запускалось.")
    }

    fun confirmAppend(targetProfileId: String) = operations.run {
        val pending = pendingImport()
        val targetName = committer.append(targetProfileId, pending)
        state.update { it.copy(importPreview = null) }
        operations.showMessage("Сервер добавлен в $targetName. Работающий VPN не изменён.")
    }

    fun confirmRefresh(restartConnected: Boolean) = operations.run {
        val pending = state.value.importPreview
            ?.takeIf { it.isRefresh }
            ?: throw ImportException("Предпросмотр обновления уже закрыт.")
        pending.splitRefreshPlan?.let { plan ->
            confirmSplitRefresh(pending, plan, restartConnected)
            return@run
        }
        val profileId = checkNotNull(pending.refreshProfileId)
        // Пока открыт предпросмотр, сервер могли сменить: выбор — из профиля на момент записи.
        val current = store.read(profileId).json
        store.update(profileId, ManagedProfileEditor.preserveSelectorDefaults(current, pending.preparedJson).json)
        state.update { it.copy(importPreview = null) }
        val outcome = refreshOutcome(
            restartConnected = restartConnected,
            connectedNow = vpn.isConnectedTo(profileId),
            activeRefresh = pending.activeRefresh,
            selectionChanged = pending.selectionChanged,
        )
        if (outcome.followUp == VpnFollowUp.Restart) {
            vpn.restartIfConnected("Подписка обновлена пользователем")
        }
        operations.showMessage(outcome.message)
    }

    fun dismissPreview() {
        state.update { it.copy(importPreview = null) }
    }

    fun consumeCompletion() {
        state.update { it.copy(importCompletion = null) }
    }

    private suspend fun handleQrContents(contents: String, sourceDescription: String) {
        val value = contents.trim()
        if (service.isSubscriptionLink(value)) {
            promptSubscriptionLink(value, SubscriptionLinkOrigin.Qr)
        } else {
            preview(service.qrRequest(value, sourceDescription))
        }
    }

    private fun promptSubscriptionLink(url: String, origin: SubscriptionLinkOrigin) {
        state.update { it.copy(subscriptionLinkPrompt = SubscriptionLinkPrompt(url, origin), message = null) }
    }

    private suspend fun preview(request: ImportRequest) {
        val preview = service.preview(request, state.value.profiles)
        state.update { it.copy(importPreview = preview, message = null) }
    }

    private fun pendingImport(): ImportPreviewState = state.value.importPreview
        ?.takeUnless { it.isRefresh }
        ?: throw ImportException("Предпросмотр импорта уже закрыт.")

    private fun hasActiveProfile(): Boolean = state.value.settings.activeProfileId != null

    private suspend fun confirmSplitRefresh(
        pending: ImportPreviewState,
        plan: SplitRefreshPlan,
        restartConnected: Boolean,
    ) {
        val connectedBefore = vpn.connectedProfileId()?.takeIf(plan.previousBindings::containsKey)
        if (connectedBefore in plan.removedProfileIds && !restartConnected) {
            throw ImportException("Подключённый удаляемый профиль нужно сначала отключить.")
        }
        val commit = splitCommitter.commit(plan)
        state.update {
            it.copy(
                importPreview = null,
                refreshableProfileIds =
                    (it.refreshableProfileIds - plan.removedProfileIds) + commit.survivingIds + commit.createdIds,
                serverSummaries = it.serverSummaries - plan.removedProfileIds,
            )
        }
        if (state.value.settings.activeProfileId in plan.removedProfileIds) {
            settings.setActiveProfile((commit.survivingIds + commit.createdIds).firstOrNull())
        }
        val outcome = splitRefreshOutcome(
            plan = plan,
            createdCount = commit.createdIds.size,
            restartConnected = restartConnected,
            connectedBefore = connectedBefore,
            connectedStill = connectedBefore?.let(vpn::isConnectedTo) == true,
            activeRefresh = pending.activeRefresh,
        )
        when (outcome.followUp) {
            VpnFollowUp.Stop -> vpn.stop()
            VpnFollowUp.Restart -> vpn.restartIfConnected("Группа подписки обновлена пользователем")
            VpnFollowUp.None -> Unit
        }
        operations.showMessage(outcome.message)
    }
}
