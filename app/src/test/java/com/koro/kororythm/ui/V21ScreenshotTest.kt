package com.koro.kororythm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.koro.kororythm.sync.Flight
import com.koro.kororythm.sync.SyncPhase
import com.koro.kororythm.sync.SyncUi
import com.koro.kororythm.ui.skin.GridBackground
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import com.koro.kororythm.ui.sync.SyncStage
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** v2.1: the sync animation — radar, covers flying both ways, the parrot landing. */
class V21ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    @After fun reset() = SkinState.set(SkinId.NEON)

    private val moving = SyncUi(SyncPhase.MOVING, "JOSE-PC", done = 3, total = 8, bytes = 41_000_000, totalBytes = 96_000_000, current = "Neon Runway")
    private val now = 1_000_000L

    private fun stages(name: String) = paparazzi.snapshot(name) {
        androidx.compose.foundation.layout.Box {
            GridBackground(1f)
            Column(Modifier.padding(12.dp)) {
                val m = Modifier.fillMaxWidth().height(230.dp).padding(bottom = 10.dp)
                SyncStage(SyncUi(), searching = true, modifier = m, frozenMs = now)
                SyncStage(moving, false, m, frozenMs = now, demoFlights = listOf(Flight(true, "a", null) to 0.35f, Flight(false, "b", null) to 0.22f), demoElapsed = 2f)
                SyncStage(moving.copy(phase = SyncPhase.DONE), false, m, frozenMs = now, demoElapsed = 1.6f)
            }
        }
    }

    @Test fun neon() = stages("210_sync_neon")
    @Test fun pod() { SkinState.set(SkinId.POD); stages("211_sync_pod") }
}
