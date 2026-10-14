package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigValidationResult
import io.github.zapretkvn.android.config.ConfigValidator
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.ui.UiSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Общие заглушки для тестов контроллеров профилей. */
internal class JvmAtomicProfileWriter : AtomicProfileWriter {
    override fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".new")
        temp.writeBytes(bytes)
        move(temp, target)
    }

    override fun writeProfile(target: File, bytes: ByteArray) {
        val stage = AndroidAtomicProfileWriter.stageFile(target)
        writeAtomic(stage, bytes)
        val backup = AndroidAtomicProfileWriter.backupFile(target)
        backup.delete()
        if (target.exists()) move(target, backup)
        move(stage, target)
    }

    override fun rollbackProfile(target: File): Boolean {
        val backup = AndroidAtomicProfileWriter.backupFile(target)
        if (!backup.exists()) return false
        target.delete()
        move(backup, target)
        return true
    }

    private fun move(from: File, to: File) {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}

/** Валидатор: любой разбираемый JSON корректен, всё остальное нет. */
internal val JsonSyntaxValidator = ConfigValidator { raw ->
    runCatching { JsonConfig.parse(raw) }.fold(
        onSuccess = { ConfigValidationResult.Valid },
        onFailure = { ConfigValidationResult.Invalid("bad json") },
    )
}

internal class FakeVpnPort(var connected: String? = null) : ProfilesVpnPort {
    val restarts = mutableListOf<String>()
    var stops = 0
    val selected = mutableListOf<Triple<String, String, String>>()

    override fun connectedProfileId(): String? = connected

    override fun switchProfileIfConnected(profileId: String): Boolean = connected != null

    override fun restartIfConnected(reason: String) {
        restarts += reason
    }

    override fun stop() {
        stops++
    }

    override fun selectOutbound(profileId: String, groupTag: String, serverTag: String) {
        selected += Triple(profileId, groupTag, serverTag)
    }
}

internal class FakeSettingsPort(initial: UiSettings = UiSettings()) : ProfilesSettingsPort {
    private val flow = MutableStateFlow(initial)
    override val settings: StateFlow<UiSettings> get() = flow
    val dnsModes = mutableListOf<DnsMode>()

    override suspend fun setActiveProfile(id: String?) {
        flow.value = flow.value.copy(activeProfileId = id)
    }

    override suspend fun setDnsMode(mode: DnsMode) {
        dnsModes += mode
    }

    override suspend fun subscriptionDeviceId(): String = "device-id"
}

internal fun managedServers(vararg links: String): List<ManagedServer> {
    val candidate = ImportParser.parse(links.joinToString("\n"), ProfileSource.Clipboard)
    return (candidate as ImportCandidate.Managed).servers
}

internal fun vlessLink(host: String, name: String, uuidDigit: Char = '1') =
    "vless://${uuidDigit.toString().repeat(8)}-1111-4111-8111-111111111111@$host:443?security=tls#$name"

/** Ждёт условие в реальном времени (для кода, который уходит на настоящие потоки ввода-вывода). */
internal suspend fun awaitCondition(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5)
        }
    }
}
