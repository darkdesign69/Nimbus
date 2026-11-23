package com.nimbus.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.print.PrintManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Patterns
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Tab(val web: WebView, val incognito: Boolean) {
    var title: String = ""
    var url: String = ""
    var desktop: Boolean = false
    var progress: Int = 100
}

class MainActivity : Activity() {

    companion object {
        const val HOME = "nimbus://home"
        const val REQ_FILE = 11
        const val REQ_LIST = 12
        const val REQ_SETTINGS = 13
        const val REQ_PERM_GEO = 21
        const val REQ_PERM_MEDIA = 22
        const val REQ_PERM_STORAGE = 23
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val IPHONE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
        const val IPAD_UA = "Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    }

    private lateinit var store: Store
    private val tabs = mutableListOf<Tab>()
    private var curIdx = -1
    private val cur: Tab? get() = tabs.getOrNull(curIdx)

    private lateinit var rootCol: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var fullLayer: FrameLayout
    private lateinit var address: EditText
    private lateinit var reloadBtn: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var tabCount: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private val navButtons = mutableListOf<ImageButton>()

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private var pendingWebPerm: PermissionRequest? = null
    private var pendingDownload: (() -> Unit)? = null
    private var lastBack = 0L
    private lateinit var defaultUa: String

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        defaultUa = WebSettings.getDefaultUserAgent(this)
        AdBlock.setCustom(store.customFilters)
        buildUi()
        applyTheme()

        if (!handleIntent(intent)) restoreOrHome()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?): Boolean {
        if (i == null) return false
        when (i.action) {
            Intent.ACTION_VIEW -> {
                val d = i.dataString
                if (d != null) { newTab(d); return true }
            }
            Intent.ACTION_SEND -> {
                val t = i.getStringExtra(Intent.EXTRA_TEXT)
                if (t != null) { newTab(resolve(t.trim())); return true }
            }
            Intent.ACTION_WEB_SEARCH -> {
                val q = i.getStringExtra("query")
                if (q != null) { newTab(resolve(q)); return true }
            }
        }
        return false
    }

    private fun restoreOrHome() {
        var restored = false
        if (store.restoreTabs) {
            try {
                val a = JSONArray(store.s("openTabs", "[]"))
                for (n in 0 until a.length()) {
                    newTab(a.getString(n), background = true)
                    restored = true
                }
                if (restored) switchTo(store.i("openTabIdx", 0).coerceIn(0, tabs.size - 1))
            } catch (_: Exception) {
            }
        }
        if (!restored) newTab()
    }

    override fun onPause() {
        super.onPause()
        val a = JSONArray()
        var idx = 0
        tabs.forEachIndexed { n, t ->
            if (!t.incognito && t.url.isNotEmpty()) {
                if (t === cur) idx = a.length()
                a.put(t.url)
            }
        }
        store.put("openTabs", a.toString())
        store.put("openTabIdx", idx)
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        AdBlock.setCustom(store.customFilters)
        applyTheme()
        tabs.forEach { configure(it) }
        if (store.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        if (store.clearOnExit && isFinishing) clearBrowsingData(cache = true, cookies = true, history = true, webStorage = true)
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
        tabs.forEach { configure(it) }
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit): ImageButton {
        val b = ImageButton(this)
        b.setImageResource(res)
        b.contentDescription = desc
        b.setBackgroundColor(Color.TRANSPARENT)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val frame = FrameLayout(this)
        rootCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Top bar: address field + reload/stop
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        address = EditText(this).apply {
            hint = "Search or enter address"
            isSingleLine = true
            textSize = 15f
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setPadding(dp(16), 0, dp(16), 0)
        }
        address.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                go(address.text.toString())
                address.clearFocus()
                hideKeyboard()
                true
            } else false
        }
        address.setOnFocusChangeListener { _, focused ->
            if (focused) {
                val t = cur
                address.setText(if (t == null || isStart(t.url)) "" else t.url)
                address.post { address.selectAll() }
            } else updateAddress()
        }
        reloadBtn = iconButton(R.drawable.ic_reload, "Reload") {
            val t = cur ?: return@iconButton
            if (t.progress < 100) t.web.stopLoading() else t.web.reload()
        }
        topBar.addView(address, LinearLayout.LayoutParams(0, dp(40), 1f))
        topBar.addView(reloadBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        content = FrameLayout(this)

        // Find-in-page bar
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        findInput = EditText(this).apply {
            hint = "Find in page"
            isSingleLine = true
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q.isEmpty()) { cur?.web?.clearMatches(); findCount.text = "" } else cur?.web?.findAllAsync(q)
            }
        })
        findCount = TextView(this).apply { textSize = 13f; setPadding(dp(8), 0, dp(8), 0) }
        fun textBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { onClick() }
        }
        findBar.addView(findInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        findBar.addView(findCount)
        findBar.addView(textBtn("Prev") { cur?.web?.findNext(false) })
        findBar.addView(textBtn("Next") { cur?.web?.findNext(true) })
        findBar.addView(textBtn("Done") { closeFind() })

        // Bottom bar: back, forward, menu, tabs, home
        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun slot(v: View) = bottomBar.addView(v, LinearLayout.LayoutParams(0, dp(48), 1f))
        val back = iconButton(R.drawable.ic_back, "Back") { goBack() }
        val fwd = iconButton(R.drawable.ic_forward, "Forward") { cur?.web?.let { if (it.canGoForward()) it.goForward() } }
        val menu = iconButton(R.drawable.ic_menu, "Menu") { showMenu() }
        val home = iconButton(R.drawable.ic_home, "Home") { goHome() }
        tabCount = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { showTabs() }
        }
        navButtons.addAll(listOf(back, fwd, menu, home))
        slot(back); slot(fwd); slot(menu)
        val tabWrap = FrameLayout(this)
        tabWrap.addView(tabCount, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        tabWrap.setOnClickListener { showTabs() }
        slot(tabWrap)
        slot(home)

        rootCol.addView(topBar)
        rootCol.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        rootCol.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootCol.addView(findBar)
        rootCol.addView(bottomBar)

        fullLayer = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        frame.addView(rootCol, FrameLayout.LayoutParams(-1, -1))
        frame.addView(fullLayer, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
    }

    private fun systemDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun isDark() = store.night == "on" || (store.night == "auto" && systemDark())

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val dark = isDark()
        val bar = if (dark) 0xFF1F2023.toInt() else 0xFFF6F7F9.toInt()
        val fg = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
        val field = if (dark) 0xFF35363A.toInt() else 0xFFE8EAED.toInt()
        val incog = cur?.incognito == true
        topBar.setBackgroundColor(if (incog) 0xFF3B2F4F.toInt() else bar)
        bottomBar.setBackgroundColor(bar)
        findBar.setBackgroundColor(bar)
        rootCol.setBackgroundColor(if (dark) 0xFF121212.toInt() else Color.WHITE)
        address.background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(if (incog) 0x33FFFFFF else field) }
        val txt = if (incog) Color.WHITE else fg
        address.setTextColor(txt)
        address.setHintTextColor(if (dark || incog) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt())
        findInput.setTextColor(fg)
        findCount.setTextColor(fg)
        for (n in 0 until findBar.childCount) (findBar.getChildAt(n) as? TextView)?.let { if (it !is EditText) it.setTextColor(fg) }
        (listOf(reloadBtn) + navButtons).forEach { it.setColorFilter(if (incog && it === reloadBtn) Color.WHITE else fg) }
        tabCount.setTextColor(fg)
        tabCount.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(2), fg) }
        window.statusBarColor = if (incog) 0xFF3B2F4F.toInt() else bar
        window.navigationBarColor = bar
        var flags = window.decorView.systemUiVisibility
        flags = if (dark || incog) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.decorView.systemUiVisibility = flags
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ tabs

    private fun tabOf(v: WebView?) = tabs.firstOrNull { it.web === v }

    fun newTab(url: String? = null, incognito: Boolean = false, background: Boolean = false, transport: Message? = null): Tab {
        val w = WebView(this)
        w.visibility = View.GONE
        content.addView(w, FrameLayout.LayoutParams(-1, -1))
        val tab = Tab(w, incognito)
        tabs.add(tab)
        w.webViewClient = client
        w.webChromeClient = chrome
        w.setDownloadListener { u, ua, cd, mime, len -> askDownload(u, ua, cd, mime, len) }
        w.setOnLongClickListener { onLongPress(w) }
        w.setFindListener { a, n, _ -> findCount.text = if (n == 0) "0/0" else "${a + 1}/$n" }
        configure(tab)

        if (transport != null) {
            val t = transport.obj as WebView.WebViewTransport
            t.webView = w
            transport.sendToTarget()
        }
        if (!background) switchTo(tabs.size - 1) else updateAddress()
        if (transport == null) {
            if (url == null) loadHome(tab) else load(tab, resolve(url))
        }
        return tab
    }

    private fun switchTo(i: Int) {
        if (i !in tabs.indices) return
        curIdx = i
        tabs.forEachIndexed { n, t -> t.web.visibility = if (n == i) View.VISIBLE else View.GONE }
        closeFind()
        applyTheme()
        updateAddress()
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        content.removeView(t.web)
        t.web.stopLoading()
        t.web.destroy()
        if (tabs.isEmpty()) {
            curIdx = -1
            newTab()
            return
        }
        if (i < curIdx) curIdx -= 1
        else if (i == curIdx) curIdx = (i - 1).coerceAtLeast(0).coerceAtMost(tabs.size - 1)
        switchTo(curIdx)
    }

    private fun closeAll() {
        while (tabs.isNotEmpty()) {
            val t = tabs.removeAt(tabs.size - 1)
            content.removeView(t.web)
            t.web.destroy()
        }
        curIdx = -1
        newTab()
    }

    private fun showTabs() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val scroll = ScrollView(this).apply { addView(box) }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Tabs")
            .setView(scroll)
            .setPositiveButton("New tab") { _, _ -> newTab() }
            .setNeutralButton("Incognito") { _, _ -> newTab(incognito = true) }
            .setNegativeButton("Close all") { _, _ -> closeAll() }
            .create()
        fun fill() {
            box.removeAllViews()
            tabs.forEachIndexed { i, t ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = TextView(this).apply {
                    text = (if (t.incognito) "\uD83D\uDD76 " else "") + t.title.ifEmpty { if (isStart(t.url) || t.url.isEmpty()) "New tab" else t.url }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 15f
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    if (i == curIdx) setTypeface(null, Typeface.BOLD)
                    setOnClickListener { switchTo(i); dlg.dismiss() }
                }
                val x = iconButton(R.drawable.ic_close, "Close tab") {
                    closeTab(i)
                    fill()
                }
                row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(x, LinearLayout.LayoutParams(dp(44), dp(44)))
                box.addView(row)
            }
        }
        fill()
        dlg.show()
    }

    // ------------------------------------------------------------------ navigation

    private fun isStart(url: String) = url.startsWith(HOME)

    private fun searchUrl(q: String): String {
        val custom = store.customSearch
        if (store.engine == "custom" && custom.contains("%s")) return custom.replace("%s", URLEncoder.encode(q, "UTF-8"))
        return Engines.byId(store.engine).searchUrl(q)
    }

    /** Turn whatever was typed into a loadable URL: a real URL, a bare host, or a search query. */
    fun resolve(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return HOME
        val lower = s.lowercase()
        if (Regex("^[a-z][a-z0-9+.-]*://.*").matches(lower) || lower.startsWith("about:") ||
            lower.startsWith("data:") || lower.startsWith("javascript:")
        ) return s
        if (!s.contains(' ')) {
            if (lower.startsWith("localhost") || Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(s)) return "http://$s"
            if (Patterns.WEB_URL.matcher(s).matches()) return "https://$s"
        }
        return searchUrl(s)
    }

    private fun go(input: String) {
        val u = resolve(input)
        val t = cur
        if (t == null) newTab(u) else load(t, u)
    }

    private fun load(tab: Tab, url: String) {
        if (isStart(url)) { loadStartPage(tab); return }
        val headers = mutableMapOf<String, String>()
        if (store.dnt) { headers["DNT"] = "1"; headers["Sec-GPC"] = "1" }
        tab.web.loadUrl(url, headers)
    }

    private fun loadHome(tab: Tab) {
        val hp = store.homepage
        if (hp.isBlank()) loadStartPage(tab) else load(tab, resolve(hp))
    }

    private fun goHome() {
        val t = cur ?: return newTabHome()
        loadHome(t)
    }

    private fun newTabHome() { newTab() }

    private fun goBack() {
        val t = cur ?: return
        if (t.web.canGoBack()) t.web.goBack()
        else if (tabs.size > 1) closeTab(curIdx)
        else toast("Press back again to exit")
    }

    private fun loadStartPage(tab: Tab) {
        val e = Engines.byId(if (store.engine == "custom") "google" else store.engine)
        val dark = isDark()
        val tiles = StringBuilder()
        for (b in store.bookmarks().take(12)) {
            val host = try { Uri.parse(b.url).host ?: b.url } catch (_: Exception) { b.url }
            val label = b.title.ifEmpty { host }
            tiles.append("<a class='t' href=\"").append(escHtml(b.url)).append("\"><i>")
                .append(escHtml(label.take(1).uppercase())).append("</i><span>")
                .append(escHtml(label.take(18))).append("</span></a>")
        }
        val html = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>New tab</title><style>
:root{color-scheme:${if (dark) "dark" else "light"}}
body{margin:0;font-family:sans-serif;background:${if (dark) "#121212" else "#fff"};color:${if (dark) "#e8eaed" else "#202124"};text-align:center}
h1{margin:18vh 0 18px;font-weight:300;font-size:34px;letter-spacing:1px}
form{margin:0 auto;max-width:520px;padding:0 16px}
input{width:100%;box-sizing:border-box;padding:13px 20px;border-radius:26px;border:1px solid #8884;font-size:16px;background:${if (dark) "#35363a" else "#f1f3f4"};color:inherit;outline:none}
.g{display:flex;flex-wrap:wrap;justify-content:center;max-width:520px;margin:28px auto;padding:0 8px}
.t{width:25%;text-decoration:none;color:inherit;margin:10px 0}
.t i{display:block;margin:0 auto 6px;width:48px;height:48px;line-height:48px;border-radius:14px;background:#2563eb;color:#fff;font-style:normal;font-size:22px}
.t span{font-size:12px;display:block;overflow:hidden;white-space:nowrap}
</style></head><body><h1>Nimbus</h1>
<form action="${e.action}" method="get"><input name="${e.param}" placeholder="Search ${e.name} or type a URL" autocomplete="off"></form>
<div class="g">$tiles</div></body></html>"""
        tab.web.loadDataWithBaseURL(HOME, html, "text/html", "utf-8", HOME)
    }

    private fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun updateAddress() {
        tabCount.text = tabs.size.toString()
        val t = cur ?: return
        if (!address.hasFocus()) {
            address.setText(
                when {
                    isStart(t.url) || t.url.isEmpty() -> ""
                    t.title.isNotEmpty() -> t.title
                    else -> t.url
                }
            )
        }
        progress.visibility = if (t.progress in 1..99) View.VISIBLE else View.GONE
        progress.progress = t.progress
        reloadBtn.setImageResource(if (t.progress in 1..99) R.drawable.ic_close else R.drawable.ic_reload)
    }

    // ------------------------------------------------------------------ WebView configuration

    @Suppress("DEPRECATION", "SetJavaScriptEnabled")
    private fun configure(tab: Tab) {
        val w = tab.web
        val s = w.settings
        s.javaScriptEnabled = store.js
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = store.images
        s.blockNetworkImage = !store.images
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.textZoom = store.textZoom
        s.setGeolocationEnabled(true)
        val ua = uaFor(tab)
        if (s.userAgentString != ua) s.userAgentString = ua
        if (Build.VERSION.SDK_INT >= 29) {
            s.forceDark = if (isDark()) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(store.cookies)
        cm.setAcceptThirdPartyCookies(w, store.thirdPartyCookies)
    }

    private fun uaFor(tab: Tab): String {
        val preset = if (tab.desktop) "desktop" else store.uaPreset
        return when (preset) {
            "desktop" -> DESKTOP_UA
            "iphone" -> IPHONE_UA
            "ipad" -> IPAD_UA
            "custom" -> store.customUa.ifBlank { defaultUa }
            else -> defaultUa
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val host = request?.url?.host
            if (AdBlock.shouldBlock(host, store.adBlock)) {
                AdBlock.blockedCount.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url?.toString() ?: return false
            return handleExternalScheme(u)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            val t = tabOf(view) ?: return
            t.url = url ?: ""
            t.progress = 1
            if (t === cur) updateAddress()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val t = tabOf(view) ?: return
            t.url = url ?: t.url
            t.progress = 100
            if (!t.incognito && store.saveHistory && url != null && !isStart(url) &&
                (url.startsWith("http://") || url.startsWith("https://"))
            ) {
                store.addHistory(Entry(view?.title ?: "", url))
            }
            if (t === cur) updateAddress()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val req = request ?: return
            if (!req.isForMainFrame || view == null) return
            val u = req.url.toString()
            val msg = error?.description?.toString() ?: "Unknown error"
            if (msg.contains("ERR_UNKNOWN_URL_SCHEME")) return
            val dark = isDark()
            val html = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                "<body style='font-family:sans-serif;padding:32px;background:${if (dark) "#121212" else "#fff"};color:${if (dark) "#e8eaed" else "#202124"}'>" +
                "<h2>Can\u2019t open this page</h2><p>${escHtml(msg)}</p><p style='opacity:.6;word-break:break-all'>${escHtml(u)}</p>" +
                "<button onclick='location.reload()' style='padding:10px 20px;font-size:16px'>Retry</button></body></html>"
            view.loadDataWithBaseURL(u, html, "text/html", "utf-8", u)
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Certificate problem")
                .setMessage("${error?.url}\n\nThis site\u2019s security certificate can\u2019t be verified. Anyone on the network may be able to read what you send. Continue anyway?")
                .setPositiveButton("Continue") { _, _ -> handler?.proceed() }
                .setNegativeButton("Go back") { _, _ -> handler?.cancel() }
                .setOnCancelListener { handler?.cancel() }
                .show()
        }
    }

    /** Returns true if the URL was handled outside the WebView (other app) or blocked. */
    private fun handleExternalScheme(url: String): Boolean {
        val scheme = try { Uri.parse(url).scheme?.lowercase() } catch (_: Exception) { null } ?: return false
        if (scheme in setOf("http", "https", "about", "data", "javascript", "file", "blob", "nimbus")) return false
        try {
            val intent = if (scheme == "intent") Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            val fallback = intent.getStringExtra("browser_fallback_url")
            AlertDialog.Builder(this)
                .setTitle("Open another app?")
                .setMessage("This page wants to open an external app.")
                .setPositiveButton("Open") { _, _ ->
                    try {
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        if (fallback != null) go(fallback) else toast("No app can open this link")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Exception) {
            toast("Invalid link")
        }
        return true
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            val t = tabOf(view) ?: return
            t.progress = newProgress
            if (t === cur) updateAddress()
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            val t = tabOf(view) ?: return
            t.title = title ?: ""
            if (t === cur) updateAddress()
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (resultMsg == null) return false
            if (!store.popups && !isUserGesture) {
                toast("Blocked pop-up")
                return false
            }
            newTab(incognito = tabOf(view)?.incognito == true, transport = resultMsg)
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            val i = tabs.indexOfFirst { it.web === window }
            if (i >= 0) closeTab(i)
        }

        override fun onShowFileChooser(webView: WebView?, cb: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = cb
            return try {
                startActivityForResult(params!!.createIntent(), REQ_FILE)
                true
            } catch (_: Exception) {
                fileCb = null
                false
            }
        }

        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            if (view == null || customView != null) { callback?.onCustomViewHidden(); return }
            customView = view
            customCb = callback
            fullLayer.addView(view, FrameLayout.LayoutParams(-1, -1))
            fullLayer.visibility = View.VISIBLE
            rootCol.visibility = View.GONE
            enterImmersive(true)
        }

        override fun onHideCustomView() {
            if (customView == null) return
            fullLayer.removeAllViews()
            fullLayer.visibility = View.GONE
            rootCol.visibility = View.VISIBLE
            customCb?.onCustomViewHidden()
            customView = null
            customCb = null
            enterImmersive(false)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            if (origin == null || callback == null) return
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Location access")
                .setMessage("$origin would like to know your location.")
                .setPositiveButton("Allow") { _, _ ->
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        callback.invoke(origin, true, false)
                    } else {
                        pendingGeo = Pair(origin, callback)
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_PERM_GEO)
                    }
                }
                .setNegativeButton("Block") { _, _ -> callback.invoke(origin, false, false) }
                .setOnCancelListener { callback.invoke(origin, false, false) }
                .show()
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            val needed = mutableListOf<String>()
            val names = mutableListOf<String>()
            for (r in request.resources) {
                when (r) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> { needed.add(Manifest.permission.CAMERA); names.add("camera") }
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> { needed.add(Manifest.permission.RECORD_AUDIO); names.add("microphone") }
                }
            }
            if (needed.isEmpty()) { request.deny(); return }
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Permission request")
                    .setMessage("${request.origin} would like to use your ${names.joinToString(" and ")}.")
                    .setPositiveButton("Allow") { _, _ ->
                        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                        if (missing.isEmpty()) request.grant(request.resources)
                        else {
                            pendingWebPerm = request
                            requestPermissions(missing.toTypedArray(), REQ_PERM_MEDIA)
                        }
                    }
                    .setNegativeButton("Block") { _, _ -> request.deny() }
                    .setOnCancelListener { request.deny() }
                    .show()
            }
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            AlertDialog.Builder(this@MainActivity).setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.cancel() }.show()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            AlertDialog.Builder(this@MainActivity).setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }.show()
            return true
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            val input = EditText(this@MainActivity).apply { setText(defaultValue ?: "") }
            AlertDialog.Builder(this@MainActivity).setMessage(message).setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }.show()
            return true
        }
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else View.SYSTEM_UI_FLAG_VISIBLE
        if (!on) applyTheme()
    }

    // ------------------------------------------------------------------ context menu (long press)

    private fun onLongPress(w: WebView): Boolean {
        val hit = w.hitTestResult
        if (hit.type == WebView.HitTestResult.UNKNOWN_TYPE || hit.type == WebView.HitTestResult.EDIT_TEXT_TYPE ||
            hit.type == WebView.HitTestResult.PHONE_TYPE
        ) return false
        val handler = Handler(Looper.getMainLooper()) { m ->
            var link = m.data?.getString("url")
            var img = m.data?.getString("src")
            if (link.isNullOrEmpty() && img.isNullOrEmpty()) {
                if (hit.type == WebView.HitTestResult.IMAGE_TYPE) img = hit.extra
                else if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) link = hit.extra
            }
            if (!link.isNullOrEmpty() || !img.isNullOrEmpty()) showContextMenu(link, img, tabOf(w)?.incognito == true)
            true
        }
        w.requestFocusNodeHref(handler.obtainMessage())
        return true
    }

    private fun showContextMenu(link: String?, img: String?, incognito: Boolean) {
        val labels = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        if (!link.isNullOrEmpty()) {
            add("Open in new tab") { newTab(link, incognito = incognito) }
            add("Open in background") { newTab(link, incognito = incognito, background = true); toast("Opened in background") }
            add("Copy link") { copy(link) }
            add("Share link") { share(link) }
            add("Download link") { askDownload(link, defaultUa, null, null, 0L) }
            add("Block link\u2019s domain") { blockHostOf(link) }
        }
        if (!img.isNullOrEmpty()) {
            add("View image") { newTab(img, incognito = incognito) }
            add("Save image") { askDownload(img, defaultUa, null, null, 0L) }
            add("Copy image address") { copy(img) }
        }
        AlertDialog.Builder(this).setItems(labels.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun blockHostOf(url: String) {
        val host = try { Uri.parse(url).host } catch (_: Exception) { null } ?: return
        val l = store.customFilters
        store.put("customFilters", if (l.isBlank()) host else "$l\n$host")
        AdBlock.setCustom(store.customFilters)
        toast("Blocked $host")
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", text))
        toast("Copied")
    }

    private fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(i, "Share"))
    }

    // ------------------------------------------------------------------ downloads

    private fun askDownload(url: String, ua: String?, cd: String?, mime: String?, len: Long) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("This kind of download isn\u2019t supported yet")
            return
        }
        val name = URLUtil.guessFileName(url, cd, mime)
        val size = if (len > 0) android.text.format.Formatter.formatFileSize(this, len) else "unknown size"
        val start = {
            if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownload = { enqueue(url, ua, mime, name) }
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM_STORAGE)
            } else enqueue(url, ua, mime, name)
        }
        if (!store.askDownload) { start(); return }
        AlertDialog.Builder(this)
            .setTitle("Download file?")
            .setMessage("$name\n$size")
            .setPositiveButton("Download") { _, _ -> start() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enqueue(url: String, ua: String?, mime: String?, name: String) {
        try {
            val r = DownloadManager.Request(Uri.parse(url))
            CookieManager.getInstance().getCookie(url)?.let { r.addRequestHeader("Cookie", it) }
            if (ua != null) r.addRequestHeader("User-Agent", ua)
            if (!mime.isNullOrEmpty()) r.setMimeType(mime)
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        val t = cur
        val url = t?.url ?: ""
        val real = url.startsWith("http")
        val labels = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels.add(l); acts.add(a) }
        fun check(on: Boolean) = if (on) "\u2713 " else ""

        add("New tab") { newTab() }
        add("Incognito tab") { newTab(incognito = true) }
        add("Bookmarks") { startActivityForResult(Intent(this, ListActivity::class.java).putExtra("mode", "bookmarks"), REQ_LIST) }
        add("History") { startActivityForResult(Intent(this, ListActivity::class.java).putExtra("mode", "history"), REQ_LIST) }
        add("Downloads") { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        if (real) {
            val marked = store.isBookmarked(url)
            add(if (marked) "Remove bookmark" else "Add bookmark") {
                if (marked) { store.removeBookmark(url); toast("Bookmark removed") }
                else { store.addBookmark(Entry(t!!.title, url)); toast("Bookmark added") }
            }
            add("Find in page") { openFind() }
            add(check(t?.desktop == true) + "Desktop site") { t?.let { it.desktop = !it.desktop; configure(it); it.web.reload() } }
            add("Share") { share(url) }
            add("Copy link") { copy(url) }
            add("View source") { viewSource(url) }
            add("Page info") { pageInfo(t!!) }
            add("Print / Save as PDF") { printPage(t!!) }
        }
        add(check(isDark()) + "Night mode") {
            store.put("night", if (isDark()) "off" else "on")
            applyTheme()
            tabs.forEach { configure(it) }
        }
        add("Clear data") { clearDataDialog() }
        add("Settings") { startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS) }
        add("Exit") { finishAndRemoveTask() }
        AlertDialog.Builder(this).setItems(labels.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, 0)
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE
        findInput.setText("")
        findCount.text = ""
        cur?.web?.clearMatches()
        hideKeyboard()
    }

    private fun viewSource(url: String) {
        val cookie = CookieManager.getInstance().getCookie(url)
        val ua = cur?.let { uaFor(it) } ?: defaultUa
        Thread {
            val text = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", ua)
                if (cookie != null) c.setRequestProperty("Cookie", cookie)
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.inputStream.bufferedReader().use { it.readText() }.take(300_000)
            } catch (e: Exception) {
                "Could not fetch source: ${e.message}"
            }
            runOnUiThread {
                val tv = TextView(this).apply {
                    this.text = text
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextIsSelectable(true)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                val sv = ScrollView(this).apply { addView(tv) }
                AlertDialog.Builder(this).setTitle("Source").setView(sv).setPositiveButton("Close", null).show()
            }
        }.start()
    }

    private fun pageInfo(t: Tab) {
        val cert = t.web.certificate
        val secure = t.url.startsWith("https://")
        val sb = StringBuilder()
        sb.append("Title: ").append(t.title).append("\nURL: ").append(t.url).append("\n\n")
        sb.append(if (secure) "Connection: HTTPS" else "Connection: not secure")
        if (cert != null) {
            sb.append("\nIssued to: ").append(cert.issuedTo.cName)
            sb.append("\nIssued by: ").append(cert.issuedBy.cName)
            sb.append("\nValid until: ").append(cert.validNotAfterDate)
        }
        sb.append("\n\nAds blocked this session: ").append(AdBlock.blockedCount.get())
        AlertDialog.Builder(this).setTitle("Page info").setMessage(sb.toString()).setPositiveButton("Close", null).show()
    }

    private fun printPage(t: Tab) {
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(t.title.ifEmpty { "page" }, t.web.createPrintDocumentAdapter(t.title.ifEmpty { "page" }), null)
    }

    private fun clearDataDialog() {
        val items = arrayOf("Browsing history", "Cookies (login status)", "Cache", "Website data (local storage)")
        val on = booleanArrayOf(true, true, true, false)
        AlertDialog.Builder(this)
            .setTitle("Clear data")
            .setMultiChoiceItems(items, on) { _, i, c -> on[i] = c }
            .setPositiveButton("Clear") { _, _ ->
                clearBrowsingData(cache = on[2], cookies = on[1], history = on[0], webStorage = on[3])
                toast("Data cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearBrowsingData(cache: Boolean, cookies: Boolean, history: Boolean, webStorage: Boolean) {
        if (history) store.clearHistory()
        if (cookies) CookieManager.getInstance().removeAllCookies(null)
        if (cache) tabs.firstOrNull()?.web?.clearCache(true)
        if (webStorage) WebStorage.getInstance().deleteAllData()
    }

    // ------------------------------------------------------------------ results, permissions, keys

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCb = null
            }
            REQ_LIST -> {
                val u = data?.getStringExtra("url")
                if (resultCode == RESULT_OK && u != null) newTab(u, incognito = cur?.incognito == true)
            }
            REQ_SETTINGS -> {
                AdBlock.setCustom(store.customFilters)
                applyTheme()
                tabs.forEach { configure(it) }
                // Refresh the built-in start page so engine / theme changes show immediately.
                cur?.let { if (isStart(it.url)) loadStartPage(it) }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_PERM_GEO -> {
                pendingGeo?.let { it.second.invoke(it.first, ok, false) }
                pendingGeo = null
            }
            REQ_PERM_MEDIA -> {
                pendingWebPerm?.let { if (ok) it.grant(it.resources) else it.deny() }
                pendingWebPerm = null
            }
            REQ_PERM_STORAGE -> {
                if (ok) pendingDownload?.invoke() else toast("Storage permission is needed to download")
                pendingDownload = null
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) { chrome.onHideCustomView(); return }
        if (findBar.visibility == View.VISIBLE) { closeFind(); return }
        val t = cur
        if (t != null && t.web.canGoBack()) { t.web.goBack(); return }
        if (tabs.size > 1) { closeTab(curIdx); return }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) finishAndRemoveTask() else {
            lastBack = now
            toast("Press back again to exit")
        }
    }
}
