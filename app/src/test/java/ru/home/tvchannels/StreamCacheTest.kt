package ru.home.tvchannels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamCacheTest {

    private class MapStore : StreamCache.Store {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private var now = 1_000_000_000L
    private val store = MapStore()
    private val cache = StreamCache(store) { now }

    private val hour = 60 * 60 * 1000L
    private val vitrina = "vitrina:https://player.example/ctc.html"

    @Test
    fun returnsSavedStreamWhileFresh() {
        cache.put(vitrina, "https://edge/ctc.m3u8?token=1")
        now += 11 * hour

        assertEquals("https://edge/ctc.m3u8?token=1", cache.get(vitrina))
    }

    @Test
    fun forgetsStreamAfterSourceLifetime() {
        cache.put(vitrina, "https://edge/ctc.m3u8?token=1")
        now += 13 * hour

        assertNull(cache.get(vitrina))
    }

    @Test
    fun lifetimeDependsOnSource() {
        cache.put("okru:123", "https://okcdn/1.m3u8")
        cache.put("vkplay:disney", "https://okcdn/2.m3u8")
        now += 2 * hour

        assertEquals("https://okcdn/1.m3u8", cache.get("okru:123"))
        assertNull(cache.get("vkplay:disney"))
    }

    @Test
    fun invalidateRemovesStream() {
        cache.put(vitrina, "https://edge/ctc.m3u8?token=1")

        cache.invalidate(vitrina)

        assertNull(cache.get(vitrina))
    }

    @Test
    fun survivesRestartThroughStore() {
        cache.put(vitrina, "https://edge/ctc.m3u8?token=1")

        val afterRestart = StreamCache(store) { now + hour }

        assertEquals("https://edge/ctc.m3u8?token=1", afterRestart.get(vitrina))
    }

    @Test
    fun ignoresBrokenStoredValue() {
        store.put(StreamCache.KEY_PREFIX + vitrina, "мусор")

        assertNull(cache.get(vitrina))
    }
}
