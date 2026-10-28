package ru.home.tvchannels

import android.text.Html
import android.util.LruCache
import java.util.Calendar
import java.util.TimeZone

data class Programme(val start: Long, val end: Long, val title: String, val desc: String) {
    fun isNow(now: Long) = now in start until end
    fun progress(now: Long) = ((now - start) * 100 / (end - start).coerceAtLeast(1)).toInt().coerceIn(0, 100)
}

/**
 * Телепрограмма с epg.iptvx.one: страница канала /id/<id> отдаёт неделю
 * передач (время московское). Берётся по запросу, в памяти держится недолго —
 * на диск ничего не качается. load() вызывать не из главного потока.
 */
object Epg {
    private const val BASE = "https://epg.iptvx.one/id/"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private class Entry(val loadedAt: Long, val programmes: List<Programme>)

    private val cache = LruCache<String, Entry>(40)

    private val itemRegex = Regex(
        """<h3>([^<]+)</h3>|<span class="prog_time">(\d\d):(\d\d)</span>\s*""" +
            """<span class="prog_title[^"]*">(.*?)</span></div>(?:<div class="prog_desc">(.*?)</div>)?""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val dayRegex = Regex("""(\d{1,2}) (\p{L}+) (\d{4})""")
    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** Из кэша, если программа свежая; иначе null — тогда нужен load(). */
    fun cached(id: String): List<Programme>? =
        cache.get(id)?.takeIf { System.currentTimeMillis() - it.loadedAt < TTL_MS }?.programmes

    fun load(id: String): List<Programme>? {
        cached(id)?.let { return it }
        val page = Resolvers.fetch(BASE + id) ?: return null
        val list = parse(page)
        if (list.isNotEmpty()) cache.put(id, Entry(System.currentTimeMillis(), list))
        return list
    }

    fun nowAndNext(list: List<Programme>, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val i = list.indexOfFirst { it.isNow(now) }
        return if (i < 0) null to list.firstOrNull { it.start > now } else list[i] to list.getOrNull(i + 1)
    }

    fun parse(page: String): List<Programme> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
        var day: Calendar? = null
        var lastMinutes = -1
        val starts = mutableListOf<Triple<Long, String, String>>()

        for (m in itemRegex.findAll(page)) {
            val header = m.groupValues[1]
            if (header.isNotEmpty()) {
                val d = dayRegex.find(header) ?: continue
                val month = months.indexOf(d.groupValues[2])
                if (month < 0) continue
                day = (cal.clone() as Calendar).apply {
                    clear()
                    set(d.groupValues[3].toInt(), month, d.groupValues[1].toInt())
                }
                lastMinutes = -1
                continue
            }
            val base = day ?: continue
            val minutes = m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
            // передачи после полуночи идут под тем же днём — переносим на следующие сутки
            if (lastMinutes >= 0 && minutes < lastMinutes) base.add(Calendar.DAY_OF_MONTH, 1)
            lastMinutes = minutes
            val start = (base.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, minutes / 60)
                set(Calendar.MINUTE, minutes % 60)
            }.timeInMillis
            starts += Triple(start, text(m.groupValues[4]), text(m.groupValues[5]))
        }
        starts.sortBy { it.first }
        return starts.mapIndexed { i, (start, title, desc) ->
            val end = starts.getOrNull(i + 1)?.first ?: (start + 60 * 60 * 1000L)
            Programme(start, end, title, desc)
        }.filter { it.end > it.start }
    }

    private fun text(html: String) =
        // картинки в названиях (значки трансляций) fromHtml превращает в U+FFFC — выкидываем
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace("\uFFFC", "").replace('┊', ' ').trim()
}
