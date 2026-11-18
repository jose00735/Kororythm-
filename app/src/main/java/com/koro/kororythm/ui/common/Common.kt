package com.koro.kororythm.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koro.kororythm.ui.skin.GridBackground
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.drawParrot
import com.koro.kororythm.ui.skin.neonBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full screen on the neon grid with a title bar: back button, title, actions. */
@Composable
fun NeonScreen(
    title: String,
    onBack: () -> Unit,
    backLabel: String = "◂ BACK",
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        GridBackground(1f)
        // Dim the grid behind full pages so text on it stays readable.
        Box(Modifier.fillMaxSize().background(Neon.Bg.copy(alpha = 0.78f)))
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NeonButton(backLabel, onBack)
                Spacer(Modifier.width(10.dp))
                NeonText(title, Modifier.weight(1f), size = 14.sp, weight = FontWeight.Bold, spacing = 3.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = actions)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** Single-line neon text input. */
@Composable
fun NeonTextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    numeric: Boolean = false,
) {
    Column(modifier) {
        label?.let { NeonText(it, size = 9.sp, color = Neon.Dim, glow = null, spacing = 2.sp) }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .background(if (Neon.isHiFi) Neon.Lcd else Color(0x99000000), RoundedCornerShape(6.dp))
                .neonBorder(Neon.Cyan, 6.dp, 0.6f)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            val textColor = if (Neon.isHiFi) Neon.LcdText else Neon.Ink
            if (value.isEmpty()) NeonText(placeholder, size = 13.sp, color = if (Neon.isHiFi) Neon.LcdText.copy(alpha = 0.45f) else Neon.Dim, glow = null)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Neon.style(13.sp, textColor, glow = null),
                cursorBrush = SolidColor(Neon.Orange),
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Neon check box with a label. */
@Composable
fun NeonCheck(checked: Boolean, modifier: Modifier = Modifier, accent: Color = Neon.Orange) {
    Canvas(modifier.size(20.dp)) {
        val s = size.minDimension
        drawRoundRect(accent.copy(alpha = if (checked) 0.25f else 0.08f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        drawRoundRect(accent, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        if (checked) {
            drawLine(Neon.Ink, Offset(s * 0.24f, s * 0.52f), Offset(s * 0.43f, s * 0.72f), 2.dp.toPx())
            drawLine(Neon.Ink, Offset(s * 0.43f, s * 0.72f), Offset(s * 0.78f, s * 0.3f), 2.dp.toPx())
        }
    }
}

private val artCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/**
 * Cover art from a file path (scanned art) or a content:// URI (a cover the user picked).
 * Falls back to a small neon parrot when there is none.
 */
@Composable
fun ArtThumb(
    path: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentUri: String? = null,
    url: String? = null,
    bytes: ByteArray? = null,
) {
    val key = bytes?.let { "bytes:${it.size}:${it.contentHashCode()}" } ?: url ?: contentUri ?: path
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val image by produceState<ImageBitmap?>(null, key) {
        if (key == null || inPreview) return@produceState
        artCache.get(key)?.let { value = it.asImageBitmap(); return@produceState }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                if (bytes != null) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = if (bytes.size > 400_000) 2 else 1 })
                } else if (url != null) {
                    // network covers (database search results), cached on disk
                    val f = java.io.File(context.cacheDir, "covers/" + url.hashCode().toUInt().toString(16) + ".img")
                    if (!f.exists()) {
                        f.parentFile?.mkdirs()
                        com.koro.kororythm.tags.TagSearch.fetchCover(url)?.let { f.writeBytes(it) }
                    }
                    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) else null
                } else if (contentUri != null) {
                    context.contentResolver.openInputStream(Uri.parse(contentUri))?.use { ins ->
                        BitmapFactory.decodeStream(ins, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                } else {
                    BitmapFactory.decodeFile(path)
                }
            }.getOrNull()
        }
        if (bmp != null) { artCache.put(key, bmp); value = bmp.asImageBitmap() }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(if (Neon.isHiFi) Neon.Lcd else Color(0xFF071624))
            .neonBorder(Neon.Cyan, 6.dp, 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Canvas(Modifier.fillMaxSize().padding(size * 0.12f)) {
                drawParrot(Offset.Zero, this.size.minDimension, body = Neon.Cyan.copy(alpha = 0.55f), phones = Neon.Orange.copy(alpha = 0.6f))
            }
        }
    }
}

/** Wizard frame: title, numbered step chips, content, and BACK / NEXT at the bottom. */
@Composable
fun WizardFrame(
    title: String,
    steps: List<String>,
    current: Int,
    onClose: () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    NeonScreen(title = title, onBack = onClose, backLabel = "✕ CLOSE") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { i, s ->
                val c = when {
                    i == current -> Neon.Orange
                    i < current -> Neon.Cyan
                    else -> Neon.Dim
                }
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(c, RoundedCornerShape(2.dp)))
                    NeonText("${i + 1} ${s.uppercase()}", Modifier.padding(top = 4.dp), size = 9.sp, color = c, glow = if (i == current) c else null, spacing = 1.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().weight(1f), content = content)
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = footer,
        )
    }
}

/** A row of toggle-style tabs. */
@Composable
fun NeonTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l -> NeonButton(l, { onSelect(i) }, active = i == selected) }
    }
}

/** Big clickable neon card used for wizard choices. */
@Composable
fun ChoiceCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) Neon.Cyan.copy(alpha = 0.10f) else Neon.Panel, RoundedCornerShape(10.dp))
            .neonBorder(if (selected) Neon.Cyan else Neon.Dim, 10.dp, if (selected) 1f else 0.6f)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            NeonText(title, size = 13.sp, weight = FontWeight.Bold, color = if (selected) Neon.Ink else Neon.Cyan)
            Spacer(Modifier.height(3.dp))
            NeonText(body, size = 11.sp, color = Neon.Dim, glow = null, maxLines = 3)
        }
        trailing()
    }
}
