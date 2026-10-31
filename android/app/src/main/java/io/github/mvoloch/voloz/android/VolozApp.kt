package io.github.mvoloch.voloz.android

import android.app.Application
import io.github.mvoloch.voloz.android.data.DictationLog
import io.github.mvoloch.voloz.android.data.HistoryRepository
import io.github.mvoloch.voloz.android.data.SettingsRepository
import io.github.mvoloch.voloz.android.dictation.DictationController
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons shared by the activity, the accessibility service and the FGS. */
class VolozApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val api = ApiClient()
    lateinit var settings: SettingsRepository
        private set
    lateinit var history: HistoryRepository
        private set
    lateinit var controller: DictationController
        private set
    lateinit var pipeline: DictationPipeline
        private set
    lateinit var log: DictationLog
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this, appScope)
        history = HistoryRepository(this, appScope)
        pipeline = DictationPipeline(api)
        log = DictationLog(this)
        controller = DictationController(this, settings, history, pipeline, appScope, log)
    }
}
