package ru.home.tvchannels

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

/**
 * Логи самопроверки (tools/selftest.py). Только в отладочной сборке; строки
 * с тегом ST в формате «событие ключ=значение …», чтобы их было легко разбирать.
 */
object Dbg {
    const val TAG = "ST"
    val on = BuildConfig.DEBUG

    fun log(event: String, vararg kv: Pair<String, Any?>) {
        if (!on) return
        Log.i(TAG, buildString {
            append(event)
            for ((k, v) in kv) append(' ').append(k).append('=').append(v.toString().replace(' ', '_'))
        })
    }

    /** Нажатие: lag — сколько событие ждало в очереди до обработки. */
    fun key(e: KeyEvent, where: String) {
        if (!on || e.action != KeyEvent.ACTION_DOWN) return
        log("key", "code" to KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_"),
            "repeat" to e.repeatCount, "lag" to SystemClock.uptimeMillis() - e.eventTime, "in" to where)
    }

    inline fun <T> timed(what: String, block: () -> T): T {
        if (!on) return block()
        val t = SystemClock.elapsedRealtime()
        return block().also { log("timing", "what" to what, "ms" to SystemClock.elapsedRealtime() - t) }
    }

    /** Подвисания главного потока: кадр дольше 100 мс. */
    fun watchMainThread() {
        if (!on) return
        val choreographer = Choreographer.getInstance()
        var last = 0L
        choreographer.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (last != 0L) {
                    val gap = (now - last) / 1_000_000
                    if (gap > 100) log("jank", "ms" to gap)
                }
                last = now
                choreographer.postFrameCallback(this)
            }
        })
    }

    /** Всё, что важно для звука и картинки: старт, буферизация, недогрузы, качество, ошибки. */
    @OptIn(UnstableApi::class)
    class Playback(private val channel: () -> String) : AnalyticsListener {
        private var startedAt = 0L
        private var bufferingSince = 0L

        fun started(url: String) {
            startedAt = SystemClock.elapsedRealtime()
            log("stream", "ch" to channel(), "url" to url.take(90))
        }

        override fun onPlaybackStateChanged(t: AnalyticsListener.EventTime, state: Int) {
            val now = SystemClock.elapsedRealtime()
            when (state) {
                Player.STATE_BUFFERING -> bufferingSince = now
                Player.STATE_READY -> {
                    if (startedAt != 0L) {
                        log("ready", "ch" to channel(), "ms" to now - startedAt)
                        startedAt = 0L
                    } else if (bufferingSince != 0L) {
                        log("rebuffer", "ch" to channel(), "ms" to now - bufferingSince)
                    }
                    bufferingSince = 0L
                }
            }
        }

        override fun onAudioUnderrun(t: AnalyticsListener.EventTime, size: Int, ms: Long, since: Long) =
            log("underrun", "ch" to channel(), "bufferMs" to ms, "sinceLastFeedMs" to since)

        override fun onDroppedVideoFrames(t: AnalyticsListener.EventTime, count: Int, elapsed: Long) =
            log("dropped", "ch" to channel(), "frames" to count, "ms" to elapsed)

        override fun onVideoInputFormatChanged(t: AnalyticsListener.EventTime, f: Format, r: DecoderReuseEvaluation?) =
            log("video", "ch" to channel(), "res" to "${f.width}x${f.height}", "kbps" to f.bitrate / 1000, "codec" to f.codecs)

        override fun onAudioInputFormatChanged(t: AnalyticsListener.EventTime, f: Format, r: DecoderReuseEvaluation?) =
            log("audio", "ch" to channel(), "mime" to f.sampleMimeType, "ch_n" to f.channelCount, "hz" to f.sampleRate)

        override fun onBandwidthEstimate(t: AnalyticsListener.EventTime, ms: Int, bytes: Long, bitrate: Long) =
            log("bandwidth", "kbps" to bitrate / 1000)

        override fun onLoadError(
            t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData,
            error: java.io.IOException, canceled: Boolean,
        ) = log("loaderror", "ch" to channel(), "err" to error.javaClass.simpleName, "msg" to error.message?.take(80))

        override fun onPlayerError(t: AnalyticsListener.EventTime, e: PlaybackException) =
            log("error", "ch" to channel(), "code" to e.errorCodeName)
    }
}
