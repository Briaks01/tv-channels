package ru.home.tvchannels

import android.content.Context
import java.io.File

data class Channel(
    val number: Int,
    val name: String,
    val group: String,
    /** первая — основная, остальные — запасные */
    val urls: List<String>,
    val logo: String?,
    /** основная ссылка — HLS, даже если по адресу не видно */
    val hls: Boolean,
    /** идентификатор телепрограммы на epg.iptvx.one */
    val epg: String?,
) {
    /** стабильный ключ для избранного и недавних: номера при обновлении плейлиста меняются */
    val key: String get() = name.lowercase()

    fun isHls(urlIndex: Int) = (urlIndex == 0 && hls) || urls[urlIndex].contains(".m3u8")
}

object Playlist {
    const val FILE_NAME = "channels.m3u"

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    /**
     * Плейлист из внешней папки приложения имеет приоритет над встроенным:
     * так каналы можно обновить через `adb push` без пересборки.
     */
    fun load(context: Context): List<Channel> {
        val override = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
        val text = if (override != null && override.canRead()) {
            override.readText()
        } else {
            context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
        }
        return parse(text)
    }

    fun parse(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var info: String? = null
        val urls = mutableListOf<String>()

        fun flush() {
            val line = info ?: return
            if (urls.isEmpty()) return
            val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
            result += Channel(
                number = result.size + 1,
                name = line.substringAfterLast(',').trim(),
                group = attrs["group-title"] ?: "Другие",
                urls = urls.toList(),
                logo = attrs["x-logo"],
                hls = attrs["x-hls"] == "1",
                epg = attrs["x-epg"],
            )
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    flush()
                    info = line
                    urls.clear()
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                info != null -> urls += line
            }
        }
        flush()
        return result
    }
}
