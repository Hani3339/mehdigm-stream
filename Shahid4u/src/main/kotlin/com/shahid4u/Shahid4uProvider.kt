package com.shahid4u

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Shahid4uProvider : Plugin() {
    override fun load(context: Context) {
        SiteGate.init(context)
        Shahid4uStore.init(context)
        registerMainAPI(Shahid4u())
        registerExtractorAPI(ExternalEarnVidsExtractor())
        registerExtractorAPI(ExternalFastVedExtractor())

    }
}
