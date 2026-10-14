package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.SelectorGroup
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.SubscriptionBinding
import io.github.zapretkvn.android.importer.SubscriptionClientProfile
import io.github.zapretkvn.android.importer.SubscriptionSource
import io.github.zapretkvn.android.ui.UiSettings

/**
 * Состояние редактора сырого JSON. [selectors], [serverTags] и [searchMatches] считаются
 * вне главного потока и догоняют [text] с небольшой задержкой (см. [ProfileEditorController]).
 */
data class ProfileEditorState(
    val profileId: String,
    val profileName: String,
    val originalText: String,
    val text: String,
    val search: String = "",
    val validationMessage: String? = null,
    val validationSuccessful: Boolean = false,
    val selectors: List<SelectorGroup> = emptyList(),
    val serverTags: List<String> = emptyList(),
    val hasBackup: Boolean = false,
    val searchMatches: Int = 0,
) {
    val hasUnsavedChanges: Boolean get() = text != originalText
}

data class ProfilesUiState(
    val profiles: List<ProfileMetadata> = emptyList(),
    val settings: UiSettings = UiSettings(),
    val editor: ProfileEditorState? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val importPreview: ImportPreviewState? = null,
    val importCompletion: ImportCompletion? = null,
    val refreshableProfileIds: Set<String> = emptySet(),
    val serverSummaries: Map<String, ProfileServerSummary> = emptyMap(),
    val serverPicker: ProfileServerPickerState? = null,
    val subscriptionSettings: SubscriptionSettingsState? = null,
    val qrSubscriptionUrl: String? = null,
    val initialized: Boolean = false,
)

data class ProfileServerPickerState(
    val profileId: String,
    val profileName: String,
    val groups: List<ProfileServerGroup>,
    val liveSwitch: Boolean,
) {
    val serverCount: Int get() = groups.sumOf { it.options.size }
}

data class ImportCompletion(
    val profileId: String,
    val profileName: String,
)

data class SplitRefreshAddition(
    val name: String,
    val json: String,
    val memberKey: String,
)

data class SplitRefreshPlan(
    val groupId: String,
    val source: SubscriptionSource,
    val baseName: String,
    val previousBindings: Map<String, SubscriptionBinding>,
    val updatedJson: Map<String, String>,
    val additions: List<SplitRefreshAddition>,
    val removedProfileIds: Set<String>,
    val knownMemberKeys: Set<String>,
    val memberKeysByProfileId: Map<String, String>,
    val connectedProfileId: String?,
)

data class ImportPreviewState(
    override val suggestedName: String,
    override val sourceDescription: String,
    override val serverCount: Int,
    override val serverLabels: List<String>,
    override val activityWarning: String?,
    override val appendTargets: List<ProfileMetadata>,
    override val importWarnings: List<String> = emptyList(),
    val refreshProfileId: String? = null,
    override val refreshProfileName: String? = null,
    override val activeRefresh: Boolean = false,
    override val selectionChanged: Boolean = false,
    override val splitRefreshSummary: SplitRefreshSummary? = null,
    internal val candidate: ImportCandidate,
    internal val preparedJson: String,
    internal val source: SubscriptionSource? = null,
    internal val splitRefreshPlan: SplitRefreshPlan? = null,
) : ImportPreview {
    override val isSingleManaged: Boolean
        get() = candidate is ImportCandidate.Managed && candidate.servers.size == 1
    override val isRefresh: Boolean get() = refreshProfileId != null

    /** Сколько отдельных профилей получится, если разложить подписку по серверам. */
    override val splittableServerCount: Int
        get() = (candidate as? ImportCandidate.Managed)?.servers?.size?.takeIf { it > 1 } ?: 0

    override val splitSupported: Boolean get() = splittableServerCount in 2..MAX_SPLIT_PROFILES

    override val hasSubscriptionUrl: Boolean get() = source != null
}
