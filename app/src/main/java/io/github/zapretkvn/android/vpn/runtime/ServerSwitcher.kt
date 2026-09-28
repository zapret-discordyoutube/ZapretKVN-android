package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.profiles.ProfileStore
import io.github.zapretkvn.android.vpn.VpnController
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Проверка кандидата после команды селектора; бросает при отказе. */
internal fun interface SwitchVerifier {
    suspend fun verify(session: CoreSession, candidateJson: String)
}

internal sealed interface SwitchOutcome {
    data object Committed : SwitchOutcome

    /** Кандидат не прошёл проверку или не сохранился; селектор возвращён. */
    data class RolledBack(val error: Throwable) : SwitchOutcome

    /** Команда в ядро не прошла; состояние селектора неизвестно. */
    data class CoreUnreachable(val error: RuntimeSwitchException) : SwitchOutcome
}

/**
 * Единственный путь смены сервера в работающем ядре: ручной выбор, failover и
 * «умная проверка» переключают selector одинаково, без перезапуска ядра.
 *
 * Порядок: проверка конфигурации → команда селектора → (проверка кандидата) →
 * сохранение в профиль. Любой отказ после команды возвращает прежнего
 * участника; возврат не отменяем, иначе отмена посреди переключения оставила
 * бы ядро на одном сервере, а профиль — на другом.
 */
internal class ServerSwitcher(
    private val profileStore: ProfileStore,
    private val controller: VpnController,
) {
    /**
     * Пробное переключение «умной проверки»: только команда селектора, без
     * сохранения. Затем — [persist] или [restore].
     */
    suspend fun trial(session: CoreSession, groupTag: String, outboundTag: String): SwitchOutcome =
        switch(session, groupTag, outboundTag, verifier = null, persist = false)

    /** Закрепляет в профиле участника, на которого селектор уже переключён. */
    suspend fun persist(session: CoreSession, groupTag: String, outboundTag: String): Throwable? =
        session.switchLock.withLock {
            try {
                val stored = profileStore.read(session.profileId)
                profileStore.update(
                    session.profileId,
                    ConfigAnalyzer.selectServer(stored.json, groupTag, outboundTag),
                )
                session.commitSelection(groupTag, outboundTag)
                controller.publishSelection(session.generation, groupTag, outboundTag)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                error
            }
        }

    suspend fun switch(
        session: CoreSession,
        groupTag: String,
        outboundTag: String,
        verifier: SwitchVerifier? = null,
        persist: Boolean = true,
    ): SwitchOutcome = session.switchLock.withLock {
        require(groupTag.isNotBlank() && outboundTag.isNotBlank()) { "Сервер не выбран." }
        val stored = profileStore.read(session.profileId)
        val previous = session.runtimeSelections()[groupTag]
            ?: ConfigAnalyzer.selectorGroups(stored.json).firstOrNull { it.tag == groupTag }?.default
        val candidate = ConfigAnalyzer.selectServer(stored.json, groupTag, outboundTag)
        withContext(Dispatchers.Default) { Libbox.checkConfig(candidate) }
        val client = session.selectorClient()
            ?: return@withLock SwitchOutcome.CoreUnreachable(
                RuntimeSwitchException(IllegalStateException("Клиент управления selector уже закрыт.")),
            )
        try {
            withContext(Dispatchers.IO) { client.selectOutbound(groupTag, outboundTag) }
        } catch (cancelled: CancellationException) {
            rollback(session, groupTag, previous)
            throw cancelled
        } catch (error: Throwable) {
            return@withLock SwitchOutcome.CoreUnreachable(RuntimeSwitchException(error))
        }
        if (!persist) return@withLock SwitchOutcome.Committed
        try {
            verifier?.verify(session, candidate)
            profileStore.update(session.profileId, candidate)
        } catch (error: Throwable) {
            rollback(session, groupTag, previous)?.let(error::addSuppressed)
            if (error is CancellationException) throw error
            return@withLock SwitchOutcome.RolledBack(error)
        }
        session.commitSelection(groupTag, outboundTag)
        controller.publishSelection(session.generation, groupTag, outboundTag)
        SwitchOutcome.Committed
    }

    /** Вернуть участника группы без сохранения (откат пробы «умной проверки»). */
    suspend fun restore(session: CoreSession, groupTag: String, outboundTag: String): Throwable? =
        session.switchLock.withLock { rollback(session, groupTag, outboundTag) }

    private suspend fun rollback(session: CoreSession, groupTag: String, previousTag: String?): Throwable? {
        if (previousTag.isNullOrBlank()) return null
        val client = session.selectorClient()
            ?: return IllegalStateException("Клиент управления selector уже закрыт; откат невозможен.")
        return withContext(NonCancellable + Dispatchers.IO) {
            runCatching { client.selectOutbound(groupTag, previousTag) }.exceptionOrNull()
        }
    }
}
