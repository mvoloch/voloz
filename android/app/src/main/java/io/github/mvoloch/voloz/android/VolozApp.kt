package io.github.mvoloch.voloz.android

import android.app.Application
import io.github.mvoloch.voloz.android.data.HistoryRepository
import io.github.mvoloch.voloz.android.data.SettingsRepository
import io.github.mvoloch.voloz.android.dictation.DictationController
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.GroqClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons shared by the activity, the accessibility service and the FGS. */
class VolozApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val groq = GroqClient()
    lateinit var settings: SettingsRepository
        private set
    lateinit var history: HistoryRepository
        private set
    lateinit var controller: DictationController
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this, appScope)
        history = HistoryRepository(this, appScope)
        controller = DictationController(this, settings, history, DictationPipeline(groq), appScope)
    }
}
