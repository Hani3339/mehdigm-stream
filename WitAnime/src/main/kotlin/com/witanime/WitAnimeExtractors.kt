package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

/**
 * Custom extractors for the file hosts WitAnime serves.
 *
 * CloudStream's built-ins do not cover these hosts — worse, `loadExtractor`'s
 * fuzzy `mainUrl` matcher actively mis-routes them: `drive.google.com` scores
 * ~82 against `https://archive.org` (InternetArchive) and `workupload.com`
 * scores ~87 against `https://www.yourupload.com`, so the wrong extractor used
 * to swallow the link. Registering these in the plugin guarantees the exact
 * host wins the exact-match branch (step 1 of `loadExtractor`).
 *
 * MEGA is intentionally not handled: it serves an AES-encrypted blob that only
 * the browser's JS decrypts, and `mega.nz/embed` is an HTML player, so neither
 * can be played by ExoPlayer from a plain URL.
 */

/** `https://www.4shared.com/video/<code>/<name>.html`. */
class FourSharedExtractor : ExtractorApi() {
    override val name = "4shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""4shared\.com/(?:video|web|file|mp3|office)/[^/]+/([^/?"]+)""")
            .find(url)?.groupValues?.get(1)?.removeSuffix(".html") ?: return
        val origin = "https://www.4shared.com"
        val ref = referer ?: "$origin/"

        val page = try {
            app.get(url, referer = ref).text
        } catch (_: Exception) {
            return
        }

        // The HTML embed carries a `vars` JSON blob with the signed download URL.
        val raw = Regex("""downloadUrl"?\s*[:=]\s*"([^"]+)"""").find(page)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'<>\s]+\.4shared\.com/download[^"'<>\s]*""").find(page)?.value
            ?: return
        val media = URLDecoder.decode(raw, "UTF-8").replace("\\/", "/")
        if (!media.startsWith("http")) return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "4shared",
                url = media,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = ref
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to ref,
                )
            }
        )
        println("[WitAnime][4shared] code=$code -> ${media.take(80)}")
    }
}

/**
 * `https://drive.google.com/file/d/<id>/...` and `https://drive.google.com/uc?id=<id>`.
 *
 * Only publicly shared files (no confirmation token) can be streamed directly;
 * those are served from `*.googleusercontent.com`, which no extractor matches,
 * so the resolved URL is played directly.
 */
class GoogleDriveExtractor : ExtractorApi() {
    override val name = "GoogleDrive"
    override val mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""/file/d/([A-Za-z0-9_-]{10,})""").find(url)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([A-Za-z0-9_-]{10,})""").find(url)?.groupValues?.get(1)
            ?: return

        // `confirm=t` skips the "can't scan for viruses" interstitial for files
        // over the size limit; harmless for the rest.
        val direct = "https://drive.usercontent.google.com/download" +
            "?id=$id&export=download&confirm=t"
        val head = try {
            app.head(
                direct,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://drive.google.com/",
                )
            )
        } catch (_: Exception) {
            null
        }
        val finalUrl = head?.url ?: direct
        if (!finalUrl.contains("googleusercontent.com") && !finalUrl.contains("drive.usercontent.google.com")) {
            return
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Google Drive",
                url = finalUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://drive.google.com/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://drive.google.com/",
                )
            }
        )
        println("[WitAnime][gdrive] id=$id head=${head?.code} -> ${finalUrl.take(90)}")
    }
}

/** `https://workupload.com/file/<id>`. */
class WorkUploadExtractor : ExtractorApi() {
    override val name = "WorkUpload"
    override val mainUrl = "https://workupload.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = "https://workupload.com"
        val page = try {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$origin/")).text
        } catch (_: Exception) {
            return
        }
        val media = Regex("""https?://[^"'<>\s]*workupload\.com/(?:download|file/download)/[^"'<>\s]+""")
            .find(page)?.value ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "WorkUpload",
                url = media,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$origin/",
                )
            }
        )
    }
}
