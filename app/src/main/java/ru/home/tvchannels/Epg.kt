package ru.home.tvchannels

import android.util.Log
import android.util.LruCache

/**
 * Загружает телепрограмму по запросу и держит её в памяти 6 часов.
 * На диск ничего не скачивает. Порядок источников:
 * - tv.mail.ru — текущие телесутки; сайт российский, со стика открывается;
 * - epg.iptvx.one — неделя; сайт за Cloudflare, у части провайдеров не открывается.
 * Функцию load() вызывают не из главного потока.
 */
object Epg {
    private const val IPTVX = "https://epg.iptvx.one/id/"
    private const val MAIL_RU = "https://tv.mail.ru/moskva/channel/"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val IPTVX_PAUSE_MS = 10 * 60 * 1000L

    private class Entry(val loadedAt: Long, val programmes: List<Programme>)

    private val cache = LruCache<String, Entry>(40)

    /** Время, до которого iptvx не запрашивают после неудачи. */
    @Volatile private var iptvxDownUntil = 0L

    fun has(ch: Channel) = ch.mailru != null || ch.epg != null

    /** Возвращает свежую телепрограмму из памяти. Если её нет, возвращает null: нужен load(). */
    fun cached(ch: Channel): List<Programme>? =
        cache.get(ch.key)?.takeIf { System.currentTimeMillis() - it.loadedAt < TTL_MS }?.programmes

    fun load(ch: Channel): List<Programme>? {
        cached(ch)?.let { return it }
        val list = ch.mailru?.let(::loadMailRu)?.takeIf { it.isNotEmpty() }
            ?: ch.epg?.let(::loadIptvx)
        if (!list.isNullOrEmpty()) cache.put(ch.key, Entry(System.currentTimeMillis(), list))
        return list
    }

    fun nowAndNext(list: List<Programme>, now: Long = System.currentTimeMillis()) =
        EpgParser.nowAndNext(list, now)

    private fun loadMailRu(id: String): List<Programme>? {
        val page = Resolvers.fetch("$MAIL_RU$id/") ?: return null
        return EpgParser.parseMailRu(page).also {
            if (it.isEmpty()) Log.w(MainActivity.TAG, "epg: на странице Mail.ru нет передач, канал $id")
        }
    }

    private fun loadIptvx(id: String): List<Programme>? {
        if (System.currentTimeMillis() < iptvxDownUntil) return null
        // Короткий тайм-аут: при недоступном iptvx экран телепрограммы не ждёт долго.
        val page = Resolvers.fetch(IPTVX + id, timeoutMs = 5_000)
        if (page == null) {
            iptvxDownUntil = System.currentTimeMillis() + IPTVX_PAUSE_MS
            return null
        }
        return try {
            EpgParser.parseIptvx(page)
        } catch (e: RuntimeException) {
            Log.w(MainActivity.TAG, "epg: страницу iptvx не разобрали, канал $id, ${page.length} символов", e)
            null
        }
    }
}
