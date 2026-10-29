package ru.home.tvchannels

import android.text.Html
import android.util.Log
import android.util.LruCache
import java.util.Calendar
import java.util.TimeZone

data class Programme(val start: Long, val end: Long, val title: String, val desc: String) {
    fun isNow(now: Long) = now in start until end
    fun progress(now: Long) = ((now - start) * 100 / (end - start).coerceAtLeast(1)).toInt().coerceIn(0, 100)
}

/**
 * Телепрограмма по запросу, в памяти держится недолго — на диск ничего не качается.
 * Сначала tv.mail.ru (сегодняшние телесутки, сайт российский — со стика доступен),
 * иначе epg.iptvx.one/id/<id> (неделя, время московское; сайт за Cloudflare и у
 * части провайдеров не грузится). load() вызывать не из главного потока.
 */
object Epg {
    private const val BASE = "https://epg.iptvx.one/id/"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private class Entry(val loadedAt: Long, val programmes: List<Programme>)

    private val cache = LruCache<String, Entry>(40)

    private const val LD_JSON = "<script type=\"application/ld+json\""
    private const val DAY_OPEN = "<h3>"
    private const val TIME_OPEN = "<span class=\"prog_time\">"
    private const val TITLE_OPEN = "<span class=\"prog_title"
    private const val DESC_OPEN = "<div class=\"prog_desc\">"

    private val dayRegex = Regex("""(\d{1,2}) (\p{L}+) (\d{4})""")
    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    fun has(ch: Channel) = ch.mailru != null || ch.epg != null

    /** Из кэша, если программа свежая; иначе null — тогда нужен load(). */
    fun cached(ch: Channel): List<Programme>? =
        cache.get(ch.key)?.takeIf { System.currentTimeMillis() - it.loadedAt < TTL_MS }?.programmes

    fun load(ch: Channel): List<Programme>? {
        cached(ch)?.let { return it }
        val list = ch.mailru?.let(::loadMailRu)?.takeIf { it.isNotEmpty() }
            ?: ch.epg?.let(::loadIptvx)
        if (!list.isNullOrEmpty()) cache.put(ch.key, Entry(System.currentTimeMillis(), list))
        return list
    }

    private fun loadIptvx(id: String): List<Programme>? {
        val page = Resolvers.fetch(BASE + id) ?: return null
        return try {
            parse(page)
        } catch (e: RuntimeException) {
            Log.w(MainActivity.TAG, "epg: parse failed for $id (${page.length} chars)", e)
            null
        }
    }

    /** Mail.ru: расписание вшито в страницу канала как schema.org Event (JSON-LD), время в UTC. */
    private fun loadMailRu(id: String): List<Programme>? {
        val page = Resolvers.fetch("https://tv.mail.ru/moskva/channel/$id/") ?: return null
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        val out = mutableListOf<Programme>()
        var pos = 0
        while (true) {
            val tag = page.indexOf(LD_JSON, pos)
            if (tag < 0) break
            val start = page.indexOf('>', tag) + 1
            val end = page.indexOf("</script>", start)
            if (start <= 0 || end < 0) break
            pos = end
            val json = page.substring(start, end).trim()
            if (!json.startsWith("[")) continue
            val events = try {
                org.json.JSONArray(json)
            } catch (e: org.json.JSONException) {
                continue
            }
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                if (ev.optString("@type") != "Event") continue
                val from = runCatching { iso.parse(ev.getString("startDate"))!!.time }.getOrNull() ?: continue
                val to = runCatching { iso.parse(ev.getString("endDate"))!!.time }.getOrNull() ?: continue
                out += Programme(from, to, ev.optString("name"), "")
            }
        }
        return out.sortedBy { it.start }.takeIf { it.isNotEmpty() }
            .also { if (it == null) Log.w(MainActivity.TAG, "epg: no events on mail.ru channel $id") }
    }

    fun nowAndNext(list: List<Programme>, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val i = list.indexOfFirst { it.isNow(now) }
        return if (i < 0) null to list.firstOrNull { it.start > now } else list[i] to list.getOrNull(i + 1)
    }

    /**
     * Разбор поиском подстрок, а не регулярным выражением: страницы бывают
     * по мегабайту с длинными описаниями, и ICU-регулярки с «.*?» на Android
     * на таком объёме падают с переполнением стека.
     */
    fun parse(page: String): List<Programme> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
        var day: Calendar? = null
        var lastMinutes = -1
        val starts = mutableListOf<Triple<Long, String, String>>()

        var pos = 0
        while (true) {
            val h3 = page.indexOf(DAY_OPEN, pos)
            val time = page.indexOf(TIME_OPEN, pos)
            if (h3 < 0 && time < 0) break

            if (h3 >= 0 && (time < 0 || h3 < time)) {
                val end = page.indexOf("</h3>", h3)
                if (end < 0) break
                pos = end
                val d = dayRegex.find(page.substring(h3 + DAY_OPEN.length, end)) ?: continue
                val month = months.indexOf(d.groupValues[2])
                if (month < 0) continue
                day = (cal.clone() as Calendar).apply {
                    clear()
                    set(d.groupValues[3].toInt(), month, d.groupValues[1].toInt())
                }
                lastMinutes = -1
                continue
            }

            // <span class="prog_time">HH:MM</span> <span class="prog_title …">Название</span></div>
            // [<div class="prog_desc">Описание</div>]
            val t = time + TIME_OPEN.length
            pos = t
            val hh = page.substring(t, t + 2).toIntOrNull() ?: continue
            val mm = page.substring(t + 3, t + 5).toIntOrNull() ?: continue
            val titleTag = page.indexOf(TITLE_OPEN, t)
            val titleStart = page.indexOf('>', titleTag) + 1
            val titleEnd = page.indexOf("</span></div>", titleStart)
            if (titleTag < 0 || titleStart <= 0 || titleEnd < 0) break
            pos = titleEnd
            var desc = ""
            val after = titleEnd + "</span></div>".length
            if (page.startsWith(DESC_OPEN, after)) {
                val descEnd = page.indexOf("</div>", after)
                if (descEnd > 0) {
                    desc = page.substring(after + DESC_OPEN.length, descEnd)
                    pos = descEnd
                }
            }

            val base = day ?: continue
            val minutes = hh * 60 + mm
            // передачи после полуночи идут под тем же днём — переносим на следующие сутки
            if (lastMinutes >= 0 && minutes < lastMinutes) base.add(Calendar.DAY_OF_MONTH, 1)
            lastMinutes = minutes
            val start = (base.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hh)
                set(Calendar.MINUTE, mm)
            }.timeInMillis
            starts += Triple(start, text(page.substring(titleStart, titleEnd)), text(desc))
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
