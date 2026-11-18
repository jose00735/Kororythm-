package com.koro.kororythm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.playback.PlayerViewModel
import com.koro.kororythm.ui.library.LibraryScreen
import com.koro.kororythm.ui.library.LibraryViewModel
import com.koro.kororythm.ui.player.PlayerScreen
import com.koro.kororythm.ui.wizards.AlbumWizardScreen
import com.koro.kororythm.ui.wizards.AlbumWizardViewModel
import com.koro.kororythm.ui.wizards.ImportViewModel
import com.koro.kororythm.ui.wizards.ImportWizardScreen

enum class Screen { PLAYER, LIBRARY, IMPORT, ALBUM }

class MainActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()
    private val libraryVm: LibraryViewModel by viewModels()
    private val importVm: ImportViewModel by viewModels()
    private val albumVm: AlbumWizardViewModel by viewModels()

    /** System file picker; Kororythm keeps read access so the playlist survives restarts. */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var screen by rememberSaveable { mutableStateOf(Screen.PLAYER) }
            val playerState by vm.state.collectAsStateWithLifecycle()
            BackHandler(enabled = screen == Screen.IMPORT || screen == Screen.ALBUM) { screen = Screen.LIBRARY }

            AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                when (s) {
                    Screen.PLAYER -> PlayerScreen(
                        vm,
                        onAddFiles = { picker.launch(AUDIO_TYPES) },
                        onOpenLibrary = { screen = Screen.LIBRARY },
                    )
                    Screen.LIBRARY -> LibraryScreen(
                        libraryVm, vm, playerState.current?.uri,
                        onBack = { screen = Screen.PLAYER },
                        onImport = { importVm.restart(); screen = Screen.IMPORT },
                        onNewAlbum = { albumVm.startOver(); screen = Screen.ALBUM },
                    )
                    Screen.IMPORT -> ImportWizardScreen(
                        importVm,
                        onClose = { screen = Screen.LIBRARY },
                        onOpenLibrary = { screen = Screen.LIBRARY },
                    )
                    Screen.ALBUM -> AlbumWizardScreen(
                        albumVm,
                        onClose = { screen = Screen.LIBRARY },
                        onPlay = { tracks ->
                            vm.playTracks(tracks.map { it.toTrackInfo() })
                            screen = Screen.PLAYER
                        },
                    )
                }
            }
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
