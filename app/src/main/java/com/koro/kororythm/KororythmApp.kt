package com.koro.kororythm

import android.app.Application
import kotlinx.coroutines.launch
import com.koro.kororythm.devices.DeviceWatcher
import com.koro.kororythm.dsp.EqStore
import com.koro.kororythm.library.ImportWorker
import com.koro.kororythm.library.Library
import com.koro.kororythm.ui.skin.SkinState

class KororythmApp : Application() {
    lateinit var deviceWatcher: DeviceWatcher
        private set

    override fun onCreate() {
        super.onCreate()
        EqStore.init(this)
        SkinState.init(this)
        com.koro.kororythm.dj.DjStore.init(this)
        Library.init(this)
        ImportWorker.schedulePeriodic(this)
        // Songs whose files carry a genre get sorted right away (no network needed).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.koro.kororythm.genres.GenreWorker.sortFileGenres(Library.dao) }
        }
        deviceWatcher = DeviceWatcher(this).also { it.start() }
    }
}
