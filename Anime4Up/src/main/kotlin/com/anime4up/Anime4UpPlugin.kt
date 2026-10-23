package com.anime4up

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Anime4UpPlugin : Plugin() {
    override fun load(context: Context) {
        Anime4UpNetwork.init(context)
        registerMainAPI(Anime4Up())

        // Register custom extractors so CloudStream can also invoke them directly
        registerExtractorAPI(Anime4UpMegaMaxExtractor())
        registerExtractorAPI(Anime4UpShare4MaxExtractor())
        registerExtractorAPI(Anime4UpMediaFireExtractor())
        registerExtractorAPI(Anime4UpVoeExtractor())
        registerExtractorAPI(Anime4UpVkExtractor())
        registerExtractorAPI(Anime4UpVkComExtractor())
        registerExtractorAPI(Anime4UpMp4UploadExtractor())
        registerExtractorAPI(Anime4UpMp4UploadBareExtractor())
        registerExtractorAPI(Anime4UpUqloadExtractor())
        registerExtractorAPI(Anime4UpUqloadIsExtractor())
        registerExtractorAPI(Anime4UpUqloadComExtractor())
        registerExtractorAPI(Anime4UpUqloadToExtractor())
        registerExtractorAPI(Anime4UpDoodExtractor())
        registerExtractorAPI(Anime4UpPlaymogoExtractor())
        registerExtractorAPI(Anime4UpDoodStreamComExtractor())
        registerExtractorAPI(Anime4UpByseExtractor())
        registerExtractorAPI(Anime4UpLuluStreamExtractor())
        registerExtractorAPI(Anime4UpSaveFilesExtractor())
        registerExtractorAPI(Anime4UpVnxPlayerExtractor())
        registerExtractorAPI(Anime4UpVnxDirectExtractor())
        registerExtractorAPI(Anime4UpFourSharedExtractor())
        registerExtractorAPI(Anime4UpGoFileExtractor())
        registerExtractorAPI(Anime4UpMoshahdaExtractor())
        registerExtractorAPI(Anime4UpUpnShareExtractor())
        registerExtractorAPI(Anime4UpStreamP2PExtractor())
        registerExtractorAPI(Anime4UpRpmShareExtractor())
        registerExtractorAPI(Anime4UpFireStreamExtractor())
        registerExtractorAPI(Anime4UpVidaraExtractor())
        registerExtractorAPI(Anime4UpEarnVidsExtractor())
        registerExtractorAPI(Anime4UpMixdropExtractor())
    }
}
