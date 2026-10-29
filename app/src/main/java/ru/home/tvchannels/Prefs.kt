package ru.home.tvchannels

import android.content.Context

/** Хранит настройки, «Недавние» и «Избранное». */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tv", Context.MODE_PRIVATE)

    var startLast: Boolean
        get() = sp.getBoolean("start_last", true)
        set(v) = sp.edit().putBoolean("start_last", v).apply()

    var lastChannel: String?
        get() = sp.getString("last_channel", null)
        set(v) = sp.edit().putString("last_channel", v).apply()

    /** 0 — авто, иначе максимальная высота видео */
    var maxHeight: Int
        get() = sp.getInt("max_height", 0)
        set(v) = sp.edit().putInt("max_height", v).apply()

    var infoSeconds: Int
        get() = sp.getInt("info_seconds", 4)
        set(v) = sp.edit().putInt("info_seconds", v).apply()

    var showRegional: Boolean
        get() = sp.getBoolean("show_regional", true)
        set(v) = sp.edit().putBoolean("show_regional", v).apply()

    var skipDead: Boolean
        get() = sp.getBoolean("skip_dead", true)
        set(v) = sp.edit().putBoolean("skip_dead", v).apply()

    /** Значение 0 выключает таймер сна. */
    var sleepMinutes: Int
        get() = sp.getInt("sleep_minutes", 0)
        set(v) = sp.edit().putInt("sleep_minutes", v).apply()

    var recent: List<String>
        get() = sp.getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("recent", v.joinToString("\n")).apply()

    var favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet())!!
        set(v) = sp.edit().putStringSet("favorites", v).apply()

    fun pushRecent(key: String) {
        recent = (listOf(key) + recent.filter { it != key }).take(RECENT_LIMIT)
    }

    fun toggleFavorite(key: String): Boolean {
        val set = favorites.toMutableSet()
        val added = if (key in set) !set.remove(key) else set.add(key)
        favorites = set
        return added
    }

    companion object {
        const val RECENT_LIMIT = 30
    }
}
