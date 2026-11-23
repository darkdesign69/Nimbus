package com.nimbus.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var store: Store
    private lateinit var list: LinearLayout
    private var fg = 0
    private var sub = 0

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val sysDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = store.night == "on" || (store.night == "auto" && sysDark)
        val bg = if (dark) 0xFF121212.toInt() else Color.WHITE
        val bar = if (dark) 0xFF1F2023.toInt() else 0xFFF6F7F9.toInt()
        fg = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
        sub = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(TextView(this).apply {
            text = "Settings"
            textSize = 20f
            setTextColor(fg)
            setBackgroundColor(bar)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        window.statusBarColor = bar

        // ---- General
        section("General")
        choice("Search engine", "engine",
            Engines.all.map { it.id to it.name } + ("custom" to "Custom URL"), "google")
        text("Custom search URL", "customSearch", "Use %s where the query goes, e.g. https://example.com/?q=%s")
        text("Homepage", "homepage", "Leave empty for the built-in start page")
        toggle("Restore tabs on launch", "Reopen the tabs you had open", "restoreTabs", true)

        // ---- Appearance
        section("Appearance")
        choice("Night mode", "night", listOf("auto" to "Follow system", "on" to "Always on", "off" to "Off"), "auto")
        choice("Text size", "textZoom",
            listOf("75" to "75%", "90" to "90%", "100" to "100%", "115" to "115%", "130" to "130%", "150" to "150%"), "100", int = true)
        toggle("Keep screen on", "Prevent the screen from turning off while browsing", "keepScreenOn", false)

        // ---- Privacy & security
        section("Privacy and security")
        toggle("JavaScript", "Allow sites to run JavaScript", "js", true)
        toggle("Cookies", "Allow sites to save and read cookie data", "cookies", true)
        toggle("Third-party cookies", "Allow cookies set by other domains embedded in pages", "thirdPartyCookies", false)
        toggle("Allow pop-ups", "Off: pop-ups not caused by a tap are blocked", "popups", false)
        toggle("Do not track", "Ask sites not to track or sell your data (DNT / GPC headers)", "dnt", true)
        toggle("Save history", "Incognito tabs never save history", "saveHistory", true)
        toggle("Clear data on exit", "Clears history, cookies, cache and site data when the app closes", "clearOnExit", false)

        // ---- Content
        section("Content")
        toggle("Load images", "Turn off to save data", "images", true)
        choice("User agent", "uaPreset",
            listOf("default" to "Android (default)", "desktop" to "Desktop (Chrome, Linux)", "iphone" to "iPhone", "ipad" to "iPad", "custom" to "Custom"), "default")
        text("Custom user agent", "customUa", "Used when User agent is set to Custom")

        // ---- Ad blocking
        section("Ad blocking")
        toggle("Built-in filters", "Blocks requests to common ad and tracker hosts", "adBlock", true)
        text("Custom filters", "customFilters", "One host per line, e.g. ads.example.com (lines starting with # are comments)", multiline = true)

        // ---- Downloads
        section("Downloads")
        toggle("Ask before downloading", "Confirm file name and size first", "askDownload", true)

        // ---- About
        section("About")
        row("Nimbus", "Version " + versionName() + "  \u00B7  independent, open-source browser built on Android System WebView", null)
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun section(title: String) {
        list.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(0xFF2563EB.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(4))
        })
    }

    /** Generic row: title + subtitle; returns the subtitle view so callers can refresh it. */
    private fun row(title: String, subtitle: String, onClick: (() -> Unit)?): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        box.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(fg) })
        val s = TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(sub) }
        box.addView(s)
        if (onClick != null) box.setOnClickListener { onClick() }
        list.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return s
    }

    private fun toggle(title: String, subtitle: String, key: String, def: Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(fg) })
        col.addView(TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(sub) })
        val sw = Switch(this).apply {
            isChecked = store.b(key, def)
            setOnCheckedChangeListener { _, on -> store.put(key, on) }
        }
        box.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(sw)
        box.setOnClickListener { sw.toggle() }
        list.addView(box)
    }

    private fun choice(title: String, key: String, options: List<Pair<String, String>>, def: String, int: Boolean = false) {
        fun current(): String = if (int) store.i(key, def.toInt()).toString() else store.s(key, def)
        fun label(): String = options.firstOrNull { it.first == current() }?.second ?: current()
        lateinit var subtitle: TextView
        subtitle = row(title, label()) {
            val labels = options.map { it.second }.toTypedArray()
            val checked = options.indexOfFirst { it.first == current() }
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(labels, checked) { d, which ->
                    val v = options[which].first
                    if (int) store.put(key, v.toInt()) else store.put(key, v)
                    subtitle.text = label()
                    d.dismiss()
                }.show()
        }
    }

    private fun text(title: String, key: String, hint: String, multiline: Boolean = false) {
        lateinit var subtitle: TextView
        fun shown(): String {
            val v = store.s(key, "")
            return if (v.isBlank()) hint else v.lines().first().take(80) + if (v.contains('\n')) "\u2026" else ""
        }
        subtitle = row(title, shown()) {
            val input = EditText(this).apply {
                setText(store.s(key, ""))
                this.hint = hint
                if (multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 5
                    gravity = Gravity.TOP
                } else {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    isSingleLine = true
                }
            }
            val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
            AlertDialog.Builder(this).setTitle(title).setView(wrap)
                .setPositiveButton("Save") { _, _ ->
                    store.put(key, input.text.toString().trim())
                    subtitle.text = shown()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
