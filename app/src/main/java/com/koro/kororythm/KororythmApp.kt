package com.koro.kororythm

import android.app.Application
import com.koro.kororythm.devices.DeviceWatcher
import com.koro.kororythm.dsp.EqStore

class KororythmApp : Application() {
    lateinit var deviceWatcher: DeviceWatcher
        private set

    override fun onCreate() {
        super.onCreate()
        EqStore.init(this)
        deviceWatcher = DeviceWatcher(this).also { it.start() }
    }
}
