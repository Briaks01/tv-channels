package ru.home.tvchannels

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Скачивает список каналов из репозитория tv-channels-list на GitHub.
 * GitHub Actions пересобирает этот список по расписанию.
 * Приложение скачивает список в фоне и применяет при следующем запуске.
 * Так номера каналов не меняются во время просмотра.
 * Логотипы новых каналов приложение скачивает в отдельный каталог.
 * Список из APK остаётся запасным.
 */
object RemotePlaylist {
    // Список каналов лежит в отдельном репозитории tv-channels-list.
    private val bases = listOf(
        "https://raw.githubusercontent.com/Briaks01/tv-channels-list/main",
        // Зеркало нужно, если провайдер не пропускает raw.githubusercontent.com.
        "https://cdn.jsdelivr.net/gh/Briaks01/tv-channels-list@main",
    )
    private const val MIN_CHANNELS = 100

    fun file(context: Context) = File(context.filesDir, Playlist.FILE_NAME)
    fun logoDir(context: Context) = File(context.filesDir, "logos")

    /** Вызывать не из главного потока. */
    fun refresh(context: Context) {
        for (base in bases) {
            val text = Resolvers.fetch("$base/${Playlist.FILE_NAME}", timeoutMs = 8_000) ?: continue
            val channels = runCatching { Playlist.parse(text) }.getOrNull().orEmpty()
            if (!text.startsWith("#EXTM3U") || channels.size < MIN_CHANNELS) {
                Log.w(MainActivity.TAG, "remote playlist rejected from $base: ${channels.size} channels")
                continue
            }
            val target = file(context)
            if (!target.exists() || target.readText() != text) {
                val tmp = File(context.filesDir, "${Playlist.FILE_NAME}.tmp")
                tmp.writeText(text)
                tmp.renameTo(target)
            }
            Dbg.log("remote", "channels" to channels.size, "from" to base.substringAfter("//").substringBefore('/'))
            fetchMissingLogos(context, base, channels)
            return
        }
        Dbg.log("remote", "channels" to 0, "from" to "none")
    }

    private fun fetchMissingLogos(context: Context, base: String, channels: List<Channel>) {
        val dir = logoDir(context).apply { mkdirs() }
        val bundled = context.assets.list("logos").orEmpty().toSet()
        for (name in channels.mapNotNull { it.logo }.distinct()) {
            val file = "$name.png"
            if (file in bundled || File(dir, file).exists()) continue
            val bytes = Resolvers.fetchBytes("$base/logos/$file") ?: continue
            File(dir, file).writeBytes(bytes)
        }
    }
}
