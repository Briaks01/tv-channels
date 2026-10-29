package ru.home.tvchannels

import android.text.Html
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Получает ссылку на поток перед запуском. Источники подписывают такие ссылки
 * токеном, и токен быстро истекает. Функции вызывают не из главного потока.
 */
object Resolvers {
    /**
     * Приложение и плеер посылают один User-Agent.
     * Сеть доставки Одноклассников обслуживает OK и VK Play.
     * Сеть записывает тип клиента в подпись ссылки, в параметр srcAg.
     * Если поток скачивает клиент другого типа, сеть отвечает кодом 403.
     */
    const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64)"

    fun needsResolve(url: String) =
        url.startsWith(OkRu.SCHEME) || url.startsWith(Vitrina.SCHEME) || url.startsWith(VkPlay.SCHEME)

    @Volatile private var cache: StreamCache? = null

    /** Подключает постоянное хранилище ссылок. Вызывают один раз при запуске приложения. */
    fun init(context: android.content.Context) {
        if (cache != null) return
        val sp = context.getSharedPreferences("streams", android.content.Context.MODE_PRIVATE)
        cache = StreamCache(object : StreamCache.Store {
            override fun get(key: String) = sp.getString(key, null)
            override fun put(key: String, value: String) = sp.edit().putString(key, value).apply()
            override fun remove(key: String) = sp.edit().remove(key).apply()
        }) { System.currentTimeMillis() }
    }

    /** Возвращает сохранённую ссылку на поток без запроса в сеть. Если её нет, возвращает null. */
    fun cached(url: String): String? = cache?.get(url)

    fun invalidate(url: String) {
        cache?.invalidate(url)
    }

    /** Получает ссылку на поток из сети и сохраняет её. */
    fun resolve(url: String): String? = when {
        url.startsWith(OkRu.SCHEME) -> OkRu.resolve(url)
        url.startsWith(Vitrina.SCHEME) -> Vitrina.resolve(url)
        url.startsWith(VkPlay.SCHEME) -> VkPlay.resolve(url)
        else -> url
    }.also { stream -> if (stream != null && needsResolve(url)) cache?.put(url, stream) }

    internal fun fetchBytes(url: String, timeoutMs: Int = 8_000): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } catch (e: java.io.IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    internal fun fetch(url: String, referer: String? = null, timeoutMs: Int = 10_000): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (referer != null) conn.setRequestProperty("Referer", referer)
            val code = conn.responseCode
            if (code != 200) {
                Log.w(MainActivity.TAG, "resolve: HTTP $code for $url")
                null
            } else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            Log.w(MainActivity.TAG, "resolve: $e for $url")
            null
        } finally {
            conn.disconnect()
        }
    }
}

/** Получает поток OK.ru по ссылке okru:<id>. OK.ru подписывает ссылку адресом IP на сутки. */
object OkRu {
    const val SCHEME = "okru:"

    private val optionsRegex = Regex("""data-options="([^"]+)"""")

    fun resolve(url: String): String? {
        val id = url.removePrefix(SCHEME)
        val page = Resolvers.fetch("https://ok.ru/videoembed/$id") ?: return null
        val raw = optionsRegex.find(page)?.groupValues?.get(1) ?: return null
        val options = JSONObject(Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString())
        val flashvars = options.optJSONObject("flashvars") ?: return null
        val meta = flashvars.optJSONObject("metadata")
            ?: flashvars.optString("metadata").takeIf { it.isNotEmpty() }?.let(::JSONObject)
            ?: return null
        return listOf("hlsMasterPlaylistUrl", "hlsManifestUrl", "ondemandHls")
            .firstNotNullOfOrNull { key -> meta.optString(key).takeIf { it.isNotEmpty() } }
    }
}

/**
 * Получает официальный поток «Витрины ТВ» по ссылке `vitrina:<адрес плеера>`.
 * Плеер содержит шаблон адреса балансировщика с параметром `egress_version_id`.
 * Сервис меняет этот параметр. Поэтому шаблон берут из плеера при каждом запуске.
 * Балансировщик отдаёт ссылки на HLS с токеном на несколько дней.
 */
object Vitrina {
    const val SCHEME = "vitrina:"
    private const val REFERER = "https://smotret.tv/"

    private val balancerRegex = Regex("""https://media\.mediavitrina\.ru/balancer/v3/[^"'\s]+""")

    fun resolve(url: String): String? {
        val playerUrl = url.removePrefix(SCHEME)
        val player = Resolvers.fetch(playerUrl, REFERER) ?: return null
        val template = balancerRegex.find(player)?.value
            ?: return null.also { Log.w(MainActivity.TAG, "vitrina: no balancer in player $playerUrl") }
        val balancer = template
            .replace("{{PLAYER_REFERER_HOSTNAME}}", "smotret.tv")
            .replace(Regex("""\{\{\w+\}\}"""), "") // ICU на Android требует экранировать и «}»
        // балансировщик отвечает только плееру «Витрины» (по Referer)
        val streams = Resolvers.fetch(balancer, playerUrl) ?: return null
        val hls = JSONObject(streams).optJSONArray("hls") ?: return null
        return if (hls.length() > 0) hls.getString(0) else null
    }
}

/**
 * Получает поток VK Play Live (live.vkvideo.ru) по ссылке vkplay:<канал>.
 * Открытый API отдаёт ссылки на поток. Подпись ссылки содержит адрес IP и срок действия.
 */
object VkPlay {
    const val SCHEME = "vkplay:"
    private val preferred = listOf("live_hls", "live_playback_hls")

    fun resolve(url: String): String? {
        val channel = url.removePrefix(SCHEME)
        val raw = Resolvers.fetch("https://api.live.vkvideo.ru/v1/blog/$channel/public_video_stream") ?: return null
        val stream = JSONObject(raw).optJSONArray("data")?.optJSONObject(0) ?: return null
        val urls = HashMap<String, String>()
        for (key in listOf("playerUrls", "sharedPlayerUrls")) {
            val list = stream.optJSONArray(key) ?: continue
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val link = item.optString("url")
                if (link.isNotEmpty()) urls.putIfAbsent(item.optString("type"), link)
            }
        }
        return preferred.firstNotNullOfOrNull(urls::get)
    }
}
