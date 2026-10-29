package ru.home.tvchannels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {

    private val text = """
        #EXTM3U
        #EXTINF:-1 group-title="Федеральные" x-logo="sts" x-hls="1" x-epg="sts" x-mailru="1112",СТС
        vitrina:https://player.example/ctc.html
        https://cdn.example/sts/index.m3u8
        http://backup.example/sts.ts
        #EXTINF:-1,Без ссылки
        #EXTINF:-1 x-hls="1",Пятница!
        https://cdn.example/friday
    """.trimIndent()

    @Test
    fun parsesChannelsWithAllLinksAndAttributes() {
        val list = Playlist.parse(text)

        assertEquals(listOf("СТС", "Пятница!"), list.map { it.name })
        val sts = list[0]
        assertEquals(1, sts.number)
        assertEquals("Федеральные", sts.group)
        assertEquals(3, sts.urls.size)
        assertEquals("sts", sts.logo)
        assertEquals("sts", sts.epg)
        assertEquals("1112", sts.mailru)
    }

    @Test
    fun skipsChannelWithoutLinkAndKeepsNumbering() {
        val friday = Playlist.parse(text)[1]

        assertEquals(2, friday.number)
        assertEquals("Другие", friday.group)
        assertNull(friday.epg)
    }

    @Test
    fun hlsFlagCoversFirstLinkOnly() {
        val sts = Playlist.parse(text)[0]

        assertTrue(sts.isHls(0))
        assertTrue(sts.isHls(1))
        assertFalse(sts.isHls(2))
    }
}
