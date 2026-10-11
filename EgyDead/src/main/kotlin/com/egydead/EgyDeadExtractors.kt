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
 * EgyDead file hosts whose domains are not covered by any built-in extractor.
 *
 * The cloudstream SDK exposes [Uqload] and [ByseSX] as `open` classes; the runtime
 * matches extractors purely by [mainUrl] prefix, so declaring subclasses with the
 * rotating/mirror domains reuses the proven built-in parsing without any duplication.
 */

/** `uqload.vc` — the driver the EgyDead player returns (built-ins only ship .com/.cx/.bz). */
class EgyDeadUqload : Uqload() {
    override var mainUrl = "https://uqload.vc"
}

/** `byseraguci.com` — a Byse mirror not listed among the built-in Byse domains. */
class EgyDeadByse : ByseSX() {
    override var mainUrl = "https://byseraguci.com"
}

/**
 * StreamHG / StreamWish file host used by EgyDead.
 *
 * The embed page (`streamwish.to` / `hgcloud.to`) is only a JS loader; the real player
 * lives on `vibuxer.com/e/<code>` and ships a p.a.c.k.e.r-obfuscated jwplayer setup:
 *
 *   var links={"hls4":"…","hls2":"…","hls3":"…"};
 *   …sources:[{file:links.hls4||links.hls3||links.hls2,type:"hls"}]…
 *
 * The built-in StreamWish extractor looks for `sources:` directly on the page and thus
 * finds nothing here, so we bounce to vibuxer ourselves.
 */
open class EgyDeadStreamHgBase : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://streamwish.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (code.isBlank()) return

        val page = try {
            app.get(
                "$PLAYER_HOST/e/$code",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                referer = "$mainUrl/"
            ).text
        } catch (e: Throwable) {
            Log.i(TAG, "vibuxer fetch failed $code -> $e")
            return
        }

        val decoded = JsUnpacker(page).unpack() ?: page
        val stream = pickStream(decoded) ?: pickStream(page) ?: return
        val absolute = if (stream.startsWith("//")) "https:$stream" else stream

        callback.invoke(
            newExtractorLink(name, name, absolute, ExtractorLinkType.M3U8) {
                this.referer = "$PLAYER_HOST/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /** The setup references `hls4 || hls3 || hls2`; take the highest numbered present. */
    private fun pickStream(text: String): String? {
        HLS_RE.findAll(text)
            .mapNotNull { m -> m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] } }
            .maxByOrNull { it.first }
            ?.let { return it.second }
        return PLAIN_HLS_RE.find(text)?.groupValues?.get(1)
    }

    private companion object {
        const val TAG = "EgyDeadStreamHg"
        const val PLAYER_HOST = "https://vibuxer.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

        val HLS_RE = Regex("""hls(\d+)"\s*:\s*"([^"]+)"""")
        val PLAIN_HLS_RE = Regex("""["']hls["']\s*:\s*["']([^"']+)["']""")
    }
}

class EgyDeadStreamHg : EgyDeadStreamHgBase()
class EgyDeadStreamHgCloud : EgyDeadStreamHgBase() {
    override var mainUrl = "https://hgcloud.to"
}
