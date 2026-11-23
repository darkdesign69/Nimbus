package com.nimbus.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Entry(val title: String, val url: String, val time: Long = System.currentTimeMillis())

data class Engine(val id: String, val name: String, val action: String, val param: String) {
    fun searchUrl(q: String): String = action + "?" + param + "=" + URLEncoder.encode(q, "UTF-8")
}

object Engines {
    val all = listOf(
        Engine("google", "Google", "https://www.google.com/search", "q"),
        Engine("bing", "Bing", "https://www.bing.com/search", "q"),
        Engine("ddg", "DuckDuckGo", "https://duckduckgo.com/", "q"),
        Engine("baidu", "Baidu", "https://www.baidu.com/s", "wd"),
        Engine("yandex", "Yandex", "https://yandex.com/search/", "text"),
        Engine("brave", "Brave", "https://search.brave.com/search", "q"),
        Engine("ecosia", "Ecosia", "https://www.ecosia.org/search", "q")
    )

    fun byId(id: String): Engine = all.firstOrNull { it.id == id } ?: all[0]
}

/** All persistent state: settings, bookmarks, history. Backed by SharedPreferences. */
class Store(ctx: Context) {
    val sp = ctx.getSharedPreferences("nimbus", Context.MODE_PRIVATE)

    fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun s(k: String, d: String) = sp.getString(k, d) ?: d
    fun i(k: String, d: Int) = sp.getInt(k, d)

    fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) {
            is Boolean -> e.putBoolean(k, v)
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
        }
        e.apply()
    }

    // ---- General
    val engine: String get() = s("engine", "google")
    val customSearch: String get() = s("customSearch", "")
    val homepage: String get() = s("homepage", "")          // empty = built-in start page
    val restoreTabs: Boolean get() = b("restoreTabs", true)

    // ---- Appearance
    val night: String get() = s("night", "auto")            // off | on | auto
    val textZoom: Int get() = i("textZoom", 100)
    val keepScreenOn: Boolean get() = b("keepScreenOn", false)

    // ---- Privacy & security
    val js: Boolean get() = b("js", true)
    val cookies: Boolean get() = b("cookies", true)
    val thirdPartyCookies: Boolean get() = b("thirdPartyCookies", false)
    val popups: Boolean get() = b("popups", false)           // allow pop-ups without a gesture
    val dnt: Boolean get() = b("dnt", true)
    val saveHistory: Boolean get() = b("saveHistory", true)
    val clearOnExit: Boolean get() = b("clearOnExit", false)

    // ---- Content
    val images: Boolean get() = b("images", true)
    val uaPreset: String get() = s("uaPreset", "default")   // default | desktop | iphone | ipad | custom
    val customUa: String get() = s("customUa", "")

    // ---- Ad blocking
    val adBlock: Boolean get() = b("adBlock", true)
    val customFilters: String get() = s("customFilters", "")

    // ---- Downloads
    val askDownload: Boolean get() = b("askDownload", true)

    // ---- Lists
    private fun load(key: String): MutableList<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val a = JSONArray(s(key, "[]"))
            for (n in 0 until a.length()) {
                val o = a.getJSONObject(n)
                out.add(Entry(o.optString("t"), o.getString("u"), o.optLong("d")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun save(key: String, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        put(key, a.toString())
    }

    fun bookmarks(): MutableList<Entry> = load("bookmarks")
    fun isBookmarked(url: String) = bookmarks().any { it.url == url }
    fun addBookmark(e: Entry) {
        val l = bookmarks()
        if (l.none { it.url == e.url }) {
            l.add(0, e)
            save("bookmarks", l)
        }
    }

    fun removeBookmark(url: String) {
        val l = bookmarks()
        l.removeAll { it.url == url }
        save("bookmarks", l)
    }

    fun history(): MutableList<Entry> = load("history")
    fun addHistory(e: Entry) {
        val l = history()
        l.removeAll { it.url == e.url }
        l.add(0, e)
        while (l.size > 1000) l.removeAt(l.size - 1)
        save("history", l)
    }

    fun removeHistory(url: String) {
        val l = history()
        l.removeAll { it.url == url }
        save("history", l)
    }

    fun clearHistory() = put("history", "[]")

    // ---- Bookmark import / export (Netscape bookmark HTML, the de-facto interchange format)
    fun exportBookmarksHtml(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        for (e in bookmarks()) {
            sb.append("    <DT><A HREF=\"").append(esc(e.url)).append("\">")
                .append(esc(e.title.ifEmpty { e.url })).append("</A>\n")
        }
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    fun importBookmarksHtml(text: String): Int {
        val re = Regex("<A[^>]*?HREF=\"([^\"]*)\"[^>]*>(.*?)</A>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        var n = 0
        val have = bookmarks().map { it.url }.toMutableSet()
        val l = bookmarks()
        for (m in re.findAll(text)) {
            val url = unesc(m.groupValues[1])
            if (!url.startsWith("http") || !have.add(url)) continue
            l.add(Entry(unesc(m.groupValues[2]).trim(), url))
            n++
        }
        if (n > 0) save("bookmarks", l)
        return n
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
