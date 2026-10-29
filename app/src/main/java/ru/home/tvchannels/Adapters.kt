package ru.home.tvchannels

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LogoCache(private val assets: AssetManager, private val downloaded: java.io.File) {
    private val cache = LruCache<String, Bitmap>(80)
    private val missing = HashSet<String>()

    fun get(name: String?): Bitmap? {
        if (name == null || name in missing) return null
        cache.get(name)?.let { return it }
        // Сначала ищет логотип в APK, затем в каталоге логотипов с GitHub.
        val bitmap = runCatching {
            assets.open("logos/$name.png").use(BitmapFactory::decodeStream)
        }.getOrNull() ?: java.io.File(downloaded, "$name.png").takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.path) }
        if (bitmap == null) missing += name else cache.put(name, bitmap)
        return bitmap
    }
}

class ChannelAdapter(
    private val logos: LogoCache,
    private val onPick: (Channel) -> Unit,
    private val onLongPick: (Channel) -> Unit,
    private val onNeedEpg: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    var items: List<Channel> = emptyList()
        private set
    var playingNumber = 0
    var favorites: Set<String> = emptySet()

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val placeholder: TextView = view.findViewById(R.id.logo_placeholder)
        val name: TextView = view.findViewById(R.id.name)
        val number: TextView = view.findViewById(R.id.number)
        val star: View = view.findViewById(R.id.star)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ch = items[position]
        val bitmap = logos.get(ch.logo)
        holder.logo.setImageBitmap(bitmap)
        holder.logo.visibility = if (bitmap != null) View.VISIBLE else View.INVISIBLE
        holder.placeholder.visibility = if (bitmap != null) View.GONE else View.VISIBLE
        holder.placeholder.text = ch.name.take(1)
        holder.name.text = ch.name
        // RecyclerView привязывает только видимые и соседние карточки.
        // Поэтому телепрограмму запрашивают только для них.
        val programmes = Epg.cached(ch)
        if (programmes == null && Epg.has(ch)) onNeedEpg(ch)
        val now = programmes?.let { Epg.nowAndNext(it).first }
        holder.number.text = if (now != null) "${ch.number} · ${now.title}" else ch.number.toString()
        // Бегущая строка работает только у карточки под фокусом: так процессор не перегружен.
        holder.number.isSelected = holder.itemView.hasFocus()
        holder.itemView.setOnFocusChangeListener { _, focused -> holder.number.isSelected = focused }
        holder.star.visibility = if (ch.key in favorites) View.VISIBLE else View.GONE
        holder.itemView.isActivated = ch.number == playingNumber
        holder.itemView.setOnClickListener { onPick(ch) }
        holder.itemView.setOnLongClickListener { onLongPick(ch); true }
    }

    override fun getItemCount() = items.size
}

/** Вкладка меню: группа каналов или кнопка-действие (настройки). */
data class Tab(val title: String, val channels: () -> List<Channel>, val action: (() -> Unit)? = null)

class TabAdapter(
    private val tabs: List<Tab>,
    private val onSelect: (Int) -> Unit,
) : RecyclerView.Adapter<TabAdapter.Holder>() {

    private var recycler: RecyclerView? = null

    // Подсветку меняют прямо на видимых элементах. Вызов notifyItemChanged
    // во время прокрутки к следующей вкладке ломает поиск фокуса.
    var selected = 0
        set(value) {
            field = value
            val rv = recycler ?: return
            for (i in 0 until rv.childCount) {
                val holder = rv.getChildViewHolder(rv.getChildAt(i)) as Holder
                holder.text.isActivated = holder.bindingAdapterPosition == value
            }
        }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false) as TextView
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tab = tabs[position]
        holder.text.text = tab.title
        holder.text.isActivated = position == selected
        // Как в Триколоре: фокус на вкладке сразу включает её категорию.
        // Вкладку-действие включает только кнопка OK.
        holder.text.setOnFocusChangeListener { _, hasFocus ->
            val pos = holder.bindingAdapterPosition
            if (hasFocus && tab.action == null && pos != RecyclerView.NO_POSITION && pos != selected) {
                onSelect(pos)
            }
        }
        holder.text.setOnClickListener {
            tab.action?.invoke() ?: onSelect(holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount() = tabs.size
}

/** Телепрограмма канала: заголовки дней и передачи. */
class GuideAdapter(private val onPick: () -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row
    private data class Day(val label: String) : Row
    private data class Item(val p: Programme) : Row

    private var rows: List<Row> = emptyList()
    private var now = 0L
    private val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val dayFormat = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale("ru"))

    /** Возвращает позицию текущей передачи (или ближайшей будущей). */
    fun submit(list: List<Programme>, now: Long): Int {
        this.now = now
        val out = mutableListOf<Row>()
        var lastDay = ""
        for (p in list) {
            val day = dayFormat.format(java.util.Date(p.start)).replaceFirstChar { it.uppercase() }
            if (day != lastDay) {
                out += Day(day)
                lastDay = day
            }
            out += Item(p)
        }
        rows = out
        notifyDataSetChanged()
        return rows.indexOfFirst { it is Item && it.p.end > now }.coerceAtLeast(0)
    }

    override fun getItemViewType(position: Int) = if (rows[position] is Day) 0 else 1

    private class DayHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.prog_time)
        val title: TextView = view.findViewById(R.id.prog_title)
        val badge: View = view.findViewById(R.id.prog_badge)
        val progress: android.widget.ProgressBar = view.findViewById(R.id.prog_progress)
        val desc: TextView = view.findViewById(R.id.prog_desc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) DayHolder(inflater.inflate(R.layout.item_day, parent, false) as TextView)
        else ItemHolder(inflater.inflate(R.layout.item_programme, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Day -> (holder as DayHolder).text.text = row.label
            is Item -> {
                val h = holder as ItemHolder
                val p = row.p
                val live = p.isNow(now)
                h.time.text = time.format(java.util.Date(p.start))
                h.title.text = p.title
                // прошедшее приглушаем, идущее сейчас — с меткой и прогрессом
                h.itemView.alpha = if (p.end <= now) 0.5f else 1f
                h.badge.visibility = if (live) View.VISIBLE else View.GONE
                h.progress.visibility = if (live) View.VISIBLE else View.GONE
                h.progress.progress = p.progress(now)
                h.desc.text = p.desc
                h.desc.visibility = View.GONE
                // описание раскрываем у передачи под фокусом
                h.itemView.setOnFocusChangeListener { v, focused ->
                    h.desc.visibility = if (focused && p.desc.isNotEmpty()) View.VISIBLE else View.GONE
                    h.title.maxLines = if (focused) 2 else 1
                    // строка выросла из-за описания — после раскладки ставим её точно по центру
                    if (focused) v.post {
                        val rv = v.parent as? RecyclerView ?: return@post
                        (rv.layoutManager as? CenteringLayoutManager)?.center(rv, v)
                    }
                }
                h.itemView.setOnClickListener { onPick() }
            }
        }
    }

    override fun getItemCount() = rows.size
}

/**
 * Держит элемент под фокусом в центре списка и прокручивает весь список.
 * Сверху и снизу добавляет отступ в половину высоты. Так в центр
 * встают первая и последняя строки.
 */
class CenteringLayoutManager(context: android.content.Context) : LinearLayoutManager(context) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: android.graphics.Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean = center(parent, child)

    // прокрутка мгновенная: плавная во время смены фокуса отцепляет строку с фокусом
    fun center(parent: RecyclerView, child: View): Boolean {
        val dy = (child.top + child.bottom) / 2 - parent.height / 2
        if (dy == 0) return false
        parent.scrollBy(0, dy)
        return true
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        view.clipToPadding = false
        view.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                val half = (bottom - top) / 2
                v.setPadding(v.paddingLeft, half, v.paddingRight, half)
            }
        }
    }
}
