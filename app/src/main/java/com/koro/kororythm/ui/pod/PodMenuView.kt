package com.koro.kororythm.ui.pod

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.ui.skin.NeonText

/** Colours of the little screen the menu draws on. */
class PodInk(val ink: Color, val dim: Color, val sel: Brush, val selInk: Color)

/**
 * The open menu level, sliding in from the right when you go deeper and back from the left
 * when you press MENU, like the real thing. Tapping a row selects it too.
 */
@Composable
fun PodMenuList(menu: PodMenuState, ink: PodInk, modifier: Modifier = Modifier, onTap: (Int) -> Unit) {
    AnimatedContent(
        targetState = menu.top,
        modifier = modifier,
        transitionSpec = {
            val d = menu.direction
            (slideInHorizontally(tween(220)) { w -> w * d }) togetherWith (slideOutHorizontally(tween(220)) { w -> -w * d })
        },
        contentKey = { "${it.depth}:${it.title}" },
        label = "pod-level",
    ) { level ->
        if (level.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NeonText("Nothing here yet", size = 12.sp, color = ink.dim, glow = null)
            }
            return@AnimatedContent
        }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (level.cursor - 3).coerceAtLeast(0))
        LaunchedEffect(level.cursor) {
            val vis = list.layoutInfo.visibleItemsInfo
            val first = vis.firstOrNull()?.index ?: 0
            val last = vis.lastOrNull()?.index ?: 0
            if (level.cursor < first + 1 || level.cursor > last - 1) list.animateScrollToItem((level.cursor - 3).coerceAtLeast(0))
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            itemsIndexed(level.items) { i, item ->
                PodRow(item, i == level.cursor, ink) { onTap(i) }
            }
        }
    }
}

@Composable
private fun PodRow(item: PodItem, on: Boolean, c: PodInk, onClick: () -> Unit) {
    val fg = if (on) c.selInk else c.ink
    val sub = if (on) c.selInk.copy(alpha = 0.85f) else c.dim
    Row(
        Modifier.fillMaxWidth().then(if (on) Modifier.background(c.sel) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item is PodItem.Action && item.checked != null) {
            Box(Modifier.width(16.dp)) { if (item.checked) Tick(fg) }
        }
        val bold = item is PodItem.Queued && item.current
        NeonText(item.label, Modifier.weight(1f), size = 12.sp, color = fg, glow = null, weight = if (bold) FontWeight.Bold else FontWeight.Normal)
        item.detail?.let {
            Spacer(Modifier.width(6.dp))
            NeonText(it, Modifier.widthIn(max = 110.dp), size = 10.sp, color = sub, glow = null)
        }
        if (item is PodItem.Folder) {
            Spacer(Modifier.width(4.dp))
            Chevron(sub)
        }
    }
}

@Composable
private fun Tick(color: Color) = Canvas(Modifier.size(10.dp)) {
    val w = size.width
    drawLine(color, Offset(w * 0.1f, w * 0.55f), Offset(w * 0.4f, w * 0.85f), 2.2f, StrokeCap.Round)
    drawLine(color, Offset(w * 0.4f, w * 0.85f), Offset(w * 0.92f, w * 0.15f), 2.2f, StrokeCap.Round)
}

@Composable
private fun Chevron(color: Color) = Canvas(Modifier.size(7.dp, 10.dp)) {
    drawLine(color, Offset(1f, 1f), Offset(size.width - 1f, size.height / 2), 2f, StrokeCap.Round)
    drawLine(color, Offset(size.width - 1f, size.height / 2), Offset(1f, size.height - 1f), 2f, StrokeCap.Round)
}
