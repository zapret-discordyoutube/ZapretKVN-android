package io.github.zapretkvn.android.profiles

import android.net.Uri
import io.github.zapretkvn.android.config.ConfigValidationResult
import io.github.zapretkvn.android.config.ConfigValidator
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.importer.AndroidImportReader
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportException
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.importer.SubscriptionBinding
import io.github.zapretkvn.android.importer.SubscriptionClientProfile
import io.github.zapretkvn.android.importer.SubscriptionFetcher
import io.github.zapretkvn.android.importer.SubscriptionIdentity
import io.github.zapretkvn.android.importer.SubscriptionSource
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import io.github.zapretkvn.android.routing.InstalledRuleSets
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.android.routing.RoutingPreset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Источники сырых данных импорта: системный файл, буфер обмена, QR из галереи. */
internal interface ImportInputPort {
    suspend fun readDocument(uri: Uri): DocumentContent

    fun readClipboard(): String

    suspend fun readQrImage(uri: Uri): String
}

internal data class DocumentContent(val raw: String, val displayName: String?)

internal class AndroidImportInput(private val reader: AndroidImportReader) : ImportInputPort {
    override suspend fun readDocument(uri: Uri): DocumentContent = withContext(Dispatchers.IO) {
        val raw = reader.readDocument(uri)
        DocumentContent(raw, reader.documentDisplayName(uri))
    }

    override fun readClipboard(): String = reader.readClipboardAfterUserAction()

    override suspend fun readQrImage(uri: Uri): String =
        withContext(Dispatchers.IO) { reader.readSingleQrImage(uri) }
}

/** Исходные данные импорта, из которых строится предпросмотр. */
internal data class ImportRequest(
    val raw: String,
    val source: ProfileSource,
    val suggestedName: String,
    val sourceDescription: String,
    val subscription: SubscriptionSource? = null,
)

/**
 * Всё, что нужно импорту без состояния экрана: чтение источников, разбор, подготовка JSON,
 * проверка ядром и сборка предпросмотра. Состояние и показ сообщений остаются у контроллера.
 */
internal class ImportService(
    private val store: ProfileStore,
    private val validator: ConfigValidator,
    private val input: ImportInputPort,
    private val subscriptionFetcher: SubscriptionFetcher,
    private val subscriptionSourceStore: SubscriptionSourceStore,
    private val settings: ProfilesSettingsPort,
    private val vpn: ProfilesVpnPort,
    private val installRuleSets: suspend () -> InstalledRuleSets,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun documentRequest(uri: Uri): ImportRequest {
        val document = input.readDocument(uri)
        val displayName = document.displayName
        val suggestedName = displayName
            ?.substringBeforeLast('.', displayName)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "Профиль из файла"
        val sourceDescription = displayName
            ?.let(SecretRedactor::redactInline)
            ?.let { "Системный файл: $it" }
            ?: "Системный файл"
        return ImportRequest(document.raw, ProfileSource.File, suggestedName, sourceDescription)
    }

    fun clipboardRequest(): ImportRequest = ImportRequest(
        raw = input.readClipboard(),
        source = ProfileSource.Clipboard,
        suggestedName = "Профиль из буфера",
        sourceDescription = "Буфер обмена",
    )

    suspend fun qrImageContents(uri: Uri): String = input.readQrImage(uri)

    /** Содержимое QR — ссылка на подписку (нужна форма идентификации) или обычный профиль. */
    suspend fun isSubscriptionLink(contents: String): Boolean = withContext(defaultDispatcher) {
        runCatching { SubscriptionIdentity.resolveSource(contents) }.isSuccess
    }

    fun qrRequest(contents: String, sourceDescription: String) = ImportRequest(
        raw = contents,
        source = ProfileSource.Qr,
        suggestedName = "Профиль из QR",
        sourceDescription = sourceDescription,
    )

    suspend fun urlRequest(url: String, identity: SubscriptionIdentityInput): ImportRequest {
        val subscription = resolveSubscription(url, identity)
        val raw = fetch(subscription)
        return ImportRequest(
            raw = raw,
            source = ProfileSource.Url,
            suggestedName = "Подписка",
            sourceDescription = SecretRedactor.redactInline(subscription.url),
            subscription = subscription,
        )
    }

    /** Пустой HWID означает постоянный идентификатор этой установки. */
    suspend fun resolveSubscription(
        url: String,
        identity: SubscriptionIdentityInput,
    ): SubscriptionSource {
        // Расшифровка happ://crypt* — RSA-4096, поэтому не на главном потоке.
        val resolved = withContext(defaultDispatcher) { SubscriptionIdentity.resolveSource(url) }
        val profile = identity.clientProfile
            ?: resolved.profileHint
            ?: SubscriptionClientProfile.Zapret
        val hwid = if (identity.sendHwid) {
            SubscriptionIdentity.validateHwid(
                identity.hwid.ifBlank { settings.subscriptionDeviceId() },
            )
        } else {
            ""
        }
        return SubscriptionSource(
            url = resolved.url,
            clientProfile = profile,
            sendHwid = identity.sendHwid,
            hwid = hwid,
        )
    }

    suspend fun requireValid(rawJson: String) {
        when (val result = withContext(ioDispatcher) { validator.validate(rawJson) }) {
            ConfigValidationResult.Valid -> Unit
            is ConfigValidationResult.Invalid -> throw ImportException(result.message)
        }
    }

    /** Разбирает [request], применяет к серверным ссылкам маршрутизацию по умолчанию и проверяет итог ядром. */
    suspend fun preview(request: ImportRequest, profiles: List<ProfileMetadata>): ImportPreviewState {
        val candidate = withContext(defaultDispatcher) {
            ImportParser.parse(request.raw, request.source, request.suggestedName)
        }
        val baseJson = candidate.toJson()
        val json = if (candidate is ImportCandidate.Managed) {
            val installed = installRuleSets()
            withContext(defaultDispatcher) { withDefaultRouting(baseJson, installed) }
        } else {
            baseJson
        }
        requireValid(json)
        val appendTargets = if (candidate is ImportCandidate.Managed && candidate.servers.size == 1) {
            managedProfiles(profiles)
        } else {
            emptyList()
        }
        return ImportPreviewState(
            suggestedName = SecretRedactor.redactInline(candidate.suggestedName),
            sourceDescription = request.sourceDescription,
            serverCount = candidate.serverCount(),
            serverLabels = candidate.serverLabels(),
            activityWarning = importActivityWarnings(json),
            appendTargets = appendTargets,
            importWarnings = candidate.importWarnings(),
            candidate = candidate,
            preparedJson = json,
            source = request.subscription,
        )
    }

    /** Профили, в которые можно дописать сервер: только те, что собраны приложением. */
    private suspend fun managedProfiles(profiles: List<ProfileMetadata>): List<ProfileMetadata> =
        buildList {
            for (profile in profiles) {
                if (runCatching { ManagedProfileEditor.isManaged(store.read(profile.id).json) }
                        .getOrDefault(false)
                ) {
                    add(profile)
                }
            }
        }

    /** Предпросмотр ручного обновления подписки профиля [profileId]. */
    suspend fun refreshPreview(profileId: String, profiles: List<ProfileMetadata>): ImportPreviewState {
        val binding = subscriptionSourceStore.binding(profileId)
            ?: throw ImportException("Для этого профиля не сохранён URL ручного обновления.")
        val subscription = binding.source
        val stored = store.read(profileId)
        val raw = fetch(subscription)
        val candidate = withContext(defaultDispatcher) {
            ImportParser.parse(raw, ProfileSource.Url, stored.metadata.name)
        }
        if (binding.isSplit) {
            return splitRefreshPreview(profileId, binding, subscription, candidate, profiles)
        }
        val candidateJson = candidate.toJson()
        val update = when {
            candidate is ImportCandidate.Managed && ManagedProfileEditor.isManaged(stored.json) ->
                ManagedProfileEditor.refreshServers(stored.json, candidate.servers)
            candidate is ImportCandidate.RawJson ->
                ManagedProfileEditor.preserveSelectorDefaults(stored.json, candidateJson)
            else -> ManagedProfileUpdate(candidateJson, selectedTag = "", selectionChanged = false)
        }
        requireValid(update.json)
        return ImportPreviewState(
            suggestedName = stored.metadata.name,
            sourceDescription = SecretRedactor.redactInline(subscription.url),
            serverCount = candidate.serverCount(),
            serverLabels = candidate.serverLabels(),
            activityWarning = importActivityWarnings(update.json),
            appendTargets = emptyList(),
            importWarnings = candidate.importWarnings(),
            refreshProfileId = profileId,
            refreshProfileName = stored.metadata.name,
            activeRefresh = vpn.isConnectedTo(profileId),
            selectionChanged = update.selectionChanged,
            candidate = candidate,
            preparedJson = update.json,
            source = subscription,
        )
    }

    private suspend fun splitRefreshPreview(
        profileId: String,
        binding: SubscriptionBinding,
        subscription: SubscriptionSource,
        candidate: ImportCandidate,
        profiles: List<ProfileMetadata>,
    ): ImportPreviewState {
        val managed = candidate as? ImportCandidate.Managed
            ?: throw ImportException("Раздельная подписка должна возвращать список серверных ссылок.")
        val groupId = checkNotNull(binding.splitGroupId)
        val group = subscriptionSourceStore.splitGroup(profileId)
        if (group.isEmpty()) throw ImportException("Группа раздельной подписки повреждена.")
        val storedJson = group.keys.associateWith { id -> store.read(id).json }
        val installed = installRuleSets()
        val planner = SplitRefreshPlanner(
            validate = ::requireValid,
            buildNewProfile = { server ->
                withContext(defaultDispatcher) {
                    withDefaultRouting(ManagedProfileFactory.single(server), installed)
                }
            },
        )
        val plan = planner.plan(
            groupId = groupId,
            subscription = subscription,
            servers = managed.servers,
            group = group,
            storedJson = storedJson,
            connectedProfileId = vpn.connectedProfileId(),
        )
        return ImportPreviewState(
            suggestedName = plan.baseName,
            sourceDescription = SecretRedactor.redactInline(subscription.url),
            serverCount = managed.servers.size,
            serverLabels = candidate.serverLabels(),
            activityWarning = null,
            appendTargets = emptyList(),
            importWarnings = candidate.importWarnings(),
            refreshProfileId = profileId,
            refreshProfileName = profiles.firstOrNull { it.id == profileId }?.name,
            activeRefresh = plan.connectedProfileId != null,
            splitRefreshSummary = plan.summary(),
            candidate = candidate,
            preparedJson = plan.previewJson(managed.servers),
            source = subscription,
            splitRefreshPlan = plan,
        )
    }

    private suspend fun fetch(subscription: SubscriptionSource): String =
        withContext(ioDispatcher) { subscriptionFetcher.fetch(subscription) }

    private fun withDefaultRouting(json: String, installed: InstalledRuleSets): String =
        RoutingConfigEditor.apply(json, RoutingPreset.RussiaDirect, emptyList(), installed).json
}
