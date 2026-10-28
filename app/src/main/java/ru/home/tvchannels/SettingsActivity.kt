package ru.home.tvchannels

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Настройки: каждая строка — название и значение, OK перебирает значения по кругу.
 */
class SettingsActivity : Activity() {

    private class Row(
        val title: String,
        val value: () -> String,
        val onClick: () -> Unit,
    )

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        val channels = Playlist.load(this)
        findViewById<TextView>(R.id.settings_footer).text = getString(
            R.string.settings_footer,
            packageManager.getPackageInfo(packageName, 0).versionName,
            channels.size,
            channels.count { it.urls.size > 1 },
        )

        val rows = listOf(
            Row(getString(R.string.set_start), {
                getString(if (prefs.startLast) R.string.start_last else R.string.start_first)
            }) { prefs.startLast = !prefs.startLast },

            Row(getString(R.string.set_quality), {
                if (prefs.maxHeight == 0) getString(R.string.quality_auto) else "до ${prefs.maxHeight}p"
            }) { prefs.maxHeight = next(QUALITY, prefs.maxHeight) },

            Row(getString(R.string.set_skip_dead), { onOff(prefs.skipDead) }) {
                prefs.skipDead = !prefs.skipDead
            },

            Row(getString(R.string.set_regional), { onOff(prefs.showRegional) }) {
                prefs.showRegional = !prefs.showRegional
            },

            Row(getString(R.string.set_info), { "${prefs.infoSeconds} с" }) {
                prefs.infoSeconds = next(INFO_SECONDS, prefs.infoSeconds)
            },

            Row(getString(R.string.set_sleep), {
                if (prefs.sleepMinutes == 0) getString(R.string.off) else "${prefs.sleepMinutes} мин"
            }) { prefs.sleepMinutes = next(SLEEP, prefs.sleepMinutes) },

            Row(getString(R.string.set_clear_recent), { "${prefs.recent.size}" }) {
                prefs.recent = emptyList()
                toast(R.string.cleared)
            },

            Row(getString(R.string.set_clear_favorites), { "${prefs.favorites.size}" }) {
                prefs.favorites = emptySet()
                toast(R.string.cleared)
            },
        )

        val list = findViewById<LinearLayout>(R.id.settings_list)
        val inflater = LayoutInflater.from(this)
        rows.forEach { row ->
            val view = inflater.inflate(R.layout.item_setting, list, false)
            val title = view.findViewById<TextView>(R.id.setting_title)
            val value = view.findViewById<TextView>(R.id.setting_value)
            title.text = row.title
            value.text = row.value()
            view.setOnClickListener {
                row.onClick()
                value.text = row.value()
            }
            list.addView(view)
        }
        list.getChildAt(0)?.requestFocus()
    }

    private fun onOff(v: Boolean) = getString(if (v) R.string.on else R.string.off)

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun next(options: IntArray, current: Int): Int {
        val i = options.indexOf(current)
        return options[(i + 1) % options.size]
    }

    companion object {
        private val QUALITY = intArrayOf(0, 1080, 720, 480)
        private val INFO_SECONDS = intArrayOf(4, 6, 8, 2)
        private val SLEEP = intArrayOf(0, 30, 60, 90, 120)
    }
}
