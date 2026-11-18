package com.koro.kororythm.ui.wizards

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.library.MusicBrainz
import com.koro.kororythm.library.SourceEntity
import com.koro.kororythm.library.TrackEntity
import com.koro.kororythm.ui.common.ChoiceCard
import com.koro.kororythm.ui.common.NeonCheck
import com.koro.kororythm.ui.common.WizardFrame
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.neonBorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Everything the import wizard can ask for (no-ops by default, for previews). */
open class ImportCallbacks {
    open fun togglePhoneLibrary(enabled: Boolean) {}
    open fun addFolder() {}
    open fun removeFolder(src: SourceEntity) {}
    open fun startScan() {}
    open fun findTags(t: TrackEntity) {}
    open fun apply(t: TrackEntity, s: MusicBrainz.Suggestion) {}
    open fun skip(t: TrackEntity) {}
    open fun finishReview() {}
    open fun restart() {}
    open fun close() {}
    open fun openLibrary() {}
}

/** Wires the wizard to its ViewModel, the folder picker and the music permission. */
@Composable
fun ImportWizardScreen(vm: ImportViewModel, onClose: () -> Unit, onOpenLibrary: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.addFolder(it) }
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setPhoneLibrary(true)
    }
    val callbacks = remember(vm) {
        object : ImportCallbacks() {
            override fun togglePhoneLibrary(enabled: Boolean) {
                if (!enabled) vm.setPhoneLibrary(false)
                else if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) vm.setPhoneLibrary(true)
                else permission.launch(audioPermission)
            }
            override fun addFolder() = folderPicker.launch(null)
            override fun removeFolder(src: SourceEntity) { vm.removeFolder(src) }
            override fun startScan() = vm.startScan()
            override fun findTags(t: TrackEntity) { vm.findTags(t) }
            override fun apply(t: TrackEntity, s: MusicBrainz.Suggestion) { vm.apply(t, s) }
            override fun skip(t: TrackEntity) = vm.skip(t)
            override fun finishReview() = vm.finishReview()
            override fun restart() = vm.restart()
            override fun close() = onClose()
            override fun openLibrary() = onOpenLibrary()
        }
    }
    ImportWizardContent(ui, callbacks)
}

private val STEPS = listOf("Sources", "Scan", "Review", "Done")

@Composable
fun ImportWizardContent(ui: ImportUi, cb: ImportCallbacks) {
    WizardFrame(
        title = "UPLOAD TO THE GRID",
        steps = STEPS,
        current = ui.step.ordinal,
        onClose = cb::close,
        footer = {
            when (ui.step) {
                ImportStep.SOURCES -> {
                    NeonText("Files stay where they are; Kororythm only indexes them.", Modifier.weight(1f), size = 10.sp, color = Neon.Dim, glow = null, maxLines = 2)
                    NeonButton(if (ui.sources.isEmpty()) "ADD A SOURCE" else "START SCAN ▸", { if (ui.sources.isNotEmpty()) cb.startScan() }, active = ui.sources.isNotEmpty(), accent = Neon.Orange)
                }
                ImportStep.SCANNING -> {
                    NeonText("You can leave this screen; the scan keeps going.", Modifier.weight(1f), size = 10.sp, color = Neon.Dim, glow = null, maxLines = 2)
                }
                ImportStep.REVIEW -> {
                    Spacer(Modifier.weight(1f))
                    NeonButton("FINISH ▸", cb::finishReview, active = true, accent = Neon.Orange)
                }
                ImportStep.DONE -> {
                    NeonButton("SCAN AGAIN", cb::restart)
                    Spacer(Modifier.weight(1f))
                    NeonButton("OPEN LIBRARY ▸", cb::openLibrary, active = true, accent = Neon.Orange)
                }
            }
        },
    ) {
        when (ui.step) {
            ImportStep.SOURCES -> SourcesStep(ui, cb)
            ImportStep.SCANNING -> ScanningStep(ui)
            ImportStep.REVIEW -> ReviewStep(ui, cb)
            ImportStep.DONE -> DoneStep(ui)
        }
    }
}

// ---------------------------------------------------------------- 1. sources

@Composable
private fun SourcesStep(ui: ImportUi, cb: ImportCallbacks) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NeonText("Where is your music?", size = 16.sp, weight = FontWeight.Bold)
        ui.error?.let { NeonText(it, size = 11.sp, color = Neon.Orange, glow = null, maxLines = 2) }
        ChoiceCard(
            title = "PHONE MUSIC LIBRARY",
            body = "Every FLAC and MP3 Android already knows about. Asks once for permission to read your music.",
            selected = ui.hasPhoneLibrary,
            onClick = { cb.togglePhoneLibrary(!ui.hasPhoneLibrary) },
            trailing = { NeonCheck(ui.hasPhoneLibrary, Modifier.padding(start = 10.dp), accent = Neon.Cyan) },
        )
        ChoiceCard(
            title = "+ ADD A FOLDER",
            body = "Pick a folder on the phone or SD card. Sub-folders are included, and new files are picked up on later scans.",
            onClick = cb::addFolder,
        )
        if (ui.folders.isNotEmpty()) {
            NeonText("FOLDERS", Modifier.padding(top = 4.dp), size = 9.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
            ui.folders.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().background(Neon.Panel, RoundedCornerShape(8.dp)).neonBorder(Neon.Cyan, 8.dp, 0.5f).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeonText("▤", size = 14.sp, color = Neon.Orange, glow = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        NeonText(f.label, size = 12.sp)
                        NeonText(f.lastScanAt?.let { "Scanned before" } ?: "Not scanned yet", size = 9.sp, color = Neon.Dim, glow = null)
                    }
                    NeonButton("REMOVE", { cb.removeFolder(f) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 2. scanning

@Composable
private fun ScanningStep(ui: ImportUi) {
    val p = ui.progress
    val fraction = if (p.found > 0) p.processed.toFloat() / p.found else 0f
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(10.dp))
        ScanRadar(Modifier.size(240.dp))
        Spacer(Modifier.height(16.dp))
        NeonText("UPLOADING TO THE GRID", size = 14.sp, weight = FontWeight.Bold, spacing = 3.sp)
        Spacer(Modifier.height(6.dp))
        NeonText(
            if (p.found == 0) "Looking for FLAC and MP3 files…" else "${p.processed} of ${p.found} files",
            size = 12.sp, color = Neon.Cyan,
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Neon.Cyan.copy(alpha = 0.15f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Neon.Orange, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(8.dp))
        NeonText(p.current, size = 10.sp, color = Neon.Dim, glow = null)
    }
}

/** Radar sweep over a polar grid, with blips that light up as the beam passes. */
@Composable
fun ScanRadar(modifier: Modifier = Modifier, angleOverride: Float? = null) {
    val spin by rememberInfiniteTransition(label = "radar").animateFloat(
        0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    val angle = angleOverride ?: spin
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        for (i in 1..4) drawCircle(Neon.Cyan.copy(alpha = 0.10f + 0.05f * i), r * i / 4, c, style = Stroke(1.dp.toPx()))
        for (k in 0 until 12) {
            val a = (k * 30.0) * PI / 180
            drawLine(Neon.Cyan.copy(alpha = 0.12f), c, c + Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat()), 1.dp.toPx())
        }
        // beam: fading wedge behind the leading edge
        for (t in 0 until 40) {
            val a = (angle - t * 1.5f) * PI.toFloat() / 180f
            val alpha = (1f - t / 40f) * 0.35f
            drawLine(Neon.Cyan.copy(alpha = alpha), c, c + Offset(cos(a) * r, sin(a) * r), 3.dp.toPx())
        }
        val lead = angle * PI.toFloat() / 180f
        drawLine(Neon.Ink, c, c + Offset(cos(lead) * r, sin(lead) * r), 2.dp.toPx(), StrokeCap.Round)
        // blips at fixed spots; bright just after the beam passes them
        val blips = listOf(40f to 0.35f, 110f to 0.7f, 165f to 0.5f, 230f to 0.85f, 300f to 0.3f, 335f to 0.6f)
        blips.forEach { (deg, dist) ->
            val since = ((angle - deg) % 360 + 360) % 360
            val glow = (1f - since / 300f).coerceIn(0f, 1f)
            val a = deg * PI.toFloat() / 180f
            val pos = c + Offset(cos(a) * r * dist, sin(a) * r * dist)
            drawCircle(Neon.Orange.copy(alpha = 0.25f * glow), 9.dp.toPx(), pos)
            drawCircle(Neon.Orange.copy(alpha = 0.2f + 0.8f * glow), 3.dp.toPx(), pos)
        }
        drawCircle(Neon.Cyan, r, c, style = Stroke(1.5.dp.toPx()))
    }
}

// ---------------------------------------------------------------- 3. review

@Composable
private fun ReviewStep(ui: ImportUi, cb: ImportCallbacks) {
    Column(Modifier.fillMaxSize()) {
        NeonText("${ui.review.size} tracks need tags", size = 16.sp, weight = FontWeight.Bold)
        NeonText(
            "Their title came from the file name, or the artist or album is empty. Find tags on MusicBrainz, or skip.",
            Modifier.padding(top = 4.dp, bottom = 8.dp), size = 11.sp, color = Neon.Dim, glow = null, maxLines = 3,
        )
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.review, key = { it.id }) { t -> ReviewCard(t, ui.suggestions[t.id], cb) }
        }
    }
}

@Composable
private fun ReviewCard(t: TrackEntity, s: SuggestState?, cb: ImportCallbacks) {
    Column(
        Modifier.fillMaxWidth().background(Neon.Panel, RoundedCornerShape(10.dp)).neonBorder(Neon.Cyan, 10.dp, 0.5f).padding(12.dp),
    ) {
        NeonText(t.title, size = 13.sp, weight = FontWeight.Bold)
        Row(Modifier.padding(top = 2.dp)) {
            Field("ARTIST", t.artist)
            Spacer(Modifier.width(12.dp))
            Field("ALBUM", t.album)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NeonButton(if (s?.loading == true) "SEARCHING…" else "FIND TAGS", { if (s?.loading != true) cb.findTags(t) }, accent = Neon.Orange)
            NeonButton("SKIP", { cb.skip(t) })
        }
        s?.error?.let { NeonText(it, Modifier.padding(top = 6.dp), size = 10.sp, color = Neon.Orange, glow = null) }
        s?.items?.forEach { sug ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).background(Neon.Lcd, RoundedCornerShape(6.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    NeonText(sug.title, size = 12.sp, color = Neon.LcdText, glow = null)
                    NeonText(listOfNotNull(sug.artist, sug.album, sug.year?.toString()).joinToString(" · "), size = 10.sp, color = Neon.LcdText.copy(alpha = 0.65f), glow = null)
                }
                NeonButton("USE", { cb.apply(t, sug) }, accent = Neon.Orange)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Row {
        NeonText("$label ", size = 10.sp, color = Neon.Dim, glow = null)
        NeonText(value ?: "missing", size = 10.sp, color = if (value == null) Neon.Orange else Neon.Ink, glow = null)
    }
}

// ---------------------------------------------------------------- 4. done

@Composable
private fun DoneStep(ui: ImportUi, progressOverride: Float? = null) {
    val r = ui.result
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        UploadComplete(Modifier.size(220.dp), progressOverride)
        NeonText("UPLOAD COMPLETE", size = 18.sp, weight = FontWeight.Bold, spacing = 4.sp, glow = Neon.Orange)
        NeonText("${ui.libraryCount} tracks in your library", Modifier.padding(top = 4.dp), size = 12.sp, color = Neon.Cyan)
        Spacer(Modifier.height(16.dp))
        if (r != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("NEW", r.added, Neon.Orange)
                Stat("UPDATED", r.updated, Neon.Cyan)
                Stat("SAME", r.unchanged, Neon.Dim)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("DUPLICATES", r.duplicates, Neon.Dim)
                Stat("REMOVED", r.removed, Neon.Dim)
                Stat("UNREADABLE", r.failed, if (r.failed > 0) Neon.Orange else Neon.Dim)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        NeonText("$value", size = 22.sp, weight = FontWeight.Bold, color = color, glow = color)
        NeonText(label, size = 9.sp, color = Neon.Dim, glow = null, spacing = 1.5.sp)
    }
}

/** Grid squares pulse outward, then a check mark draws itself. */
@Composable
fun UploadComplete(modifier: Modifier = Modifier, progressOverride: Float? = null) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { if (progressOverride == null) anim.animateTo(1f, tween(1600, easing = FastOutSlowInEasing)) }
    val v = progressOverride ?: anim.value
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val maxR = size.minDimension / 2
        for (i in 0 until 4) {
            val p = ((v - i * 0.12f) / 0.6f).coerceIn(0f, 1f)
            if (p <= 0f) continue
            val half = maxR * (0.35f + 0.65f * p) * (1f - i * 0.15f)
            val a = (1f - p * 0.7f)
            drawRect(Neon.Cyan.copy(alpha = 0.6f * a), Offset(c.x - half, c.y - half), Size(half * 2, half * 2), style = Stroke(1.5.dp.toPx()))
        }
        val box = maxR * 0.42f
        drawRect(
            Brush.radialGradient(listOf(Neon.Orange.copy(alpha = 0.35f), Color.Transparent), c, box * 1.6f),
            Offset(c.x - box * 1.6f, c.y - box * 1.6f), Size(box * 3.2f, box * 3.2f),
        )
        drawRect(Neon.Orange, Offset(c.x - box, c.y - box), Size(box * 2, box * 2), style = Stroke(2.5.dp.toPx()))
        val ck = ((v - 0.45f) / 0.45f).coerceIn(0f, 1f)
        val p1 = c + Offset(-box * 0.5f, 0f)
        val p2 = c + Offset(-box * 0.12f, box * 0.4f)
        val p3 = c + Offset(box * 0.55f, -box * 0.45f)
        val firstLeg = (ck / 0.4f).coerceIn(0f, 1f)
        val secondLeg = ((ck - 0.4f) / 0.6f).coerceIn(0f, 1f)
        if (firstLeg > 0f) drawLine(Neon.Ink, p1, p1 + (p2 - p1) * firstLeg, 4.dp.toPx(), StrokeCap.Round)
        if (secondLeg > 0f) drawLine(Neon.Ink, p2, p2 + (p3 - p2) * secondLeg, 4.dp.toPx(), StrokeCap.Round)
    }
}

/** For screenshot tests: the Done step with the animation finished. */
@Composable
fun ImportDonePreview(ui: ImportUi) {
    WizardFrame("UPLOAD TO THE GRID", STEPS, ImportStep.DONE.ordinal, {}, footer = {
        NeonButton("SCAN AGAIN", {})
        Spacer(Modifier.weight(1f))
        NeonButton("OPEN LIBRARY ▸", {}, active = true, accent = Neon.Orange)
    }) { DoneStep(ui, progressOverride = 1f) }
}
