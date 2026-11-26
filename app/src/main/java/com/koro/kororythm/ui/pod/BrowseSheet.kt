package com.koro.kororythm.ui.pod

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.ui.player.ClickWheel
import com.koro.kororythm.ui.player.PodBody
import com.koro.kororythm.ui.player.WheelButton
import com.koro.kororythm.ui.player.podColors
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder
import kotlinx.coroutines.launch

/** Is the iPod browser open over the Classic, Cover Stage or Prism layout. */
object BrowseState {
    var open by mutableStateOf(false)
}

/** Round button with a tiny click wheel on it: opens the iPod browser. */
@Composable
fun WheelIconButton(modifier: Modifier = Modifier, onClick: () -> Unit = { BrowseState.open = true }) {
    val ring = Neon.metal?.let { Color(0xFF8C939B) } ?: Neon.Cyan
    Box(
        modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = "Browse the library with the click wheel" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2
            drawCircle(ring.copy(alpha = 0.18f), r)
            drawCircle(ring, r - 1f, style = Stroke(2f))
            drawCircle(ring, r * 0.36f, style = Stroke(1.6f))
            val d = r * 0.68f
            listOf(Offset(0f, -d), Offset(0f, d), Offset(-d, 0f), Offset(d, 0f)).forEach { o ->
                drawCircle(ring, 1.6f, center + o)
            }
        }
    }
}

/**
 * The iPod browser as a sheet: the same menus as the Pod layout, on a small screen with a
 * click wheel under it. Rows can be tapped as well.
 */
@Composable
fun BrowseSheet(st: PlayerUiState, actions: PlayerActions, menuState: PodMenuState? = null) {
    val state by rememberUpdatedState(st)
    val menu = menuState ?: remember { PodMenuState(PodTree(RoomCatalog, actions) { state }) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    fun close() { BrowseState.open = false; menu.home() }
    fun choose(i: Int) {
        if (menu.busy) return
        scope.launch { if (menu.select(actions, i) == PodResult.NOW_PLAYING) close() }
    }

    if (BrowseState.open) BackHandler { if (!menu.back()) close() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(BrowseState.open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                    .clickable(remember { MutableInteractionSource() }, null) { close() },
            )
        }
        AnimatedVisibility(
            BrowseState.open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Neon.Panel, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .neonBorder(Neon.Cyan, 18.dp, 0.7f)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NeonText("BROWSE", Modifier.weight(1f), size = 10.sp, color = Neon.Dim, glow = null, spacing = 3.sp)
                    com.koro.kororythm.ui.skin.NeonButton("DONE", { close() }, accent = Neon.Orange, active = true)
                }
                Spacer(Modifier.height(10.dp))
                PodBody(Modifier.width(300.dp).height(440.dp)) {
                    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val c = podColors()
                        Column(Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(8.dp)).background(c.bg).neonBorder(Neon.Dim, 8.dp, 0.6f)) {
                            Row(Modifier.fillMaxWidth().background(c.bar).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                NeonText(if (st.isPlaying) "▶" else "❚❚", Modifier.width(18.dp), size = 9.sp, color = c.barInk, glow = null)
                                NeonText(menu.top.title, Modifier.weight(1f), size = 11.sp, weight = FontWeight.Bold, color = c.barInk, glow = null)
                            }
                            PodMenuList(menu, PodInk(c.ink, c.dim, c.sel, c.selInk), Modifier.fillMaxSize(), ::choose)
                        }
                        Spacer(Modifier.weight(1f))
                        ClickWheel(
                            Modifier.size(176.dp),
                            onTick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); menu.tick(it) },
                            onPress = { b ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                when (b) {
                                    WheelButton.MENU -> if (!menu.back()) close()
                                    WheelButton.CENTER -> choose(menu.top.cursor)
                                    WheelButton.PREV -> actions.previous()
                                    WheelButton.NEXT -> actions.next()
                                    WheelButton.PLAY -> actions.playPause()
                                }
                            },
                            playing = st.isPlaying,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
