package com.witanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WitAnimePlugin : Plugin() {
    override fun load(context: Context) {
        // File hosts WitAnime serves that CloudStream does not cover. Registering
        // them also stops loadExtractor's fuzzy mainUrl matcher from mis-routing
        // drive.google.com -> InternetArchive and workupload.com -> YourUpload.
        registerExtractorAPI(FourSharedExtractor())
        registerExtractorAPI(GoogleDriveExtractor())
        registerExtractorAPI(WorkUploadExtractor())

        registerMainAPI(WitAnime(context))
    }
}
