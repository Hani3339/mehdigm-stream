package com.cimawbas

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CimawbasPlugin : Plugin() {
    override fun load(context: Context) {
        registerExtractorAPI(MixdropExtractor())
        registerExtractorAPI(StreamHgExtractor())

        registerMainAPI(Cimawbas())
    }
}
