package com.shooflive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShoofLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ShoofLive())
    }
}
