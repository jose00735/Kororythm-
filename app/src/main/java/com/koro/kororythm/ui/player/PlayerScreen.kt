package com.koro.kororythm.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koro.kororythm.AudioBus
import com.koro.kororythm.devices.DeviceEvent
import com.koro.kororythm.devices.OutputKind
import com.koro.kororythm.devices.OutputDevice
import com.koro.kororythm.dsp.EqSettings
import com.koro.kororythm.playback.PlayerActions
import com.koro.kororythm.playback.PlayerUiState
import com.koro.kororythm.playback.PlayerViewModel
import com.koro.kororythm.ui.viz.VizState
import com.koro.kororythm.ui.anim.DeviceOverlay
import com.koro.kororythm.ui.skin.GridBackground
import com.koro.kororythm.ui.viz.rememberVizState

/**
 * The player screen: Winamp's main, EQ and playlist panels stacked on a phone, side by side in
 * landscape / on tablets. The grid draws itself on launch, then the panels fade in.
 */
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onAddFiles: () -> Unit,
    onOpenLibrary: () -> Unit = {},
    onEditTags: (String) -> Unit = {},
    onOpenDj: () -> Unit = {},
    onLyrics: () -> Unit = {},
    onSaveQueue: (List<String>) -> Unit = {},
    viz: VizState = rememberVizState(),
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val eq by AudioBus.eq.collectAsStateWithLifecycle()
    val output by AudioBus.output.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    val boot = remember { Animatable(0f) }
    LaunchedEffect(Unit) { boot.animateTo(1f, tween(1400)) }

    var overlay by remember { mutableStateOf<DeviceEvent?>(null) }
    LaunchedEffect(Unit) {
        AudioBus.deviceEvents.collect { e ->
            if (e is DeviceEvent.Plugged && e.device.kind == OutputKind.USB_DAC) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            overlay = e
        }
    }

    Box(Modifier.fillMaxSize()) {
        val hooks = LayoutHooks(onOpenLibrary = onOpenLibrary, onLyrics = onLyrics)
        when (LayoutState.current) {
            PlayerLayoutId.CLASSIC -> PlayerLayout(st, eq, output, viz, vm, onAddFiles, boot.value, onOpenLibrary, onEditTags, onOpenDj, onLyrics, onSaveQueue)
            PlayerLayoutId.COVER -> CoverLayout(st, vm, hooks)
            PlayerLayoutId.POD -> PodLayout(st, vm, hooks)
            PlayerLayoutId.PRISM -> PrismLayout(st, vm, viz, hooks)
        }
        if (LayoutState.current != PlayerLayoutId.POD) com.koro.kororythm.ui.pod.BrowseSheet(st, vm)
        StyleSheet()
        overlay?.let { e ->
            key(e) { DeviceOverlay(e) { if (overlay === e) overlay = null } }
        }
    }
}

/** The panels on the grid, without any live player wiring (also used for screenshot tests). */
@Composable
fun PlayerLayout(
    st: PlayerUiState,
    eq: EqSettings,
    output: OutputDevice,
    viz: VizState,
    actions: PlayerActions,
    onAddFiles: () -> Unit,
    boot: Float = 1f,
    onOpenLibrary: () -> Unit = {},
    onEditTags: (String) -> Unit = {},
    onOpenDj: () -> Unit = {},
    onLyrics: () -> Unit = {},
    onSaveQueue: (List<String>) -> Unit = {},
) {
    var mainShade by rememberSaveable { mutableStateOf(false) }
    var eqShade by rememberSaveable { mutableStateOf(false) }
    var listShade by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        GridBackground(boot)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(10.dp)
                .graphicsLayer { alpha = ((boot - 0.45f) / 0.55f).coerceIn(0f, 1f) },
        ) {
            val wide = maxWidth > 640.dp
            val main: @Composable (Modifier) -> Unit = { m ->
                MainPanel(st, viz, output, actions, onAddFiles, mainShade, { mainShade = !mainShade }, m, onEditTags, onOpenDj, onLyrics)
            }
            val eqPanel: @Composable (Modifier) -> Unit = { m ->
                EqPanel(eq, actions, eqShade, { eqShade = !eqShade }, m)
            }
            val list: @Composable (Modifier) -> Unit = { m ->
                PlaylistPanel(st, actions, onAddFiles, listShade, { listShade = !listShade }, m, onSaveQueue)
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Header(output, onOpenLibrary = onOpenLibrary)
                        main(Modifier.fillMaxWidth())
                        eqPanel(Modifier.fillMaxWidth())
                    }
                    list(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Header(output, onOpenLibrary = onOpenLibrary)
                    main(Modifier.fillMaxWidth())
                    eqPanel(Modifier.fillMaxWidth())
                    list(if (listShade) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}
