package ru.home.tvchannels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class EpgParserTest {

    private fun msk(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun iptvxItem(time: String, title: String, desc: String? = null) =
        """<div class="prog_id"><span class="prog_time">$time</span> <span class="prog_title adver">$title</span></div>""" +
            (desc?.let { """<div class="prog_desc">$it</div>""" } ?: "")

    @Test
    fun iptvxGivesStartEndTitleAndDescription() {
        val page = "<h3>Четверг, 29 октября 2026 г.</h3><section>" +
            iptvxItem("21:00", "Время", "Информационная &quot;программа&quot;") +
            iptvxItem("21:45", "Фильм") + "</section>"

        val list = EpgParser.parseIptvx(page)

        assertEquals(2, list.size)
        assertEquals(msk(2026, 10, 29, 21, 0), list[0].start)
        assertEquals(list[1].start, list[0].end)
        assertEquals("Время", list[0].title)
        assertEquals("Информационная \"программа\"", list[0].desc)
        assertEquals(list[1].start + 60 * 60 * 1000L, list[1].end)
    }

    @Test
    fun iptvxMovesProgrammesAfterMidnightToNextDay() {
        val page = "<h3>Суббота, 31 октября 2026 г.</h3>" +
            iptvxItem("23:30", "Ночные новости") + iptvxItem("00:15", "Ночной фильм")

        val list = EpgParser.parseIptvx(page)

        assertEquals(msk(2026, 11, 1, 0, 15), list[1].start)
    }

    @Test
    fun iptvxDropsBroadcastIconsFromTitles() {
        val page = "<h3>Четверг, 29 октября 2026 г.</h3>" +
            iptvxItem("19:00", """<img src="live.png">Футбол┊Кубок""")

        assertEquals("Футбол Кубок", EpgParser.parseIptvx(page)[0].title)
    }

    @Test
    fun iptvxParsesMegabytePageWithLongDescriptions() {
        val desc = "Описание. ".repeat(20_000)
        val page = "<h3>Четверг, 29 октября 2026 г.</h3>" +
            (0 until 20).joinToString("") { iptvxItem("%02d:00".format(it), "Передача $it", desc) }

        assertEquals(20, EpgParser.parseIptvx(page).size)
    }

    @Test
    fun mailRuTakesEventsFromJsonLd() {
        val events = """[{"@type":"Event","name":"Вести","startDate":"2026-10-29T17:00:00+00:00","endDate":"2026-10-29T17:45:00+00:00"},""" +
            """{"@type":"Person","name":"не передача"},""" +
            """{"@type":"Event","name":"Утро","startDate":"2026-10-29T03:00:00+00:00","endDate":"2026-10-29T06:00:00+00:00"}]"""
        val page = """<script type="application/ld+json">{"@type":"WebSite"}</script>""" +
            """<script type="application/ld+json">$events</script>"""

        val list = EpgParser.parseMailRu(page)

        assertEquals(listOf("Утро", "Вести"), list.map { it.title })
        assertEquals(utc(2026, 10, 29, 17, 0), list[1].start)
        assertEquals(utc(2026, 10, 29, 17, 45), list[1].end)
    }

    @Test
    fun mailRuIgnoresBrokenJson() {
        val page = """<script type="application/ld+json">[{"@type":"Event",</script>"""

        assertTrue(EpgParser.parseMailRu(page).isEmpty())
    }

    @Test
    fun nowAndNextInsideGapAndAfterAll() {
        val a = Programme(100, 200, "A", "")
        val b = Programme(300, 400, "B", "")
        val list = listOf(a, b)

        assertEquals(a to b, EpgParser.nowAndNext(list, 150))
        assertEquals(null to b, EpgParser.nowAndNext(list, 250))
        val after = EpgParser.nowAndNext(list, 500)
        assertNull(after.first)
        assertNull(after.second)
    }

    @Test
    fun progressStaysInsideZeroToHundred() {
        val p = Programme(1000, 2000, "A", "")

        assertEquals(0, p.progress(500))
        assertEquals(50, p.progress(1500))
        assertEquals(100, p.progress(2500))
    }
}
