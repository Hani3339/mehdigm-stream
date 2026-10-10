package com.egydead

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class EgyDeadStreamRubyCom : EgyDeadStreamRuby() {
    override var mainUrl = "https://stmruby.com"
}

class EgyDeadStreamRubyNet : EgyDeadStreamRuby() {
    override var mainUrl = "https://streamruby.net"
}

class EgyDeadStreamRubyVip : EgyDeadStreamRuby() {
    override var mainUrl = "https://rubyvidhub.com"
}

/**
 * StreamRuby (`stmruby.com`, `streamruby.net`, `rubyvidhub.com`) embed host used as an
 * EgyDead source. The embed page ships a p.a.c.k.e.r-obfuscated jwplayer setup whose
 * `sources[].file` is the master playlist.
 */
open class EgyDeadStreamRuby : ExtractorApi() {
    override var name = "StreamRuby"
    override var mainUrl = "https://streamruby.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val text = try {
            app.get(url, referer = referer).text
        } catch (_: Throwable) {
            return
        }
        val unpacked = JsUnpacker(text).unpack() ?: text

        val stream = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
            .find(unpacked)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                .find(unpacked)?.groupValues?.get(1)

        if (stream.isNullOrBlank()) return

        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
