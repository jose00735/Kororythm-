package com.koro.kororythm.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.koro.kororythm.MainActivity
import com.koro.kororythm.R
import com.koro.kororythm.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/** What the widget shows; updated by the playback service. */
object NowPlaying {
    data class State(val title: String? = null, val artist: String? = null, val playing: Boolean = false)

    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun update(context: Context, title: String?, artist: String?, playing: Boolean) {
        val s = State(title, artist, playing)
        if (s == state.value) return
        state.value = s
        val app = context.applicationContext
        scope.launch { runCatching { KoroWidget().updateAll(app) } }
    }
}

/** Home-screen mini player in the Neon Grid look: parrot, now playing, ⏮ ⏯ ⏭. */
class KoroWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val s by NowPlaying.state.collectAsState()
            Bar(s)
        }
    }

    @Composable
    private fun Bar(s: NowPlaying.State) {
        val cyan = ColorProvider(Color(0xFF00E5FF))
        val ink = ColorProvider(Color(0xFFE8FDFF))
        val dim = ColorProvider(Color(0xFF5E8796))
        Row(
            GlanceModifier.fillMaxSize().background(Color(0xF204060B)).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                ImageProvider(R.drawable.ic_launcher_foreground), "Open Kororythm",
                GlanceModifier.size(44.dp).clickable(actionStartActivity<MainActivity>()),
            )
            Spacer(GlanceModifier.width(6.dp))
            Column(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                Text(
                    s.title ?: "KORORYTHM", maxLines = 1,
                    style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                )
                Text(
                    s.artist ?: if (s.title == null) "Tap to open" else "", maxLines = 1,
                    style = TextStyle(color = dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                )
            }
            Key("⏮", actionRunCallback<PrevAction>(), cyan)
            Key(if (s.playing) "❚❚" else "▶", actionRunCallback<PlayPauseAction>(), ColorProvider(Color(0xFFFF6A00)))
            Key("⏭", actionRunCallback<NextAction>(), cyan)
        }
    }

    @Composable
    private fun Key(label: String, action: androidx.glance.action.Action, color: ColorProvider) {
        Box(GlanceModifier.size(40.dp).clickable(action), contentAlignment = Alignment.Center) {
            Text(label, style = TextStyle(color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
    }
}

class KoroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KoroWidget()
}

/** Widget buttons talk to the playback service like the app does, through a MediaController. */
private suspend fun withController(context: Context, block: (MediaController) -> Unit) {
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val c = MediaController.Builder(context, token).buildAsync().await()
    try {
        block(c)
        kotlinx.coroutines.delay(400) // let the command reach the service before disconnecting
    } finally {
        c.release()
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withController(context) { c ->
            if (c.isPlaying) c.pause() else { if (c.playbackState == androidx.media3.common.Player.STATE_IDLE) c.prepare(); c.play() }
        }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withController(context) { it.seekToNextMediaItem() }
}

class PrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withController(context) { it.seekToPreviousMediaItem() }
}
