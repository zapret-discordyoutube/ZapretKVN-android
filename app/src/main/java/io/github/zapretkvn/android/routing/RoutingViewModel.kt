package io.github.zapretkvn.android.routing

import io.github.zapretkvn.android.diagnostics.SecretRedactor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zapretkvn.android.profiles.ProfileStore
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.vpn.VpnController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RoutingUiState(
    val activeProfileId: String? = null,
    val activeProfileName: String? = null,
    val inspection: RoutingInspection? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val managedDiff: String? = null,
)

class RoutingViewModel(
    private val profileStore: ProfileStore,
    private val settingsStore: UiSettingsStore,
    private val ruleSetAssets: RuleSetAssetManager,
    private val policyStore: RoutingPolicyStore,
    private val vpnController: VpnController,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RoutingUiState())
    val state: StateFlow<RoutingUiState> = mutableState.asStateFlow()
    private var globalPolicy: GlobalRoutingPolicy? = null

    init {
        viewModelScope.launch {
            profileStore.initialize()
            combine(
                profileStore.profiles,
                settingsStore.settings,
                policyStore.policy,
            ) { profiles, settings, policy ->
                val active = profiles.firstOrNull { it.id == settings.activeProfileId }
                Triple(active?.id, active?.name, policy)
            }
                .distinctUntilChanged()
                .collect { (id, name, policy) ->
                    if (policy == null && id != null) {
                        val profile = profileStore.read(id)
                        val inspection = withContext(Dispatchers.Default) {
                            RoutingConfigEditor.inspect(profile.json)
                        }
                        policyStore.getOrInitialize(
                            GlobalRoutingPolicy(
                                preset = inspection.preset,
                                rules = inspection.rules,
                            ),
                        )
                        return@collect
                    }
                    globalPolicy = policy
                    mutableState.update {
                        it.copy(
                            activeProfileId = id,
                            activeProfileName = name,
                        )
                    }
                    refresh()
                }
        }
    }

    fun refresh() {
        val id = mutableState.value.activeProfileId ?: run {
            mutableState.update { it.copy(inspection = null, loading = false) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            try {
                val profile = profileStore.read(id)
                val policy = globalPolicy
                val inspection = if (policy == null) {
                    withContext(Dispatchers.Default) {
                        RoutingConfigEditor.inspect(profile.json)
                    }
                } else {
                    val installed = ruleSetAssets.ensureInstalled()
                    withContext(Dispatchers.Default) {
                        RoutingConfigEditor.apply(
                            profile.json,
                            policy.preset,
                            policy.rules,
                            installed,
                        ).inspection
                    }
                }
                mutableState.update { it.copy(inspection = inspection, loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        message = error.message?.let(SecretRedactor::redactInline)?.take(320)
                            ?: "Не удалось прочитать маршруты.",
                    )
                }
            }
        }
    }

    fun applyPreset(preset: RoutingPreset) = edit { inspection ->
        preset to inspection.rules
    }

    fun saveRule(index: Int?, rule: ManagedRoutingRule) = edit { inspection ->
        val rules = inspection.rules.toMutableList()
        if (index == null) rules += rule
        else {
            require(index in rules.indices) { "Правило больше не существует." }
            rules[index] = rule
        }
        inspection.preset to rules
    }

    fun deleteRule(index: Int) = edit { inspection ->
        require(index in inspection.rules.indices) { "Правило больше не существует." }
        inspection.preset to inspection.rules.filterIndexed { position, _ -> position != index }
    }

    fun consumeMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun edit(
        transform: (RoutingInspection) -> Pair<RoutingPreset, List<ManagedRoutingRule>>,
    ) {
        val id = mutableState.value.activeProfileId ?: return
        if (mutableState.value.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                val installed = ruleSetAssets.ensureInstalled()
                val profile = profileStore.read(id)
                val current = mutableState.value.inspection
                    ?: RoutingConfigEditor.inspect(profile.json)
                val (preset, rules) = transform(current)
                val result = withContext(Dispatchers.Default) {
                    RoutingConfigEditor.apply(profile.json, preset, rules, installed)
                }
                policyStore.set(GlobalRoutingPolicy(preset, rules))
                globalPolicy = GlobalRoutingPolicy(preset, rules)
                mutableState.update {
                    it.copy(
                        inspection = result.inspection,
                        loading = false,
                        managedDiff = result.diff,
                        message = "Общие правила сохранены для всех профилей.",
                    )
                }
                vpnController.restartIfConnected("Изменение маршрутизации")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        message = error.message?.let(SecretRedactor::redactInline)?.take(320)
                            ?: "Не удалось сохранить маршрутизацию.",
                    )
                }
            }
        }
    }

    class Factory(
        private val profileStore: ProfileStore,
        private val settingsStore: UiSettingsStore,
        private val ruleSetAssets: RuleSetAssetManager,
        private val policyStore: RoutingPolicyStore,
        private val vpnController: VpnController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RoutingViewModel::class.java))
            return RoutingViewModel(
                profileStore,
                settingsStore,
                ruleSetAssets,
                policyStore,
                vpnController,
            ) as T
        }
    }
}
