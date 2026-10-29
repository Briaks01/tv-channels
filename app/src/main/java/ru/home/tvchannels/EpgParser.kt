package ru.home.tvchannels

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Передача телепрограммы. Время — в миллисекундах от начала эпохи. */
data class Programme(val start: Long, val end: Long, val title: String, val desc: String) {
    fun isNow(now: Long) = now in start until end

    /** Возвращает долю прошедшего времени передачи в процентах, от 0 до 100. */
    fun progress(now: Long) = ((now - start) * 100 / (end - start).coerceAtLeast(1)).toInt().coerceIn(0, 100)
}

/**
 * Разбирает страницы телепрограммы. Функции чистые: без сети, Android и журнала.
 * Поэтому их проверяют обычные JVM-тесты.
 */
object EpgParser {
    private const val HOUR_MS = 60 * 60 * 1000L

    private const val LD_JSON = "<script type=\"application/ld+json\""
    private const val DAY_OPEN = "<h3>"
    private const val TIME_OPEN = "<span class=\"prog_time\">"
    private const val TITLE_OPEN = "<span class=\"prog_title"
    private const val TITLE_CLOSE = "</span></div>"
    private const val DESC_OPEN = "<div class=\"prog_desc\">"

    private val dayRegex = Regex("""(\d{1,2}) (\p{L}+) (\d{4})""")
    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** Возвращает текущую и следующую передачу. Если сейчас перерыв, текущей нет. */
    fun nowAndNext(list: List<Programme>, now: Long): Pair<Programme?, Programme?> {
        val i = list.indexOfFirst { it.isNow(now) }
        return if (i < 0) null to list.firstOrNull { it.start > now } else list[i] to list.getOrNull(i + 1)
    }

    /**
     * Разбирает страницу канала на epg.iptvx.one. Время на странице московское.
     * Страницу разбирают поиском подстрок. Регулярное выражение ICU на Android
     * падает с переполнением стека на странице размером 1 МБ.
     */
    fun parseIptvx(page: String): List<Programme> {
        val moscow = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
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
                day = parseDay(page.substring(h3 + DAY_OPEN.length, end), moscow) ?: continue
                lastMinutes = -1
                continue
            }

            // Разметка передачи:
            // <span class="prog_time">ЧЧ:ММ</span> <span class="prog_title …">Название</span></div>
            // и необязательно <div class="prog_desc">Описание</div>.
            val t = time + TIME_OPEN.length
            pos = t
            val hh = page.substring(t, t + 2).toIntOrNull() ?: continue
            val mm = page.substring(t + 3, t + 5).toIntOrNull() ?: continue
            val titleTag = page.indexOf(TITLE_OPEN, t)
            val titleStart = page.indexOf('>', titleTag) + 1
            val titleEnd = page.indexOf(TITLE_CLOSE, titleStart)
            if (titleTag < 0 || titleStart <= 0 || titleEnd < 0) break
            pos = titleEnd
            var desc = ""
            val after = titleEnd + TITLE_CLOSE.length
            if (page.startsWith(DESC_OPEN, after)) {
                val descEnd = page.indexOf("</div>", after)
                if (descEnd > 0) {
                    desc = page.substring(after + DESC_OPEN.length, descEnd)
                    pos = descEnd
                }
            }

            val base = day ?: continue
            val minutes = hh * 60 + mm
            // Передачи после полуночи стоят под прежним днём. Их переносят на следующие сутки.
            if (lastMinutes >= 0 && minutes < lastMinutes) base.add(Calendar.DAY_OF_MONTH, 1)
            lastMinutes = minutes
            val start = (base.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hh)
                set(Calendar.MINUTE, mm)
            }.timeInMillis
            starts += Triple(start, htmlToText(page.substring(titleStart, titleEnd)), htmlToText(desc))
        }
        return withEnds(starts)
    }

    /**
     * Разбирает страницу канала на tv.mail.ru. Mail.ru вшивает расписание
     * в страницу как события schema.org (JSON-LD). Время в UTC.
     */
    fun parseMailRu(page: String): List<Programme> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
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
        return out.sortedBy { it.start }
    }

    /**
     * Переводит фрагмент HTML в простой текст. Значки трансляций в названиях
     * (теги img) и символ ┊ убирает.
     */
    fun htmlToText(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]*>"), ""))
            .replace('┊', ' ')
            .replace(' ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    )

    private fun decodeEntities(text: String) =
        Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(text) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x") -> name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> entities[name]
            } ?: m.value
        }

    private fun parseDay(header: String, moscow: Calendar): Calendar? {
        val d = dayRegex.find(header) ?: return null
        val month = months.indexOf(d.groupValues[2])
        if (month < 0) return null
        return (moscow.clone() as Calendar).apply {
            clear()
            set(d.groupValues[3].toInt(), month, d.groupValues[1].toInt())
        }
    }

    /** Конец передачи — начало следующей. Последней передаче дают один час. */
    private fun withEnds(starts: List<Triple<Long, String, String>>): List<Programme> {
        val sorted = starts.sortedBy { it.first }
        return sorted.mapIndexed { i, (start, title, desc) ->
            Programme(start, sorted.getOrNull(i + 1)?.first ?: (start + HOUR_MS), title, desc)
        }.filter { it.end > it.start }
    }
}
