package com.koro.kororythm

import android.app.Application
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
        deviceWatcher = DeviceWatcher(this).also { it.start() }
    }
}
