package com.bdfutsal.campro.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bdfutsal.campro.AppViewModel
import com.bdfutsal.campro.camera.RecPhase
import com.bdfutsal.campro.data.QualityOption
import com.bdfutsal.campro.data.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CaptureScreen(
    vm: AppViewModel,
    now: Long,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onGoSetup: () -> Unit,
) {
    val cam by vm.camera.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    val busy = cam.phase == RecPhase.RECORDING || cam.phase == RecPhase.ARMED || cam.phase == RecPhase.FINALIZING
    val (pillText, pillColor) = when (cam.phase) {
        RecPhase.OFF -> "STANDBY" to Bd.Muted
        RecPhase.READY -> "READY" to Bd.Accent
        RecPhase.ARMED -> "ARMED" to Bd.Warn
        RecPhase.RECORDING -> "RECORDING" to Bd.Danger
        RecPhase.FINALIZING -> "SAVING" to Bd.Warn
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading("01 / FIELD CAPTURE", "Camera station") {
            Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { Pill(pillText, pillColor) }
        }

        BoxWithConstraints {
            val wide = maxWidth > 640.dp
            val previewBlock = @Composable { m: Modifier ->
                Column(m, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreviewPanel(vm, cam.phase, hasCameraPermission, landscape, settings.role, cam.activeQuality, cam.zoom, cam.elapsedMs, cam.armedForMs, now)
                    Transport(
                        phase = cam.phase,
                        hasPermission = hasCameraPermission,
                        canArm = session?.startEpochMs?.let { it > now } == true,
                        onEnable = onRequestPermission,
                        onRecord = vm::recordNow,
                        onArm = vm::arm,
                        onDisarm = vm.camera::disarm,
                        onStop = vm.camera::stop,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            cam.message,
                            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = Bd.Muted,
                        )
                        Text(formatBytes(cam.bytes), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Bd.Muted)
                    }
                }
            }
            val settingsBlock = @Composable { m: Modifier ->
                SettingsCard(
                    modifier = m,
                    vm = vm,
                    busy = busy,
                    role = settings.role,
                    quality = settings.quality,
                    supported = cam.supportedQualities.ifEmpty { QualityOption.entries },
                    zoomPresets = cam.zoomPresets,
                    zoom = cam.zoom,
                    mic = settings.mic,
                    beep = settings.beep,
                    freeBytes = library.freeBytes,
                    sessionName = session?.name,
                    sessionCode = session?.code,
                    sessionStart = session?.startEpochMs,
                    onGoSetup = onGoSetup,
                )
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    previewBlock(Modifier.weight(1.6f))
                    settingsBlock(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    previewBlock(Modifier.fillMaxWidth())
                    settingsBlock(Modifier.fillMaxWidth())
                }
            }
        }

        Notice(
            "Before a real match:",
            "Mount the phone the way you want the video oriented before recording. Keep this app open (the screen stays awake by itself), " +
                "turn on Do Not Disturb so calls can't interrupt, plug in a power bank, and make a 30-second test recording. " +
                "Clips save straight to Movies/BDFutsalCam in your gallery.",
        )
    }
}

@Composable
private fun PreviewPanel(
    vm: AppViewModel,
    phase: RecPhase,
    hasPermission: Boolean,
    landscape: Boolean,
    role: Role,
    quality: QualityOption?,
    zoom: Float,
    elapsedMs: Long,
    armedFor: Long?,
    now: Long,
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(previewView) {
        val p = vm.camera.previewUseCase()
        p.setSurfaceProvider(previewView.surfaceProvider)
        onDispose { p.setSurfaceProvider(null) }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (landscape) 16f / 9f else 3f / 4f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .border(1.dp, if (phase == RecPhase.RECORDING) Bd.Danger else Bd.Outline, RoundedCornerShape(16.dp)),
    ) {
        if (hasPermission) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("+", color = Bd.Accent, fontSize = 40.sp)
                Kicker("CAMERA NOT CONNECTED", Bd.Text)
                Text("Tap Enable camera to grant access", color = Bd.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Badge("CAM ${role.camNumber} · ${role.label}")
            Badge(quality?.let { "${it.label} · ${formatZoom(zoom)}" } ?: "READY FOR SETUP", faint = true)
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            when (phase) {
                RecPhase.RECORDING -> Badge("● REC", color = Bd.Danger)
                RecPhase.ARMED -> Badge("◷ ARMED", color = Bd.Warn)
                RecPhase.READY -> Badge("● LIVE", color = Bd.Accent)
                RecPhase.FINALIZING -> Badge("SAVING…", color = Bd.Warn)
                RecPhase.OFF -> Badge("● OFFLINE", faint = true)
            }
            val clock = if (phase == RecPhase.ARMED && armedFor != null) "T-" + formatCountdown(armedFor - now) else formatDuration(elapsedMs)
            Badge(clock)
        }
    }
}

@Composable
private fun Badge(text: String, faint: Boolean = false, color: Color = Bd.Text) = Text(
    text,
    Modifier
        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp),
    color = if (faint) Bd.Muted else color,
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Transport(
    phase: RecPhase,
    hasPermission: Boolean,
    canArm: Boolean,
    onEnable: () -> Unit,
    onRecord: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onStop: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hasPermission) GhostButton("◉ Enable camera", onEnable)
        PrimaryButton("● Record now", onRecord, enabled = phase == RecPhase.READY)
        if (phase == RecPhase.ARMED) {
            GhostButton("✕ Disarm", onDisarm)
        } else {
            GhostButton("◷ Arm timed start", onArm, enabled = phase == RecPhase.READY && canArm)
        }
        PrimaryButton("■ Stop & save", onStop, enabled = phase == RecPhase.RECORDING || phase == RecPhase.ARMED, danger = true)
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier,
    vm: AppViewModel,
    busy: Boolean,
    role: Role,
    quality: QualityOption,
    supported: List<QualityOption>,
    zoomPresets: List<Float>,
    zoom: Float,
    mic: Boolean,
    beep: Boolean,
    freeBytes: Long,
    sessionName: String?,
    sessionCode: String?,
    sessionStart: Long?,
    onGoSetup: () -> Unit,
) = BdCard(modifier) {
    Kicker("CAMERA CONFIGURATION", Bd.Muted)

    FieldLabel("This phone's role")
    Segmented(Role.entries, role, { if (it == Role.LEFT) "◀ LEFT" else "RIGHT ▶" }, vm::setRole, enabled = !busy)

    FieldLabel("Recording quality")
    Segmented(supported, quality.takeIf { it in supported } ?: supported.last(), { it.label }, vm::setQuality, enabled = !busy)
    val minutes = if (freeBytes > 0) (freeBytes * 8 / (quality.approxMbps * 1e6) / 60).toInt() else null
    Text(
        "${quality.detail}" + (minutes?.let { " · about $it min of space at this quality" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = Bd.Muted,
    )

    if (zoomPresets.size > 1) {
        FieldLabel("Lens / zoom")
        Segmented(zoomPresets, zoomPresets.minBy { kotlin.math.abs(it - zoom) }, ::formatZoom, vm.camera::setZoom)
        if (zoomPresets.first() < 1f) {
            Text("Ultra-wide is available on this phone. Use the same zoom on both phones.", style = MaterialTheme.typography.bodySmall, color = Bd.Muted)
        }
    }

    ToggleRow("Microphone on", "Needed for clap / beep alignment", mic, vm::setMic, enabled = !busy)
    ToggleRow("Beep at timed start", "Phones side by side hear each other's beep: a shared sync point", beep, vm::setBeep, enabled = !busy)

    HorizontalDivider(color = Bd.Outline)
    Kicker("CURRENT MATCH SESSION", Bd.Muted)
    Text(sessionName ?: "No match configured", fontWeight = FontWeight.Bold)
    Text(sessionCode?.let { "Code $it" } ?: "Create one in Match setup", color = Bd.Muted, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth()) {
        Text("Shared start", Modifier.weight(1f), color = Bd.Muted, style = MaterialTheme.typography.bodySmall)
        Text(
            sessionStart?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "Not scheduled",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    TextButton(onClick = onGoSetup) { Text("Set up match ↗", color = Bd.Accent) }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).widthIn(min = 0.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Bd.Muted)
        }
        Switch(checked, onChange, enabled = enabled)
    }

fun formatZoom(z: Float): String = if (z < 1f) String.format(Locale.US, "%.1f×", z) else String.format(Locale.US, "%.0f×", z)
