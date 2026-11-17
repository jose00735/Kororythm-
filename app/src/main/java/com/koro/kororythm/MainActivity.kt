package com.koro.kororythm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.koro.kororythm.playback.PlayerViewModel
import com.koro.kororythm.ui.player.PlayerScreen

class MainActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()

    /** System file picker; Kororythm keeps read access so the playlist survives restarts. */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlayerScreen(vm, onAddFiles = { picker.launch(AUDIO_TYPES) })
        }
        if (savedInstanceState == null) handleOpenWith(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenWith(intent)
    }

    /** "Open with Kororythm" from a file manager: add the file and play it. */
    private fun handleOpenWith(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        vm.addUris(listOf(uri), playNow = true, persist = false)
    }

    companion object {
        val AUDIO_TYPES = arrayOf("audio/flac", "audio/x-flac", "audio/mpeg", "audio/mp3")
    }
}
