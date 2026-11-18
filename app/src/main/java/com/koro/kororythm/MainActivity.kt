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
import com.koro.kororythm.ui.tags.TagEditViewModel
import com.koro.kororythm.ui.tags.TagEditorScreen
import com.koro.kororythm.ui.dj.DjScreen
import com.koro.kororythm.ui.dj.DjViewModel
import com.koro.kororythm.ui.wizards.AlbumWizardScreen
import com.koro.kororythm.ui.wizards.AlbumWizardViewModel
import com.koro.kororythm.ui.wizards.ImportViewModel
import com.koro.kororythm.ui.wizards.ImportWizardScreen

enum class Screen { PLAYER, LIBRARY, IMPORT, ALBUM, TAGS, DJ }

class MainActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()
    private val libraryVm: LibraryViewModel by viewModels()
    private val importVm: ImportViewModel by viewModels()
    private val albumVm: AlbumWizardViewModel by viewModels()
    private val tagVm: TagEditViewModel by viewModels()
    private val djVm: DjViewModel by viewModels()

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
            var tagsReturn by rememberSaveable { mutableStateOf(Screen.PLAYER) }
            val playerState by vm.state.collectAsStateWithLifecycle()
            val openTags: (String) -> Unit = { uri -> tagVm.load(uri); tagsReturn = screen; screen = Screen.TAGS }
            BackHandler(enabled = screen == Screen.IMPORT || screen == Screen.ALBUM) { screen = Screen.LIBRARY }
            BackHandler(enabled = screen == Screen.TAGS) { screen = tagsReturn }
            BackHandler(enabled = screen == Screen.DJ) { screen = Screen.PLAYER }

            AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                when (s) {
                    Screen.PLAYER -> PlayerScreen(
                        vm,
                        onAddFiles = { picker.launch(AUDIO_TYPES) },
                        onOpenLibrary = { screen = Screen.LIBRARY },
                        onEditTags = openTags,
                        onOpenDj = { screen = Screen.DJ },
                    )
                    Screen.LIBRARY -> LibraryScreen(
                        libraryVm, vm, playerState.current?.uri,
                        onBack = { screen = Screen.PLAYER },
                        onImport = { importVm.restart(); screen = Screen.IMPORT },
                        onNewAlbum = { albumVm.startOver(); screen = Screen.ALBUM },
                        onEditTags = openTags,
                    )
                    Screen.IMPORT -> ImportWizardScreen(
                        importVm,
                        onClose = { screen = Screen.LIBRARY },
                        onOpenLibrary = { screen = Screen.LIBRARY },
                    )
                    Screen.TAGS -> TagEditorScreen(tagVm, vm, onClose = { screen = tagsReturn })
                    Screen.DJ -> DjScreen(djVm, vm, onClose = { screen = Screen.PLAYER })
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
