package com.anime4up

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Anime4UpPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anime4Up(context))
        registerExtractorAPI(Anime4UpMoshahdaExtractor())
    }
}
