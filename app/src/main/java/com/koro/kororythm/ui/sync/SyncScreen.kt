package com.koro.kororythm.ui.sync

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.sync.Flight
import com.koro.kororythm.sync.PairedPc
import com.koro.kororythm.sync.PcHello
import com.koro.kororythm.sync.SyncBus
import com.koro.kororythm.sync.SyncClient
import com.koro.kororythm.sync.SyncPhase
import com.koro.kororythm.sync.SyncPrefs
import com.koro.kororythm.sync.SyncProtocol
import com.koro.kororythm.sync.SyncStore
import com.koro.kororythm.sync.SyncUi
import com.koro.kororythm.sync.SyncWorker
import com.koro.kororythm.ui.common.NeonCheck
import com.koro.kororythm.ui.common.NeonScreen
import com.koro.kororythm.ui.common.NeonTextField
import com.koro.kororythm.ui.skin.Neon
import com.koro.kororythm.ui.skin.NeonButton
import com.koro.kororythm.ui.skin.NeonText
import com.koro.kororythm.ui.skin.drawParrot
import com.koro.kororythm.ui.skin.neonBorder
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Sync with a PC: find it, pair with the code it shows, then watch the songs travel. */
@Composable
fun SyncScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SyncStore(context) }
    var pcs by remember { mutableStateOf(store.pcs()) }
    var prefs by remember { mutableStateOf(store.prefs()) }
    val found = remember { mutableStateListOf<PcHello>() }
    var searching by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<PcHello?>(null) }
    var typing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ui by SyncBus.ui.collectAsStateWithLifecycle()
    val playlists by produceNames()

    fun search() {
        if (searching) return
        searching = true
        scope.launch {
            val list = runCatching { SyncClient.discover(2000) }.getOrDefault(emptyList())
            found.clear(); found.addAll(list)
            // A paired PC that moved to a new address keeps working.
            list.forEach { h -> store.pcs().firstOrNull { it.fp == h.fp && (it.host != h.host || it.port != h.port) }?.let { store.put(it.copy(host = h.host, port = h.port)) } }
            pcs = store.pcs()
            searching = false
        }
    }
    LaunchedEffect(Unit) { search() }
    fun save(p: SyncPrefs) { prefs = p; store.setPrefs(p); SyncWorker.schedule(context, p.auto) }

    NeonScreen(title = "SYNC", onBack = onClose, backLabel = "◂ LIBRARY", actions = {
        NeonButton(if (searching) "…" else "↻ FIND", { search() }, active = searching)
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            SyncStage(ui, searching && ui.phase == SyncPhase.IDLE, Modifier.fillMaxWidth().height(230.dp))
            Spacer(Modifier.height(8.dp))
            Caption(ui, searching, pcs.isEmpty())
            Spacer(Modifier.height(12.dp))

            Section("YOUR PCs")
            if (pcs.isEmpty()) NeonText("None paired yet. Open Kororythm on the PC ▸ menu ▸ Sync with phone ▸ PAIR A PHONE.", size = 11.sp, color = Neon.Dim, glow = null, maxLines = 3)
            pcs.forEach { pc ->
                val here = found.any { it.fp == pc.fp }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).neonBorder(Neon.Cyan, 8.dp, 0.5f).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NeonText(pc.name, size = 13.sp, weight = FontWeight.Bold)
                        NeonText(
                            (if (here) "Here · " else "Not seen · ") + (if (pc.lastSync > 0) "synced ${ago(pc.lastSync)}" else "never synced") + " · key ${pc.key}",
                            size = 10.sp, color = Neon.Dim, glow = null,
                        )
                    }
                    val busy = ui.phase == SyncPhase.COMPARING || ui.phase == SyncPhase.MOVING
                    NeonButton("SYNC", { SyncWorker.start(context, pc); }, accent = Neon.Orange, active = busy && ui.pc == pc.name)
                    Spacer(Modifier.width(6.dp))
                    NeonButton("✕", { store.forget(pc.fp); pcs = store.pcs() })
                }
            }
            val unpaired = found.filter { h -> pcs.none { it.fp == h.fp } }
            if (unpaired.isNotEmpty() || pcs.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Section("ON THIS NETWORK")
                if (unpaired.isEmpty()) NeonText(if (searching) "Looking…" else "No PC answered. Same Wi-Fi? Or type its address.", size = 11.sp, color = Neon.Dim, glow = null)
                unpaired.forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).neonBorder(Neon.Dim, 8.dp, 0.5f).clickable { pairing = h }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            NeonText(h.name, size = 13.sp, weight = FontWeight.Bold)
                            NeonText("${h.host} · key ${(h.fp.take(4) + " " + h.fp.drop(4).take(4)).uppercase()}", size = 10.sp, color = Neon.Dim, glow = null)
                        }
                        NeonButton("PAIR", { pairing = h }, accent = Neon.Orange)
                    }
                }
                Row(Modifier.padding(top = 6.dp)) { NeonButton("TYPE AN ADDRESS", { typing = true }) }
            }

            Spacer(Modifier.height(14.dp))
            Section("WHAT TO SYNC")
            Toggle("Everything from the PC", prefs.only.isEmpty()) { save(prefs.copy(only = if (it) emptyList() else playlists.take(1))) }
            if (prefs.only.isNotEmpty()) {
                NeonText("Only these playlists (as named on the PC):", size = 10.sp, color = Neon.Dim, glow = null)
                playlists.forEach { n ->
                    Toggle(n, n in prefs.only, indent = true) { on -> save(prefs.copy(only = if (on) prefs.only + n else prefs.only - n)) }
                }
            }
            Toggle("Send this phone's songs to the PC", prefs.upload) { save(prefs.copy(upload = it)) }
            Toggle("Hi-res FLAC arrives as 16-bit FLAC (smaller)", prefs.to16Bit) { save(prefs.copy(to16Bit = it)) }
            Toggle("Sync by itself on Wi-Fi while charging", prefs.auto) { save(prefs.copy(auto = it)) }
            Spacer(Modifier.height(8.dp))
            StorageBar(prefs.keepFree, ui) { save(prefs.copy(keepFree = it)) }
            Spacer(Modifier.height(10.dp))
            NeonText(
                "Over a cable: turn on USB tethering (Settings ▸ Network ▸ Hotspot & tethering) and tap ↻ FIND, or let the PC's Sync window set up the USB link (needs USB debugging).",
                size = 10.sp, color = Neon.Dim, glow = null, maxLines = 4,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    pairing?.let { h ->
        PairDialog(h, store, onDone = { pc -> pairing = null; pcs = store.pcs(); pc?.let { SyncWorker.start(context, it) } })
    }
    if (typing) AddressDialog(onCancel = { typing = false }) { host ->
        typing = false
        scope.launch {
            runCatching { SyncClient(host, SyncProtocol.PORT, null).let { c -> c.hello().copy(fp = c.seenFp ?: "") } }
                .onSuccess { if (it.fp.isNotEmpty()) pairing = it }
                .onFailure { SyncBus.ui.value = SyncUi(SyncPhase.ERROR, message = "No Kororythm at $host (${it.message})") }
        }
    }
}

@Composable
private fun produceNames(): androidx.compose.runtime.State<List<String>> {
    val s = remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        s.value = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.koro.kororythm.library.Library.dao.playlistsNow().map { it.name } }
        }.getOrDefault(emptyList())
    }
    return s
}

@Composable
private fun Section(t: String) {
    NeonText(t, Modifier.padding(bottom = 4.dp), size = 10.sp, color = Neon.Dim, glow = null, spacing = 3.sp)
}

@Composable
private fun Toggle(label: String, on: Boolean, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(start = if (indent) 18.dp else 0.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonCheck(on)
        Spacer(Modifier.width(10.dp))
        NeonText(label, Modifier.weight(1f), size = 12.sp, glow = null, maxLines = 2)
    }
}

/** Storage bar: used, what this sync adds, the reserve to keep free (tap −/+ to change it). */
@Composable
private fun StorageBar(keepFree: Long, ui: SyncUi, onChange: (Long) -> Unit) {
    val stat = remember { runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path) }.getOrNull() }
    val total = stat?.totalBytes ?: 1L
    val free = stat?.availableBytes ?: 0L
    val used = total - free
    val coming = if (ui.phase == SyncPhase.MOVING) (ui.totalBytes - ui.bytes).coerceAtLeast(0) else 0L
    Column(Modifier.fillMaxWidth().neonBorder(Neon.Dim, 8.dp, 0.5f).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeonText("PHONE STORAGE", Modifier.weight(1f), size = 10.sp, color = Neon.Dim, glow = null, spacing = 2.sp)
            NeonText("${gb(free)} free of ${gb(total)}", size = 10.sp, color = Neon.Dim, glow = null)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Neon.Dim.copy(alpha = 0.25f), cornerRadius = r)
            val u = size.width * used / total
            val c = size.width * coming / total
            val k = size.width * keepFree.coerceAtMost(total) / total
            drawRoundRect(Neon.Cyan.copy(alpha = 0.7f), size = Size(u, size.height), cornerRadius = r)
            drawRect(Neon.Orange, Offset(u, 0f), Size(c, size.height))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Neon.Dim.copy(alpha = 0.6f))), Offset(size.width - k, 0f), Size(k, size.height))
            drawLine(Neon.Ink, Offset(size.width - k, -2f), Offset(size.width - k, size.height + 2f), 2f)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeonText("Keep ${gb(keepFree)} free", Modifier.weight(1f), size = 11.sp, glow = null)
            NeonButton("−", { onChange((keepFree - (1L shl 30)).coerceAtLeast(512L shl 20)) })
            Spacer(Modifier.width(6.dp))
            NeonButton("+", { onChange((keepFree + (1L shl 30)).coerceAtMost(total / 2)) })
        }
    }
}

private fun gb(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else "${b shr 20} MB"

private fun ago(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

@Composable
private fun Caption(ui: SyncUi, searching: Boolean, none: Boolean) {
    val (head, sub) = when (ui.phase) {
        SyncPhase.IDLE -> (if (searching) "Looking for your PC…" else if (none) "Pair with your PC" else "Ready") to
            "Same Wi-Fi, USB tethering or a USB cable"
        SyncPhase.COMPARING -> "Talking to ${ui.pc}" to ui.message
        SyncPhase.MOVING -> (ui.current ?: "Syncing") to "${ui.done} / ${ui.total} · ${gb(ui.bytes)} of ${gb(ui.totalBytes)}"
        SyncPhase.DONE -> "All synced" to ui.message
        SyncPhase.ERROR -> "Sync stopped" to ui.message
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        NeonText(head, size = 15.sp, weight = FontWeight.Bold, color = if (ui.phase == SyncPhase.ERROR) Neon.Orange else Neon.Ink)
        NeonText(sub, size = 11.sp, color = Neon.Dim, glow = null, maxLines = 2)
    }
}

/**
 * The animation: a radar while looking; the PC and the phone joined by a dotted arc with
 * light running along it; album covers flying over the arc in the direction they go; a
 * progress ring; and, at the end, the parrot flying in and landing on the phone.
 */
@Composable
fun SyncStage(
    ui: SyncUi,
    searching: Boolean,
    modifier: Modifier = Modifier,
    frozenMs: Long? = null,
    demoFlights: List<Pair<Flight, Float>> = emptyList(),
    demoElapsed: Float? = null,
) {
    val flights = remember { mutableStateListOf<Pair<Flight, ImageBitmap?>>() }
    LaunchedEffect(Unit) {
        SyncBus.flights.collect { f ->
            val img = f.art?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
            flights.add(f to img)
            if (flights.size > 8) flights.removeAt(0)
        }
    }
    var now by remember { mutableLongStateOf(frozenMs ?: System.currentTimeMillis()) }
    if (frozenMs == null) LaunchedEffect(Unit) { while (true) withFrameMillis { now = System.currentTimeMillis() } }
    val start = remember(ui.phase) { now }
    val t = demoElapsed ?: ((now - start) / 1000f)
    val cyan = Neon.Cyan; val orange = Neon.Orange; val dim = Neon.Dim
    Canvas(modifier.background(Neon.Panel, RoundedCornerShape(10.dp)).neonBorder(cyan, 10.dp, 0.6f)) {
        val w = size.width; val h = size.height
        val pc = Offset(w * 0.2f, h * 0.62f); val ph = Offset(w * 0.8f, h * 0.62f)
        when {
            ui.phase == SyncPhase.IDLE || ui.phase == SyncPhase.ERROR -> radar(now / 1000f, cyan, dim, searching || ui.phase == SyncPhase.IDLE)
            else -> {
                val reach = min(1f, t / 0.7f)
                arc(pc, ph, reach, now / 1000f, cyan, dim, ui.phase == SyncPhase.MOVING)
                pcIcon(pc, cyan); phoneIcon(ph, cyan)
                val live = demoFlights.map { (f, p) -> Triple(f, null as ImageBitmap?, p) } +
                    flights.map { (f, img) -> Triple(f, img, (now - f.at) / 1400f) }.filter { it.third in 0f..1f }
                live.forEach { (f, img, p) -> cover(f.toPhone, img, p, pc, ph, cyan, orange) }
                if (ui.phase == SyncPhase.MOVING && ui.totalBytes > 0) ring(Offset(w / 2, h * 0.17f), (ui.bytes.toFloat() / ui.totalBytes).coerceIn(0f, 1f), cyan, dim)
                if (ui.phase == SyncPhase.DONE) landing(ph, t, orange)
            }
        }
    }
}

private fun DrawScope.radar(s: Float, c: Color, dim: Color, sweep: Boolean) {
    val ctr = Offset(size.width / 2, size.height / 2)
    val r = min(size.width, size.height) * 0.44f
    for (i in 1..3) drawCircle(dim.copy(alpha = 0.6f), r * i / 3, ctr, style = Stroke(1.5f))
    drawLine(dim.copy(alpha = 0.5f), Offset(ctr.x - r, ctr.y), Offset(ctr.x + r, ctr.y), 1f)
    drawLine(dim.copy(alpha = 0.5f), Offset(ctr.x, ctr.y - r), Offset(ctr.x, ctr.y + r), 1f)
    if (sweep) {
        val a = (s * 1.6f) % (2 * PI.toFloat())
        rotate(Math.toDegrees(a.toDouble()).toFloat() - 60f, ctr) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, c.copy(alpha = 0.45f)), ctr), 0f, 60f, true,
                Offset(ctr.x - r, ctr.y - r), Size(2 * r, 2 * r))
        }
        drawLine(c, ctr, Offset(ctr.x + cos(a) * r, ctr.y + sin(a) * r), 3f)
        val p = (s % 2f) / 2f
        drawCircle(c.copy(alpha = 1 - p), r * p, ctr, style = Stroke(2f))
    }
    phoneIcon(ctr, c, 0.85f)
}

private fun bezier(a: Offset, b: Offset, u: Float, lift: Float): Offset {
    val m = Offset((a.x + b.x) / 2, a.y - lift)
    val v = 1 - u
    return Offset(v * v * a.x + 2 * v * u * m.x + u * u * b.x, v * v * a.y + 2 * v * u * m.y + u * u * b.y)
}

private fun DrawScope.arc(a: Offset, b: Offset, reach: Float, s: Float, c: Color, dim: Color, busy: Boolean) {
    val lift = (b.x - a.x) * 0.45f
    val path = Path()
    var u = 0f
    while (u <= reach) { val p = bezier(a, b, u, lift); if (u == 0f) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y); u += 0.02f }
    drawPath(path, dim, style = Stroke(3f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 14f), -s * 60f)))
    if (busy) for (k in 0 until 3) {
        val p = bezier(a, b, (s * 0.6f + k / 3f) % 1f, lift)
        drawCircle(c.copy(alpha = 0.35f), 12f, p); drawCircle(c, 5f, p)
    }
}

private fun DrawScope.pcIcon(c: Offset, col: Color, k: Float = 1f) {
    val w = 120f * k; val h = 78f * k
    drawRoundRect(col, Offset(c.x - w / 2, c.y - h / 2 - 12 * k), Size(w, h), CornerRadius(6f), style = Stroke(4f))
    val p = Path().apply {
        moveTo(c.x - 18 * k, c.y + h / 2 - 12 * k); lineTo(c.x - 26 * k, c.y + h / 2 + 14 * k)
        lineTo(c.x + 26 * k, c.y + h / 2 + 14 * k); lineTo(c.x + 18 * k, c.y + h / 2 - 12 * k)
    }
    drawPath(p, col, style = Stroke(4f))
}

private fun DrawScope.phoneIcon(c: Offset, col: Color, k: Float = 1f) {
    drawRoundRect(col, Offset(c.x - 34 * k, c.y - 62 * k), Size(68 * k, 124 * k), CornerRadius(14 * k), style = Stroke(4f))
    drawLine(col, Offset(c.x - 12 * k, c.y - 50 * k), Offset(c.x + 12 * k, c.y - 50 * k), 4f, StrokeCap.Round)
}

private fun DrawScope.cover(toPhone: Boolean, img: ImageBitmap?, p: Float, pc: Offset, ph: Offset, c: Color, o: Color) {
    val e = if (p < 0.5f) 2 * p * p else 1 - (-2 * p + 2).pow(2) / 2
    val (a, b) = if (toPhone) pc to ph else ph to pc
    val pos = bezier(a, b, e, (ph.x - pc.x) * 0.55f)
    val s = 90f + 36f * sin(e * PI.toFloat())
    val alpha = min(1f, min(p * 6, (1 - p) * 6))
    rotate((e - 0.5f) * if (toPhone) 28f else -28f, pos) {
        translate(pos.x - s / 2, pos.y - s / 2) {
            drawRoundRect(c.copy(alpha = 0.35f * alpha), Offset(-6f, -6f), Size(s + 12, s + 12), CornerRadius(10f))
            if (img != null) drawImage(img, dstSize = IntSize(s.toInt(), s.toInt()), alpha = alpha, dstOffset = IntOffset.Zero)
            else {
                drawRoundRect(Brush.linearGradient(listOf(c.copy(alpha = alpha), o.copy(alpha = alpha))), size = Size(s, s), cornerRadius = CornerRadius(8f))
                drawCircle(Color.White.copy(alpha = 0.85f * alpha), s * 0.12f, Offset(s * 0.42f, s * 0.66f))
                drawLine(Color.White.copy(alpha = 0.85f * alpha), Offset(s * 0.53f, s * 0.66f), Offset(s * 0.53f, s * 0.26f), s * 0.06f)
                drawLine(Color.White.copy(alpha = 0.85f * alpha), Offset(s * 0.53f, s * 0.26f), Offset(s * 0.7f, s * 0.34f), s * 0.06f)
            }
        }
    }
}

private fun DrawScope.ring(c: Offset, f: Float, col: Color, dim: Color) {
    drawCircle(dim, 40f, c, style = Stroke(9f))
    drawArc(col, -90f, 360f * f, false, Offset(c.x - 40f, c.y - 40f), Size(80f, 80f), style = Stroke(9f, cap = StrokeCap.Round))
}

/** The parrot flies in from the left and lands on the phone; a tick pops when it is down. */
private fun DrawScope.landing(ph: Offset, s: Float, o: Color) {
    val p = min(1f, s / 1.1f)
    val e = 1 - (1 - p).pow(3)
    val side = 170f
    val x = ph.x - 360f * (1 - e) - side / 2
    val y = ph.y - 62f - side * 0.82f - 240f * (1 - e)
    val spread = if (p < 1f) 0.7f + 0.3f * sin(s * 26f) else 1f
    drawParrot(Offset(x, y), side, wingSpread = spread)
    if (p >= 1f) {
        val q = min(1f, (s - 1.1f) / 0.35f)
        val k = if (q < 1f) 0.6f + 0.6f * sin(q * PI.toFloat() * 0.75f) else 1f
        val c = Offset(ph.x + 46f, ph.y + 40f)
        drawCircle(o, 26f * k, c)
        val path = Path().apply { moveTo(c.x - 12 * k, c.y); lineTo(c.x - 3 * k, c.y + 9 * k); lineTo(c.x + 13 * k, c.y - 9 * k) }
        drawPath(path, Color.White, style = Stroke(6f * k, cap = StrokeCap.Round))
    }
}

@Composable
private fun PairDialog(h: PcHello, store: SyncStore, onDone: (PairedPc?) -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismiss = { onDone(null) }) {
        NeonText("PAIR WITH ${h.name.uppercase()}", size = 12.sp, weight = FontWeight.Bold, spacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        NeonText("Type the 6-digit code the PC's Sync window shows.", size = 11.sp, color = Neon.Dim, glow = null, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        NeonTextField(code, { v -> code = v.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), placeholder = "000000", numeric = true)
        error?.let { NeonText(it, Modifier.padding(top = 6.dp), size = 11.sp, color = Neon.Orange, glow = null, maxLines = 3) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeonButton("CANCEL", { onDone(null) })
            Spacer(Modifier.width(8.dp))
            NeonButton(if (busy) "…" else "PAIR", {
                if (code.length != 6 || busy) return@NeonButton
                busy = true; error = null
                scope.launch {
                    runCatching { SyncClient(h.host, h.port, h.fp).pair(code, store.deviceId, store.deviceName) }
                        .onSuccess { (token, hello) ->
                            val pc = PairedPc(hello.name, h.host, h.port, hello.fp, token)
                            store.put(pc); onDone(pc)
                        }
                        .onFailure { error = it.message ?: "Couldn't pair"; busy = false }
                }
            }, accent = Neon.Orange, active = code.length == 6)
        }
    }
}

@Composable
private fun AddressDialog(onCancel: () -> Unit, onOk: (String) -> Unit) {
    var host by remember { mutableStateOf("") }
    Dialog(onDismiss = onCancel) {
        NeonText("PC ADDRESS", size = 12.sp, weight = FontWeight.Bold, spacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        NeonText("The PC's Sync window lists it (for example 192.168.1.20).", size = 11.sp, color = Neon.Dim, glow = null, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        NeonTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), placeholder = "192.168.1.20")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeonButton("CANCEL", onCancel)
            Spacer(Modifier.width(8.dp))
            NeonButton("CONNECT", { if (host.isNotBlank()) onOk(host) }, accent = Neon.Orange)
        }
    }
}

@Composable
private fun Dialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth().background(Neon.Panel, RoundedCornerShape(12.dp)).neonBorder(Neon.Cyan, 12.dp, 0.8f)
                .clickable(remember { MutableInteractionSource() }, null) {}.padding(16.dp),
        ) { content() }
    }
}
