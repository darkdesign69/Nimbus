package com.nimbus.browser

import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal host-based ad blocker. The built-in list is intentionally small and original;
 * users can add their own hosts (one per line) in Settings > Ad blocking > Custom filters.
 * Lines starting with '#' or '!' are comments; "||example.com^" Adblock-style host rules are accepted.
 */
object AdBlock {
    val blockedCount = AtomicInteger(0)

    private val builtin = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "2mdn.net", "adnxs.com", "adsrvr.org", "adform.net", "advertising.com", "amazon-adsystem.com",
        "criteo.com", "criteo.net", "moatads.com", "openx.net", "pubmatic.com", "rubiconproject.com",
        "smartadserver.com", "taboola.com", "outbrain.com", "mgid.com", "revcontent.com",
        "popads.net", "propellerads.com", "exoclick.com", "juicyads.com", "trafficjunky.com",
        "scorecardresearch.com", "quantserve.com", "adcolony.com", "applovin.com", "unityads.unity3d.com"
    )

    @Volatile
    private var custom: Set<String> = emptySet()

    fun setCustom(text: String) {
        custom = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removePrefix("||").removeSuffix("^").removePrefix("www.").lowercase() }
            .toSet()
    }

    fun shouldBlock(host: String?, builtinEnabled: Boolean): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        if (builtinEnabled && matches(h, builtin)) return true
        return custom.isNotEmpty() && matches(h, custom)
    }

    /** True if [h] equals a listed domain or is a subdomain of one (a.b.example.com -> example.com). */
    private fun matches(h: String, set: Set<String>): Boolean {
        var d = h
        while (d.isNotEmpty()) {
            if (set.contains(d)) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
        return false
    }
}
