package ru.home.tvchannels

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var allChannels: List<Channel>
    /** каналы с учётом настроек (без региональных, если они скрыты) */
    private lateinit var channels: List<Channel>
    private lateinit var tabs: List<Tab>

    private lateinit var playerView: PlayerView
    private lateinit var infoBox: View
    private lateinit var infoNumber: TextView
    private lateinit var infoName: TextView
    private lateinit var infoTime: TextView
    private lateinit var status: TextView
    private lateinit var menu: View
    private lateinit var tabList: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var infoEpg: View
    private lateinit var infoNow: TextView
    private lateinit var infoProgress: android.widget.ProgressBar
    private lateinit var infoNext: TextView
    private lateinit var guide: View
    private lateinit var guideTitle: TextView
    private lateinit var guideLogo: android.widget.ImageView
    private lateinit var guideList: RecyclerView
    private lateinit var guideStatus: TextView
    private lateinit var guideAdapter: GuideAdapter
    private lateinit var logos: LogoCache
    /** канал, чья программа открыта (не обязательно тот, что идёт) */
    private var guideIndex = 0
    private lateinit var tabAdapter: TabAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private var current = 0
    private var urlIndex = 0
    private var retried = false
    /** направление последнего листания (+1/-1) — для пропуска нерабочих каналов */
    private var moveDir = 0
    private var skipped = 0
    private var lastBack = 0L
    private var digits = ""
    private var firstStart = true
    /** декодер видео уже получил данные текущего потока */
    private var videoFed = false
    /** номер попытки запуска потока — чтобы отбросить устаревший фоновый ответ */
    private var streamAttempt = 0
    private val io = Executors.newSingleThreadExecutor()
    // программа грузится отдельно, чтобы не задерживать запуск потоков
    private val epgIo = Executors.newSingleThreadExecutor()


    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val hideInfo = Runnable { infoBox.visibility = View.GONE }
    private val retry = Runnable { startStream() }
    // поток может «молча» зависнуть без ошибки — тогда считаем его упавшим сами
    private val stallWatchdog = Runnable {
        Log.w(TAG, "stalled: ${channels[current].name} url#$urlIndex")
        player?.stop()
        streamFailed()
    }
    // видео пришло, а кадров нет (так ведёт себя чересстрочный эфир) — сразу к следующему потоку
    private val frameWatchdog = Runnable {
        if (!videoFed) return@Runnable
        Log.w(TAG, "no frames: ${channels[current].name} url#$urlIndex")
        player?.stop()
        streamFailed()
    }
    private val skipDead = Runnable { move(moveDir) }
    private val sleep = Runnable { finish() }
    private val applyDigits = Runnable {
        digits.toIntOrNull()?.let { n ->
            val idx = channels.indexOfFirst { it.number == n }
            // набранный номер идущего канала не перезапускает его
            if (idx == current && isPlaying) showInfo(channels[current]) else if (idx >= 0) play(idx)
        }
        digits = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        allChannels = Playlist.load(this)

        playerView = findViewById(R.id.player_view)
        infoBox = findViewById(R.id.info_box)
        infoNumber = findViewById(R.id.info_number)
        infoName = findViewById(R.id.info_name)
        infoTime = findViewById(R.id.info_time)
        status = findViewById(R.id.status)
        menu = findViewById(R.id.menu)
        tabList = findViewById(R.id.groups)
        grid = findViewById(R.id.grid)
        emptyHint = findViewById(R.id.empty_hint)
        infoEpg = findViewById(R.id.info_epg)
        infoNow = findViewById(R.id.info_now)
        infoProgress = findViewById(R.id.info_progress)
        infoNext = findViewById(R.id.info_next)
        guide = findViewById(R.id.guide)
        guideTitle = findViewById(R.id.guide_title)
        guideLogo = findViewById(R.id.guide_logo)
        guideList = findViewById(R.id.guide_list)
        guideStatus = findViewById(R.id.guide_status)
        guideAdapter = GuideAdapter(::pickFromGuide)
        guideList.layoutManager = LinearLayoutManager(this)
        guideList.adapter = guideAdapter
        guideList.itemAnimator = null

        tabList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        // без анимации изменения элемент не пересоздаётся и не теряет фокус
        tabList.itemAnimator = null

        logos = LogoCache(assets)
        channelAdapter = ChannelAdapter(logos, ::pick, ::toggleFavorite)
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = channelAdapter
        grid.itemAnimator = null

        applySettings()
        val last = prefs.lastChannel
        current = if (prefs.startLast && last != null) {
            channels.indexOfFirst { it.key == last }.coerceAtLeast(0)
        } else 0
    }

    /** Перечитывает настройки, которые влияют на список каналов и вкладки. */
    private fun applySettings() {
        val playingKey = if (::channels.isInitialized) channels.getOrNull(current)?.key else null
        channels = if (prefs.showRegional) allChannels
        else allChannels.filter { it.group != REGIONAL }
        if (playingKey != null) current = channels.indexOfFirst { it.key == playingKey }.coerceAtLeast(0)

        val byKey = channels.associateBy { it.key }
        tabs = buildList {
            add(Tab(getString(R.string.tab_recent), { prefs.recent.mapNotNull(byKey::get) }))
            add(Tab(getString(R.string.tab_favorites), {
                val fav = prefs.favorites
                channels.filter { it.key in fav }
            }))
            add(Tab(getString(R.string.all_channels), { channels }))
            channels.map { it.group }.distinct().forEach { g ->
                add(Tab(g, { channels.filter { it.group == g } }))
            }
            add(Tab(getString(R.string.tab_settings), { emptyList() }) {
                hideMenu()
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            })
        }
        tabAdapter = TabAdapter(tabs, ::showTab)
        tabList.adapter = tabAdapter
        tabAdapter.selected = TAB_ALL
    }

    override fun onStart() {
        super.onStart()
        // после возврата из настроек списки и вкладки могли измениться
        if (firstStart) firstStart = false else applySettings()

        val selector = DefaultTrackSelector(this)
        val maxHeight = prefs.maxHeight
        selector.setParameters(
            if (maxHeight > 0) selector.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            // «Авто» — сразу лучшее качество, а не разгон с низкого
            else selector.buildUponParameters().setForceHighestSupportedBitrate(true)
        )
        player = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory()))
            .build()
            .also {
                it.addListener(playerListener)
                it.addAnalyticsListener(EventLogger(TAG))
                it.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?,
                    ) {
                        // кадры ждём с момента, когда видео дошло до декодера, — получение ссылки
                        // и скачивание первого HD-сегмента сюда не входят
                        if (!videoFed) {
                            videoFed = true
                            handler.removeCallbacks(frameWatchdog)
                            handler.postDelayed(frameWatchdog, FIRST_FRAME_MS)
                        }
                    }
                })
                playerView.player = it
            }
        if (channels.isNotEmpty()) play(current)

        handler.removeCallbacks(sleep)
        if (prefs.sleepMinutes > 0) handler.postDelayed(sleep, prefs.sleepMinutes * 60_000L)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    private fun httpFactory() = DefaultHttpDataSource.Factory()
        .setUserAgent(Resolvers.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    // ---- воспроизведение ----

    private fun move(step: Int) {
        moveDir = step
        play(current + step, keepDir = true)
    }

    private fun play(index: Int, keepDir: Boolean = false) {
        if (!keepDir) {
            moveDir = 0
            skipped = 0
        }
        if (keepDir) skipped++
        current = (index + channels.size) % channels.size
        val ch = channels[current]
        urlIndex = 0
        retried = false
        startStream()

        prefs.lastChannel = ch.key
        prefs.pushRecent(ch.key)
        channelAdapter.playingNumber = ch.number
        showInfo(ch)
    }

    private fun startStream() {
        val p = player ?: return
        val ch = channels[current]
        handler.removeCallbacks(retry)
        handler.removeCallbacks(skipDead)
        handler.removeCallbacks(stallWatchdog)
        handler.removeCallbacks(frameWatchdog)
        handler.postDelayed(stallWatchdog, STALL_MS)
        videoFed = false
        val attempt = ++streamAttempt

        val url = ch.urls[urlIndex]
        if (Resolvers.needsResolve(url)) {
            // ссылку на поток (OK.ru, «Витрина») достаём в фоне; устаревший ответ — канал уже сменили — отбрасываем
            p.stop()
            setStatus(getString(R.string.loading))
            io.execute {
                val stream = runCatching { Resolvers.resolve(url) }
                    .onFailure { Log.w(TAG, "resolve failed: $url", it) }
                    .getOrNull()
                handler.post {
                    if (attempt != streamAttempt || player !== p) return@post
                    if (stream == null) streamFailed() else load(p, stream, hls = true)
                }
            }
        } else {
            load(p, url, ch.isHls(urlIndex))
        }
    }

    private fun load(p: ExoPlayer, url: String, hls: Boolean) {
        val item = MediaItem.Builder().setUri(Uri.parse(url))
        if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(item.build())
        p.prepare()
        p.playWhenReady = true
    }

    private val isPlaying: Boolean
        get() = player?.let { it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_BUFFERING } == true

    private fun showInfo(ch: Channel) {
        infoNumber.text = ch.number.toString()
        infoName.text = ch.name
        infoTime.text = timeFormat.format(Date())
        infoBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, prefs.infoSeconds * 1000L)
        showNowNext(ch)
    }

    // ---- телепрограмма ----

    /** «Сейчас / Далее» на плашке канала; программу подгружаем, если её ещё нет. */
    private fun showNowNext(ch: Channel) {
        val id = ch.epg
        val list = id?.let(Epg::cached)
        if (list != null) {
            fillNowNext(list)
            return
        }
        infoEpg.visibility = View.GONE
        if (id == null) return
        epgIo.execute {
            val loaded = runCatching { Epg.load(id) }.getOrNull() ?: return@execute
            handler.post {
                if (channels[current] == ch && infoBox.visibility == View.VISIBLE) fillNowNext(loaded)
                channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount)
            }
        }
    }

    private fun fillNowNext(list: List<Programme>) {
        val nowMs = System.currentTimeMillis()
        val (now, next) = Epg.nowAndNext(list, nowMs)
        if (now == null && next == null) {
            infoEpg.visibility = View.GONE
            return
        }
        infoEpg.visibility = View.VISIBLE
        infoNow.visibility = if (now != null) View.VISIBLE else View.GONE
        infoProgress.visibility = infoNow.visibility
        now?.let {
            infoNow.text = getString(R.string.epg_now, timeFormat.format(Date(it.start)), it.title)
            infoProgress.progress = it.progress(nowMs)
        }
        infoNext.visibility = if (next != null) View.VISIBLE else View.GONE
        next?.let { infoNext.text = getString(R.string.epg_next, timeFormat.format(Date(it.start)), it.title) }
    }

    private val guideVisible get() = guide.visibility == View.VISIBLE

    private fun showGuide(index: Int) {
        guideIndex = (index + channels.size) % channels.size
        val ch = channels[guideIndex]
        guide.visibility = View.VISIBLE
        infoBox.visibility = View.GONE
        guideTitle.text = getString(R.string.guide_title, ch.number, ch.name)
        guideLogo.setImageBitmap(logos.get(ch.logo))
        guideAdapter.submit(emptyList(), 0)
        guideList.requestFocus()

        val id = ch.epg
        if (id == null) {
            setGuideStatus(R.string.guide_none)
            return
        }
        Epg.cached(id)?.let {
            fillGuide(it)
            return
        }
        setGuideStatus(R.string.guide_loading)
        val shown = guideIndex
        epgIo.execute {
            val loaded = runCatching { Epg.load(id) }.getOrNull()
            handler.post {
                if (!guideVisible || guideIndex != shown) return@post
                when {
                    loaded == null -> setGuideStatus(R.string.guide_error)
                    loaded.isEmpty() -> setGuideStatus(R.string.guide_none)
                    else -> fillGuide(loaded)
                }
            }
        }
    }

    private fun fillGuide(list: List<Programme>) {
        setGuideStatus(null)
        val pos = guideAdapter.submit(list, System.currentTimeMillis())
        // текущую передачу ставим чуть ниже верха, чтобы было видно и то, что шло
        (guideList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 200)
        guideList.post { guideList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun setGuideStatus(res: Int?) {
        guideStatus.visibility = if (res == null) View.GONE else View.VISIBLE
        if (res != null) guideStatus.setText(res)
    }

    private fun hideGuide() {
        guide.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun pickFromGuide() {
        hideGuide()
        if (guideIndex == current && isPlaying) showInfo(channels[current]) else play(guideIndex)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> setStatus(getString(R.string.loading))
                Player.STATE_READY -> {
                    handler.removeCallbacks(stallWatchdog)
                    handler.removeCallbacks(frameWatchdog)
                    setStatus(null)
                    retried = false
                    skipped = 0
                    moveDir = 0
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "error: ${channels[current].name} url#$urlIndex ${error.errorCodeName}", error)
            streamFailed()
        }
    }

    private fun streamFailed() {
        handler.removeCallbacks(stallWatchdog)
        handler.removeCallbacks(frameWatchdog)
        val ch = channels[current]
        when {
            // сначала перебираем запасные ссылки
            urlIndex + 1 < ch.urls.size -> {
                urlIndex++
                startStream()
            }
            // поток мог моргнуть — пробуем ещё раз с начала
            !retried -> {
                retried = true
                urlIndex = 0
                setStatus(getString(R.string.loading))
                handler.postDelayed(retry, 3000)
            }
            // при листании нерабочий канал проскакиваем
            prefs.skipDead && moveDir != 0 && skipped < MAX_SKIP -> {
                setStatus(getString(R.string.unavailable_skip))
                handler.postDelayed(skipDead, 1500)
            }
            else -> setStatus(getString(R.string.unavailable))
        }
    }

    private fun setStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---- меню каналов ----

    private fun pick(ch: Channel) {
        hideMenu()
        val index = channels.indexOf(ch)
        // тот же канал не перезапускаем, если он и так идёт
        if (index == current && isPlaying) {
            showInfo(ch)
            return
        }
        play(index)
    }

    private fun toggleFavorite(ch: Channel) {
        val added = prefs.toggleFavorite(ch.key)
        channelAdapter.favorites = prefs.favorites
        val pos = channelAdapter.items.indexOf(ch)
        if (tabAdapter.selected == TAB_FAVORITES) showTab(TAB_FAVORITES) else channelAdapter.notifyItemChanged(pos)
        Toast.makeText(
            this,
            getString(if (added) R.string.fav_added else R.string.fav_removed, ch.name),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showMenu() {
        menu.visibility = View.VISIBLE
        channelAdapter.favorites = prefs.favorites
        // открываем ту вкладку, где был в прошлый раз; если там нет текущего канала — «Все каналы»
        var tab = tabAdapter.selected
        if (tabs[tab].channels().none { it.number == channels[current].number }) tab = TAB_ALL
        showTab(tab)
        tabList.scrollToPosition(tab)
        focusChannel(current)
    }

    private fun hideMenu() {
        menu.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun showTab(position: Int) {
        tabAdapter.selected = position
        val list = tabs[position].channels()
        channelAdapter.submit(list)
        emptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        emptyHint.setText(if (position == TAB_FAVORITES) R.string.empty_favorites else R.string.empty_recent)
        grid.scrollToPosition(0)
    }

    private fun focusChannel(index: Int) {
        val pos = channelAdapter.items.indexOfFirst { it.number == channels[index].number }.coerceAtLeast(0)
        grid.scrollToPosition(pos)
        grid.post {
            grid.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                ?: grid.requestFocus()
        }
    }

    /** Из верхнего ряда сетки «вверх» ведёт на выбранную вкладку, а не на ту, что над карточкой. */
    private fun focusSelectedTab(): Boolean {
        val tab = tabAdapter.selected
        val view = tabList.findViewHolderForAdapterPosition(tab)?.itemView
        if (view != null) {
            view.requestFocus()
        } else {
            // вкладки нет на экране: сперва прокручиваем, фокус ставим после раскладки
            tabList.scrollToPosition(tab)
            tabList.post { tabList.findViewHolderForAdapterPosition(tab)?.itemView?.requestFocus() }
        }
        return true
    }

    private val menuVisible get() = menu.visibility == View.VISIBLE

    // ---- пульт ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode

        if (guideVisible) {
            when (code) {
                KeyEvent.KEYCODE_BACK -> hideGuide()
                KeyEvent.KEYCODE_DPAD_LEFT -> showGuide(guideIndex - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> showGuide(guideIndex + 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // пустая программа — OK просто включает канал
                    if (guideAdapter.itemCount == 0) pickFromGuide() else return super.dispatchKeyEvent(event)
                }
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        if (menuVisible) {
            when (code) {
                KeyEvent.KEYCODE_BACK -> {
                    hideMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val focused = currentFocus
                    val pos = focused?.let { grid.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
                    if (pos in 0 until GRID_COLUMNS && focusSelectedTab()) return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // из вкладок вниз — на первый канал списка
                    val focused = currentFocus
                    if (focused != null && tabList.findContainingViewHolder(focused) != null) {
                        if (channelAdapter.itemCount == 0) return true
                        grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: return false
                        return true
                    }
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> {
                    val ch = currentFocus?.let { grid.findContainingViewHolder(it)?.bindingAdapterPosition }
                        ?.let { channelAdapter.items.getOrNull(it) }
                    if (ch != null) {
                        toggleFavorite(ch)
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU -> showMenu()

            // каналы листаются боковыми кнопками, вверх/вниз — дополнительные действия
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP -> move(+1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN -> move(-1)

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_GUIDE -> showGuide(current)

            // «вниз» — к предыдущему просмотренному каналу, как кнопка «↺» на пультах приставок
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                val prevKey = prefs.recent.getOrNull(1)
                val idx = channels.indexOfFirst { it.key == prevKey }
                if (idx >= 0) play(idx) else showInfo(channels[current])
            }

            KeyEvent.KEYCODE_INFO -> showInfo(channels[current])

            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                digits += (code - KeyEvent.KEYCODE_0).toString()
                infoNumber.text = digits
                infoName.text = ""
                infoBox.visibility = View.VISIBLE
                handler.removeCallbacks(applyDigits)
                handler.postDelayed(applyDigits, 1500)
            }

            KeyEvent.KEYCODE_BACK -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2000) finish()
                else Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                lastBack = now
            }

            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    companion object {
        const val REGIONAL = "Региональные"
        const val TAB_FAVORITES = 1
        const val TAB_ALL = 2
        const val GRID_COLUMNS = 3
        const val MAX_SKIP = 10
        const val STALL_MS = 25_000L
        // отсчёт от прихода формата видео, а он известен раньше, чем скачан первый HD-сегмент
        const val FIRST_FRAME_MS = 12_000L
        const val TAG = "TVChannels"
    }
}
