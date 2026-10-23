package com.shahid4u

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Shahid4uProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Shahid4u())

        // Register all modern Shahid4u watch & download extractors
        registerExtractorAPI(Shahid4uVinovoApi())
        registerExtractorAPI(Shahid4uVinovoSiApi())
        registerExtractorAPI(Shahid4uVidaraApi())
        registerExtractorAPI(Shahid4uVidaraSoApi())
        registerExtractorAPI(Shahid4uVidaraToApi())
        registerExtractorAPI(Shahid4uFastVidApi())
        registerExtractorAPI(Shahid4uDingtezuniApi())
        registerExtractorAPI(Shahid4uDintezuvioApi())
        registerExtractorAPI(Shahid4uMinochinosApi())
        registerExtractorAPI(Shahid4uByseApi())
        registerExtractorAPI(Shahid4uByseQekahoApi())
        registerExtractorAPI(Shahid4uByseN1mwqApi())
        registerExtractorAPI(Shahid4uMixDropApi())
        registerExtractorAPI(Shahid4uMxDropClickApi())
        registerExtractorAPI(Shahid4uMixDropTopApi())
        registerExtractorAPI(Shahid4uPlaymogoApi())
        registerExtractorAPI(Shahid4uVoeApi())
        registerExtractorAPI(Shahid4uDerrickNationApi())
        registerExtractorAPI(Shahid4uUqloadApi())
        registerExtractorAPI(Shahid4uUqloadIsApi())
        registerExtractorAPI(Shahid4uUqloadToApi())
        registerExtractorAPI(Shahid4uStreamHlsApi())
        registerExtractorAPI(Shahid4uSaveFilesComApi())
        registerExtractorAPI(Shahid4uVidsStApi())
        registerExtractorAPI(Shahid4uUpzurApi())
        registerExtractorAPI(Shahid4uOkRuApi())
    }
}
