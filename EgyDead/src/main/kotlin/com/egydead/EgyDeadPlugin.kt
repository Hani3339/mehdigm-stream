package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EgyDeadPlugin : Plugin() {
    override fun load(context: Context) {
        // Packer-based file hosts (StreamWish/StreamHG family + VidHide/EarnVids).
        registerExtractorAPI(EgyDeadStreamWish())
        registerExtractorAPI(EgyDeadStreamWishSite())
        registerExtractorAPI(EgyDeadHgCloud())
        registerExtractorAPI(EgyDeadHgLink())
        registerExtractorAPI(EgyDeadEarnVids())
        registerExtractorAPI(EgyDeadVidHide())
        registerExtractorAPI(EgyDeadVidHideAlt())

        // StreamRuby.
        registerExtractorAPI(EgyDeadStreamRubyCom())
        registerExtractorAPI(EgyDeadStreamRubyNet())
        registerExtractorAPI(EgyDeadStreamRubyVip())

        // Built-in mirrors with new/rotated TLDs.
        registerExtractorAPI(EgyDeadUqload())
        registerExtractorAPI(EgyDeadByse())

        registerMainAPI(EgyDead())
    }
}
