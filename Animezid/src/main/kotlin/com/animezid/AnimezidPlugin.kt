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

        // Best-effort hosts
        registerExtractorAPI(AnimezidMegaMaxExtractor())
        registerExtractorAPI(AnimezidVidTubeExtractor())

        // PlayMate (playmate.to) and DoodStream (playmogo.com) are covered by
        // Cloudstream's built-in extractors via loadExtractor().

        registerMainAPI(Animezid())
    }
}
