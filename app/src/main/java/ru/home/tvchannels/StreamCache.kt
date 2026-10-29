package ru.home.tvchannels

/**
 * Хранит полученные ссылки на поток для okru:, vitrina: и vkplay:.
 * Получение такой ссылки занимает до нескольких секунд. Сохранённая ссылка
 * запускает канал сразу. Срок хранения меньше срока действия токена источника.
 */
class StreamCache(private val store: Store, private val clock: () -> Long) {

    /** Постоянное хранилище. На стике это SharedPreferences, в тестах — словарь. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    /** Возвращает сохранённую ссылку на поток, если её срок не истёк. Иначе возвращает null. */
    fun get(url: String): String? {
        val saved = store.get(KEY_PREFIX + url) ?: return null
        val savedAt = saved.substringBefore(SEPARATOR).toLongOrNull()
        val stream = saved.substringAfter(SEPARATOR, "")
        if (savedAt == null || stream.isEmpty()) return null
        return stream.takeIf { clock() - savedAt < lifetime(url) }
    }

    fun put(url: String, stream: String) = store.put(KEY_PREFIX + url, "${clock()}$SEPARATOR$stream")

    /** Удаляет ссылку. Её вызывают, если сохранённая ссылка не запустила поток. */
    fun invalidate(url: String) = store.remove(KEY_PREFIX + url)

    private fun lifetime(url: String) = when {
        // Токен «Витрины» действует около 6 дней.
        url.startsWith(Vitrina.SCHEME) -> 12 * HOUR_MS
        // Ссылка OK.ru действует около суток.
        url.startsWith(OkRu.SCHEME) -> 3 * HOUR_MS
        // Ссылка VK Play действует меньше суток, эфир может смениться.
        url.startsWith(VkPlay.SCHEME) -> HOUR_MS
        else -> 0L
    }

    companion object {
        const val KEY_PREFIX = "stream:"
        private const val SEPARATOR = '|'
        private const val HOUR_MS = 60 * 60 * 1000L
    }
}
