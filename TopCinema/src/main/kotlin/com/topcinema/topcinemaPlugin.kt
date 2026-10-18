package com.topcinema

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TopCinemaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TopCinema())
        registerExtractorAPI(TCStreamWish())
        registerExtractorAPI(TCDoodstream())
        registerExtractorAPI(TCStreamTape())
        registerExtractorAPI(TCUqload())
        registerExtractorAPI(TCVidTube())
        registerExtractorAPI(TCOKStream())
        registerExtractorAPI(TCUpDown())
        registerExtractorAPI(TCEarnVids())
        registerExtractorAPI(TCByse())
        registerExtractorAPI(TCLuluStream())
    }
}
