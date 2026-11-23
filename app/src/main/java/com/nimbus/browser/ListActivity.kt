package com.nimbus.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** Shows bookmarks or history. Tap = open in the browser, long-press = delete / copy. */
class ListActivity : Activity() {
    private lateinit var store: Store
    private lateinit var mode: String
    private var items = mutableListOf<Entry>()
    private lateinit var adapter: ArrayAdapter<Entry>
    private var dark = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        mode = intent.getStringExtra("mode") ?: "bookmarks"
        val night = store.night
        dark = night == "on" || (night == "auto" &&
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        val bg = if (dark) 0xFF121212.toInt() else Color.WHITE
        val fg = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
        val bar = if (dark) 0xFF1F2023.toInt() else 0xFFF6F7F9.toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bar)
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }
        val title = TextView(this).apply {
            text = if (mode == "history") "History" else "Bookmarks"
            textSize = 20f
            setTextColor(fg)
        }
        fun action(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(fg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (mode == "history") {
            header.addView(action("Clear all") { confirmClear() })
        } else {
            header.addView(action("Import") { startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1) })
            header.addView(action("Export") { startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/html").putExtra(Intent.EXTRA_TITLE, "bookmarks.html"), 2) })
        }
        root.addView(header)

        val empty = TextView(this).apply {
            text = if (mode == "history") "No history yet" else "No bookmarks yet"
            gravity = Gravity.CENTER
            setTextColor(fg)
            alpha = 0.6f
        }
        val lv = ListView(this)
        lv.emptyView = empty
        adapter = object : ArrayAdapter<Entry>(this, 0, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val e = getItem(position)!!
                val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    addView(TextView(context).apply { id = 1; textSize = 16f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(TextView(context).apply { id = 2; textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; alpha = 0.6f })
                }
                row.findViewById<TextView>(1).apply { text = e.title.ifEmpty { e.url }; setTextColor(fg) }
                row.findViewById<TextView>(2).apply {
                    text = if (mode == "history") e.url + "  \u00B7  " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.time)) else e.url
                    setTextColor(fg)
                }
                return row
            }
        }
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            setResult(RESULT_OK, Intent().putExtra("url", items[pos].url))
            finish()
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            val e = items[pos]
            AlertDialog.Builder(this).setItems(arrayOf("Delete", "Copy link")) { _, which ->
                if (which == 0) {
                    if (mode == "history") store.removeHistory(e.url) else store.removeBookmark(e.url)
                    refresh()
                } else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", e.url))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
            }.show()
            true
        }
        root.addView(FrameLayoutWrap.wrap(this, lv, empty), LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        window.statusBarColor = bar
        refresh()
    }

    private fun refresh() {
        items.clear()
        items.addAll(if (mode == "history") store.history() else store.bookmarks())
        adapter.notifyDataSetChanged()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setMessage("Delete all browsing history?")
            .setPositiveButton("Delete") { _, _ -> store.clearHistory(); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            if (requestCode == 1) {
                val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val n = store.importBookmarksHtml(text)
                Toast.makeText(this, "$n bookmarks imported", Toast.LENGTH_SHORT).show()
                refresh()
            } else if (requestCode == 2) {
                contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(store.exportBookmarksHtml()) }
                Toast.makeText(this, "Bookmarks exported", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

/** Tiny helper: a FrameLayout holding the list with its empty-view overlay. */
object FrameLayoutWrap {
    fun wrap(ctx: Context, list: ListView, empty: View): android.widget.FrameLayout {
        val f = android.widget.FrameLayout(ctx)
        f.addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))
        f.addView(empty, android.widget.FrameLayout.LayoutParams(-1, -1))
        return f
    }
}
