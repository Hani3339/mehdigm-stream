package com.egydead

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.Uqload
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * EgyDead custom file hosts.
 *
 * EgyDead mixes two kinds of sources:
 *
 *  * the rotating `https://<random>.top/iframe/<hash>` player (resolved by
 *    [EgyDead] itself through the Inertia `streams` payload), and
 *  * plain file-host embeds whose domains are not covered by CloudStream's
 *    built-ins (or whose built-ins lag behind the host's current JS loader).
 *
 * Three host families are handled here:
 *
 *  1. StreamWish/StreamHG family (`streamwish.to`, `hgcloud.to`, `vibuxer.com`,
 *     `morencius.com`, `vidhidevip.com`/`callistanise.com`): a
 *     p.a.c.k.e.r-obfuscated jwplayer setup exposing `hls4 || hls3 || hls2`.
 *  2. StreamRuby (`stmruby.com`, `streamruby.net`, `rubyvidhub.com`): the same
 *     packer but with a plain `file: ".../master.m3u8"`.
 *  3. `uqload.vc` and `byseraguci.com`: mirrors of hosts whose built-in
 *     extractors already implement the full handshake, so thin subclasses just
 *     re-declare [mainUrl] and reuse that proven logic.
 */

/**
 * Resolves the packer-based StreamWish/VidHide/StreamRuby players.
 *
 * The embed page is either a p.a.c.k.e.r payload directly ([mainUrl] === real
 * host) or only a JS loader that must be bounced to [bounceHost] using the same
 * file code.
 */
open class EgyDeadPackerExtractor : ExtractorApi() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
    override val requiresReferer = false

    /** Host the loader redirects to (StreamWish family). `null` when not needed. */
    open val bounceHost: String? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = codeOf(url)
        if (code.isBlank()) return

        var pageUrl = url
        var text = fetch(url)
        if (text != null && !text.contains("eval(function") && !bounceHost.isNullOrBlank()) {
            pageUrl = "${bounceHost!!.trimEnd('/')}/e/$code"
            text = fetch(pageUrl)
        }
        if (text.isNullOrBlank()) return

        val decoded = JsUnpacker(text).unpack() ?: text
        val stream = pickStream(decoded, pageUrl) ?: pickStream(text, pageUrl) ?: return

        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = "$pageUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    protected suspend fun fetch(target: String): String? = try {
        app.get(
            target,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            referer = refererHeader(target)
        ).text
    } catch (e: Throwable) {
        Log.i(TAG, "fetch failed $target -> $e")
        null
    }

    /** Last path segment without extension/query, e.g. `/e/abc.html` -> `abc`. */
    private fun codeOf(url: String): String {
        var code = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (code.contains('.')) code = code.substringBefore('.')
        return code
    }

    /**
     * The setup references `hls4 || hls3 || hls2`. `hls4` is a relative
     * `/stream/...` path that only works resolved against the embed host; when it
     * is missing the absolute `hls2` is used. Falls back to a plain `file:` m3u8
     * (StreamRuby).
     */
    private fun pickStream(text: String, pageUrl: String): String? {
        val candidates = linkedMapOf<Int, String>()
        HLS_RE.findAll(text).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { candidates.putIfAbsent(it, m.groupValues[2]) }
        }
        val picked = (listOf(4, 2, 3, 1).firstOrNull { candidates.containsKey(it) }
            ?: candidates.keys.maxOrNull())?.let { candidates[it] }
            ?: FILE_RE.find(text)?.groupValues?.get(1)
        return picked?.let { absolute(it, pageUrl) }
    }

    private fun absolute(link: String, pageUrl: String): String {
        val fixed = link.replace("\\/", "/")
        return when {
            fixed.startsWith("//") -> "https:$fixed"
            fixed.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(pageUrl)?.value
                    ?: mainUrl.trimEnd('/')
                "$origin$fixed"
            }
            else -> fixed
        }
    }

    private fun refererHeader(target: String): String =
        Regex("""^https?://[^/]+""").find(target)?.value?.let { "$it/" } ?: "$mainUrl/"

    protected companion object {
        const val TAG = "EgyDeadExtractor"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

        val HLS_RE = Regex("""hls(\d+)"\s*:\s*"([^"]+)"""")
        val FILE_RE = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
    }
}

// ---------------------------------------------------------------- StreamWish

class EgyDeadStreamWish : EgyDeadPackerExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
    override val bounceHost = "https://vibuxer.com"
}

class EgyDeadStreamWishSite : EgyDeadPackerExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.site"
    override val bounceHost = "https://vibuxer.com"
}

class EgyDeadHgCloud : EgyDeadPackerExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val bounceHost = "https://vibuxer.com"
}

class EgyDeadHgLink : EgyDeadPackerExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://hglink.to"
    override val bounceHost = "https://vibuxer.com"
}

// ------------------------------------------------------------------ EarnVids

class EgyDeadEarnVids : EgyDeadPackerExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://morencius.com"
}

class EgyDeadVidHide : EgyDeadPackerExtractor() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhidevip.com"
}

class EgyDeadVidHideAlt : EgyDeadPackerExtractor() {
    override var name = "VidHide"
    override var mainUrl = "https://callistanise.com"
}

// ---------------------------------------------------------------- StreamRuby

class EgyDeadStreamRubyCom : EgyDeadPackerExtractor() {
    override var name = "StreamRuby"
    override var mainUrl = "https://stmruby.com"
}

class EgyDeadStreamRubyNet : EgyDeadPackerExtractor() {
    override var name = "StreamRuby"
    override var mainUrl = "https://streamruby.net"
}

class EgyDeadStreamRubyVip : EgyDeadPackerExtractor() {
    override var name = "StreamRuby"
    override var mainUrl = "https://rubyvidhub.com"
}

// ---------------------------------------------------------------- Reuse built-ins

/** `uqload.vc` — the driver the EgyDead player returns (built-ins only ship other TLDs). */
class EgyDeadUqload : Uqload() {
    override var mainUrl = "https://uqload.vc"
}

/** `byseraguci.com` — a Byse mirror not listed among the built-in Byse domains. */
class EgyDeadByse : ByseSX() {
    override var mainUrl = "https://byseraguci.com"
}
