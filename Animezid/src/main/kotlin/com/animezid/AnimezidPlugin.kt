package com.animezid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimezidPlugin : Plugin() {
    override fun load(context: Context) {
        // Zid / SmartPlayer family
        registerExtractorAPI(AnimezidRpmHostExtractor())
        registerExtractorAPI(AnimezidUpnShareExtractor())
        registerExtractorAPI(AnimezidStreamP2PExtractor())
        registerExtractorAPI(AnimezidEasyVidPlayExtractor())

        // Direct HLS hosts
        registerExtractorAPI(AnimezidUqloadExtractor())
        registerExtractorAPI(AnimezidUqloadToExtractor())
        registerExtractorAPI(AnimezidStreamWishExtractor())
        registerExtractorAPI(AnimezidStreamRubyExtractor())
        registerExtractorAPI(AnimezidStreamRubyComExtractor())

        // Best-effort hosts
        registerExtractorAPI(AnimezidMegaMaxExtractor())
        registerExtractorAPI(AnimezidVidTubeExtractor())
        registerExtractorAPI(AnimezidAbyssExtractor())
        registerExtractorAPI(AnimezidPlayMateExtractor())
        registerExtractorAPI(AnimezidByseExtractor())
        registerExtractorAPI(AnimezidMp4UploadExtractor())

        // DoodStream (playmogo.com) is covered by Cloudstream's built-in
        // extractors via loadExtractor().

        registerMainAPI(Animezid())
    }
}
