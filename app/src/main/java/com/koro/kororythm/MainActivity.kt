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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.koro.kororythm.playback.TrackInfo
import com.koro.kororythm.ui.lyrics.LyricsDropHost
import com.koro.kororythm.ui.viz.rememberVizState
import com.koro.kororythm.ui.lyrics.LyricsViewModel
import com.koro.kororythm.ui.upgrade.UpgradeHost
import com.koro.kororythm.ui.upgrade.UpgradeViewModel
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
import com.koro.kororythm.ui.downloads.DownloadsScreen
import com.koro.kororythm.ui.downloads.DownloadsViewModel
import com.koro.kororythm.ui.wizards.AlbumWizardScreen
import com.koro.kororythm.ui.wizards.AlbumWizardViewModel
import com.koro.kororythm.ui.wizards.ImportViewModel
import com.koro.kororythm.ui.wizards.ImportWizardScreen

enum class Screen { PLAYER, LIBRARY, IMPORT, ALBUM, TAGS, DJ, DOWNLOADS }

class MainActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()
    private val libraryVm: LibraryViewModel by viewModels()
    private val importVm: ImportViewModel by viewModels()
    private val albumVm: AlbumWizardViewModel by viewModels()
    private val tagVm: TagEditViewModel by viewModels()
    private val djVm: DjViewModel by viewModels()
    private val downloadsVm: DownloadsViewModel by viewModels()
    private val lyricsVm: LyricsViewModel by viewModels()
    private val upgradeVm: UpgradeViewModel by viewModels()

    /** A link shared from YouTube etc., waiting for the UI to open Downloads. */
    private var pendingShare by mutableStateOf<String?>(null)

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
            // "FIX ONE BY ONE": the tag editor walks through these songs with NEXT.
            var fixQueue by rememberSaveable { mutableStateOf(emptyList<String>()) }
            var fixPos by rememberSaveable { mutableStateOf(0) }
            BackHandler(enabled = screen == Screen.TAGS) { fixQueue = emptyList(); screen = tagsReturn }
            BackHandler(enabled = screen == Screen.IMPORT || screen == Screen.ALBUM) { screen = Screen.LIBRARY }
            BackHandler(enabled = screen == Screen.DJ) { screen = Screen.PLAYER }
            var downloadsReturn by rememberSaveable { mutableStateOf(Screen.PLAYER) }
            BackHandler(enabled = screen == Screen.DOWNLOADS) { screen = downloadsReturn }
            val openDownloads: () -> Unit = { downloadsReturn = screen; screen = Screen.DOWNLOADS }
            // Lyrics drop down over any screen, like a Guake console.
            var lyricsOpen by rememberSaveable { mutableStateOf(false) }
            var lyricsTrack by remember { mutableStateOf<TrackInfo?>(null) }
            val openLyrics: (TrackInfo?) -> Unit = { t -> lyricsTrack = t; lyricsOpen = true }
            // One reader of the audio spectrum, shared by the player visualizer and the lyrics backdrop.
            val viz = rememberVizState()
            LaunchedEffect(pendingShare) {
                pendingShare?.let { downloadsVm.shared(it); if (screen != Screen.DOWNLOADS) openDownloads(); pendingShare = null }
            }

            Box(Modifier.fillMaxSize()) {
                AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                    when (s) {
                        Screen.PLAYER -> PlayerScreen(
                            vm,
                            onAddFiles = { picker.launch(AUDIO_TYPES) },
                            onOpenLibrary = { screen = Screen.LIBRARY },
                            onEditTags = openTags,
                            onOpenDj = { screen = Screen.DJ },
                            onLyrics = { if (lyricsOpen) lyricsOpen = false else openLyrics(null) },
                            viz = viz,
                            onSaveQueue = { uris ->
                                libraryVm.setTab(com.koro.kororythm.ui.library.LibTab.PLAYLISTS)
                                libraryVm.saveQueue(uris)
                                screen = Screen.LIBRARY
                            },
                        )
                        Screen.LIBRARY -> LibraryScreen(
                            libraryVm, vm, playerState.current?.uri,
                            onBack = { screen = Screen.PLAYER },
                            onImport = { importVm.restart(); screen = Screen.IMPORT },
                            onNewAlbum = { albumVm.startOver(); screen = Screen.ALBUM },
                            onEditTags = openTags,
                            onDownloads = openDownloads,
                        onFixAll = { uris -> if (uris.isNotEmpty()) { fixQueue = uris; fixPos = 0; openTags(uris[0]) } },
                            onUpgrade = { t -> upgradeVm.start(t) },
                            onLyrics = { t -> openLyrics(if (t.uri == playerState.current?.uri) null else t.toTrackInfo()) },
                        )
                        Screen.IMPORT -> ImportWizardScreen(
                            importVm,
                            onClose = { screen = Screen.LIBRARY },
                            onOpenLibrary = { screen = Screen.LIBRARY },
                        )
                        Screen.TAGS -> TagEditorScreen(
                            tagVm, vm,
                            onClose = { fixQueue = emptyList(); screen = tagsReturn },
                            next = if (fixQueue.isEmpty()) null else ({
                                if (fixPos + 1 < fixQueue.size) { fixPos++; tagVm.load(fixQueue[fixPos]) } else { fixQueue = emptyList(); screen = tagsReturn }
                            }),
                            progress = if (fixQueue.isEmpty()) null else "${fixPos + 1}/${fixQueue.size}",
                        )
                        Screen.DJ -> DjScreen(djVm, vm, onClose = { screen = Screen.PLAYER })
                        Screen.DOWNLOADS -> DownloadsScreen(downloadsVm, vm, onClose = { screen = downloadsReturn }, upgrade = upgradeVm)
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
                LyricsDropHost(lyricsVm, vm, lyricsOpen, lyricsTrack, viz, onClose = { lyricsOpen = false })
                UpgradeHost(upgradeVm, vm)
            }
        }
        if (savedInstanceState == null) handleOpenWith(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenWith(intent)
    }

    /** "Open with Kororythm" from a file manager: add the file and play it. Shared links open Downloads. */
    private fun handleOpenWith(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            pendingShare = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            return
        }
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        vm.addUris(listOf(uri), playNow = true, persist = false)
    }

    companion object {
        val AUDIO_TYPES = arrayOf("audio/flac", "audio/x-flac", "audio/mpeg", "audio/mp3")
    }
}
