package io.github.zapretkvn.android.updates

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Страница обращений в поддержку на том имени сайта проекта, которое открывается сейчас.
 * Тот же порядок, что у кнопки «Поддержка» в Windows (`xray_fluent/support/site.py`):
 * известные имена Forgejo (встроенные и узнанные со входных страниц) → новые имена со входных
 * страниц → входная страница Zapret Links на script.google.com с путём обращений: она работает
 * в браузере и сама предлагает зеркало, которое там открывается. На GitHub обращений нет (там
 * только копии выпусков), поэтому он запасным путём не служит.
 *
 * Имя проверяется запросом `/_sites/mirrors.json`: он есть на каждом имени нашего сайта,
 * и верный ответ доказывает, что имя открывается с этого телефона.
 */
object SupportLinks {
    const val SUPPORT_PATH = "/zapretkvn/ZapretKVN-android/issues/new"
    private const val ENTRY_HOST = "script.google.com"

    /**
     * Входная страница Zapret Links (та же, что в [ForgejoHosts.ENTRY_LISTS], но без `format=json`)
     * с путём нашего сайта: фильтр провайдера видит только script.google.com, а страница в браузере
     * показывает зеркала, которые действительно грузятся. Нужна, когда приложение само не нашло
     * открывающееся имя: его сеть и сеть браузера доходят до сайта по-разному.
     */
    fun entryPageUrl(path: String = SUPPORT_PATH): String {
        val entry = ForgejoHosts.ENTRY_LISTS.firstNotNullOfOrNull { listed ->
            runCatching { java.net.URI(listed) }.getOrNull()?.takeIf { it.host == ENTRY_HOST }
        } ?: return "https://${ForgejoHosts.BUILT_IN.last()}$path"
        return "${entry.scheme}://${entry.rawAuthority}${entry.rawPath}?s=git&p=${path.trim('/')}"
    }

    private const val MIRRORS_PATH = "/_sites/mirrors.json"
    private const val RACE_DEADLINE_MILLIS = 4_000L
    private const val MAX_RACERS = 8
    private const val MAX_LIST_BYTES = 64 * 1024

    /** Блокирует до [deadlineMillis] на каждый круг: вызывать только не из главного потока. */
    fun resolve(
        http: UpdateHttpClient = ForgejoHttpsClient(),
        deadlineMillis: Long = RACE_DEADLINE_MILLIS,
    ): String {
        val known = ForgejoHosts.all()
        race(http, known, deadlineMillis)?.let { return "https://$it$SUPPORT_PATH" }
        for (entry in ForgejoHosts.ENTRY_LISTS) {
            val fresh = try {
                ForgejoHosts.learnFromEntry(http.readEntryList(entry, MAX_LIST_BYTES)).filter { it !in known }
            } catch (_: UpdateException) {
                continue
            }
            if (fresh.isEmpty()) continue
            race(http, fresh, deadlineMillis)?.let { return "https://$it$SUPPORT_PATH" }
            break
        }
        return entryPageUrl()
    }

    /** Имена спрашиваются параллельно; побеждает первое, давшее верный список зеркал. */
    internal fun race(http: UpdateHttpClient, hosts: List<String>, deadlineMillis: Long): String? {
        val racers = hosts.distinct().take(MAX_RACERS)
        if (racers.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(racers.size)
        try {
            val completion = ExecutorCompletionService<String?>(pool)
            racers.forEach { host ->
                completion.submit(
                    Callable {
                        try {
                            host.takeIf { looksLikeMirrorList(http.readText("https://$host$MIRRORS_PATH", MAX_LIST_BYTES)) }
                        } catch (_: Exception) {
                            null
                        }
                    },
                )
            }
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis)
            repeat(racers.size) {
                val left = end - System.nanoTime()
                if (left <= 0) return null
                val finished = completion.poll(left, TimeUnit.NANOSECONDS) ?: return null
                finished.get()?.let { return it }
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    internal fun looksLikeMirrorList(text: String): Boolean = try {
        Json.parseToJsonElement(text).jsonObject["families"] != null
    } catch (_: Exception) {
        false
    }
}
