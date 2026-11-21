package com.koro.kororythm.ui.player

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.SkinId
import com.koro.kororythm.ui.skin.SkinState
import com.koro.kororythm.ui.skin.Skins
import com.koro.kororythm.ui.skin.neonBorder

/** Layouts of the main (player) screen. All original designs. */
enum class PlayerLayoutId(val label: String, val blurb: String) {
    CLASSIC("CLASSIC", "Winamp-style windows: player, equalizer, playlist"),
    COVER("COVER STAGE", "The album art, big, over its own blurred colours"),
    POD("POD", "Pocket-player screen and a working click wheel"),
    PRISM("PRISM", "Full-screen light show that dances to the music"),
}

/** The layout in use, remembered like the skin. */
object LayoutState {
    var current by mutableStateOf(PlayerLayoutId.CLASSIC)
        private set
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)
        current = runCatching { PlayerLayoutId.valueOf(prefs?.getString("id", null) ?: "CLASSIC") }.getOrDefault(PlayerLayoutId.CLASSIC)
    }

    fun set(id: PlayerLayoutId) {
        current = id
        prefs?.edit()?.putString("id", id.name)?.apply()
    }
}

/** Shared "is the style chooser open" flag, so every layout can open it. */
object StyleSheetState {
    var open by mutableStateOf(false)
}

/** ◐ STYLE: pick a skin and a layout; changes apply live behind the sheet. */
@Composable
fun StyleSheet() {
    if (!StyleSheetState.open) return
    androidx.activity.compose.BackHandler { StyleSheetState.open = false }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, null) { StyleSheetState.open = false },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(Neon.Panel, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .neonBorder(Neon.Cyan, 16.dp, 0.8f)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            NeonText("LAYOUT", size = 10.sp, color = Neon.Dim, glow = null, spacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            PlayerLayoutId.entries.forEach { l ->
                Choice(l.label, l.blurb, LayoutState.current == l, swatch = null) { LayoutState.set(l) }
            }
            Spacer(Modifier.height(12.dp))
            NeonText("SKIN", size = 10.sp, color = Neon.Dim, glow = null, spacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            SkinId.entries.forEach { s ->
                val p = Skins.of(s)
                Choice(s.label, s.blurb, SkinState.current == s, swatch = listOf(p.bg, p.panel, p.cyan, p.orange)) { SkinState.set(s) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                com.koro.kororythm.ui.skin.NeonButton("DONE", { StyleSheetState.open = false }, accent = Neon.Orange, active = true)
            }
        }
    }
}

@Composable
private fun Choice(title: String, blurb: String, on: Boolean, swatch: List<Color>?, onClick: () -> Unit) {
    val c = if (on) Neon.Orange else Neon.Dim
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(c.copy(alpha = if (on) 0.12f else 0.04f), RoundedCornerShape(8.dp))
            .neonBorder(c, 8.dp, if (on) 0.9f else 0.3f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            NeonText(title, size = 13.sp, weight = FontWeight.Bold, color = if (on) Neon.Orange else Neon.Ink, glow = null)
            NeonText(blurb, size = 10.sp, color = Neon.Dim, glow = null)
        }
        swatch?.let {
            Row(Modifier.padding(start = 8.dp)) {
                it.forEach { col -> Box(Modifier.size(14.dp).background(Brush.verticalGradient(listOf(col, col)), RoundedCornerShape(3.dp)).padding(1.dp)); Spacer(Modifier.width(3.dp)) }
            }
        }
        if (on) NeonText("✓", Modifier.padding(start = 8.dp), size = 15.sp, color = Neon.Orange, glow = null)
    }
}
