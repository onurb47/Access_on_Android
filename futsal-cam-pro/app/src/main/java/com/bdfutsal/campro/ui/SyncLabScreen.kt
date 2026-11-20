package com.bdfutsal.campro.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bdfutsal.campro.AppViewModel
import com.bdfutsal.campro.LabClip
import com.bdfutsal.campro.data.Clip
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class AudioSide(val label: String) { LEFT("L"), RIGHT("R"), BOTH("L+R"), OFF("Off") }

private const val FRAME = 1.0 / 30.0

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncLabScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val lab by vm.lab.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()

    val left = remember { ExoPlayer.Builder(context).build() }
    val right = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { left.release(); right.release() } }

    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableFloatStateOf(-1f) }
    var audio by rememberSaveable { mutableStateOf(AudioSide.LEFT) }

    LaunchedEffect(lab.left?.uri) { load(left, lab.left?.uri) }
    LaunchedEffect(lab.right?.uri) { load(right, lab.right?.uri) }
    LaunchedEffect(audio) {
        left.volume = if (audio == AudioSide.LEFT || audio == AudioSide.BOTH) 1f else 0f
        right.volume = if (audio == AudioSide.RIGHT || audio == AudioSide.BOTH) 1f else 0f
    }
    DisposableEffect(left) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        left.addListener(l)
        onDispose { left.removeListener(l) }
    }

    val offMs = (lab.offsetSec * 1000).toLong()
    // Keep the right player locked to the left one while playing, and refresh the scrubber.
    LaunchedEffect(playing, offMs) {
        while (true) {
            position = left.currentPosition
            duration = left.duration.coerceAtLeast(0)
            if (playing) {
                val expected = left.currentPosition - offMs
                if (expected >= 0 && abs(right.currentPosition - expected) > 120) right.seekTo(expected)
            }
            delay(250)
        }
    }

    fun alignRight() { right.seekTo((left.currentPosition - offMs).coerceAtLeast(0)) }
    fun setOffset(sec: Double) {
        vm.setOffset((sec * 1000).roundToInt() / 1000.0)
        if (!playing) right.seekTo((left.currentPosition - (sec * 1000).toLong()).coerceAtLeast(0))
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(alignmentJson(lab).toByteArray()) }
            }.isSuccess
            Toast.makeText(context, if (ok) "Alignment saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading("04 / PRE-STITCH WORKSPACE", "Two-camera sync lab") { Pill("PREVIEW ONLY", dot = false) }
        Text(
            "Load the LEFT and RIGHT clips, then shift the offset until a clap, pass or whistle lines up. " +
                "This aligns playback for review; it doesn't stitch or export a merged video yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = Bd.Muted,
        )

        BoxWithConstraints {
            val wide = maxWidth > 560.dp
            val l = @Composable { m: Modifier -> Slot(m, "CAM 01 / LEFT", left, lab.left, library.clips) { vm.setLabClip(false, it) } }
            val r = @Composable { m: Modifier -> Slot(m, "CAM 02 / RIGHT", right, lab.right, library.clips) { vm.setLabClip(true, it) } }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { l(Modifier.weight(1f)); r(Modifier.weight(1f)) }
            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { l(Modifier.fillMaxWidth()); r(Modifier.fillMaxWidth()) }
        }

        BdCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker("MANUAL ALIGNMENT")
                    Text("Right camera start offset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Positive = right camera started later.", style = MaterialTheme.typography.bodySmall, color = Bd.Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(String.format(Locale.US, "%+.3fs", lab.offsetSec), fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Black, color = Bd.Accent)
                    Text(String.format(Locale.US, "%+d frames @30", (lab.offsetSec / FRAME).roundToInt()), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Bd.Muted)
                }
            }
            val coarse = 15.0
            Slider(
                value = lab.offsetSec.coerceIn(-coarse, coarse).toFloat(),
                onValueChange = { setOffset(it.toDouble()) },
                valueRange = -coarse.toFloat()..coarse.toFloat(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GhostButton("−1s", { setOffset(lab.offsetSec - 1) })
                GhostButton("−1f", { setOffset(lab.offsetSec - FRAME) })
                GhostButton("+1f", { setOffset(lab.offsetSec + FRAME) })
                GhostButton("+1s", { setOffset(lab.offsetSec + 1) })
                GhostButton("0", { setOffset(0.0) })
            }
            vm.suggestedOffset()?.let { s ->
                GhostButton(String.format(Locale.US, "Use recorded start times (%+.3fs)", s), { setOffset(s) }, Modifier.fillMaxWidth())
            }
            Text("Slider covers ±15 s; the buttons go up to ±${AppViewModel.OFFSET_LIMIT.toInt()} s.", style = MaterialTheme.typography.bodySmall, color = Bd.Muted)

            FieldLabel("Audio from")
            Segmented(AudioSide.entries, audio, { it.label }, { audio = it })

            if (duration > 0) {
                Slider(
                    value = if (scrub >= 0) scrub else position.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = {
                        scrub = it
                        left.pause(); right.pause()
                    },
                    onValueChangeFinished = {
                        left.seekTo(scrub.toLong())
                        right.seekTo((scrub.toLong() - offMs).coerceAtLeast(0))
                        scrub = -1f
                    },
                    valueRange = 0f..duration.toFloat(),
                )
                Text(formatDuration(position) + " / " + formatDuration(duration), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Bd.Muted)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(if (playing) "Ⅱ Pause" else "▶ Play together", {
                    if (playing) {
                        left.pause(); right.pause(); alignRight()
                    } else {
                        var l = left.currentPosition
                        var r = l - offMs
                        if (r < 0) { l -= r; r = 0 }
                        left.seekTo(l); right.seekTo(r)
                        left.play(); right.play()
                    }
                }, Modifier.weight(1f), enabled = lab.left != null && lab.right != null)
                GhostButton("↓ Export", {
                    exportLauncher.launch("bd-alignment-${System.currentTimeMillis() / 1000}.json")
                }, Modifier.weight(1f), enabled = lab.left != null && lab.right != null)
            }
            Text(
                when {
                    lab.left == null || lab.right == null -> "Choose both recordings to begin comparison."
                    else -> "Tip: pause on the clap in the left clip, then nudge the offset frame by frame until the right clip shows the same moment."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Bd.Muted,
            )
        }

        Roadmap()
    }
}

@Composable
private fun Slot(
    modifier: Modifier,
    title: String,
    player: ExoPlayer,
    clip: LabClip?,
    library: List<Clip>,
    onPick: (Uri) -> Unit,
) = BdCard(modifier) {
    Kicker(title, Bd.Muted)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false; this.player = player } },
            modifier = Modifier.fillMaxSize(),
        )
    }
    Text(clip?.name ?: "No file selected", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Bd.Muted)

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPick) }
    var open by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            GhostButton("From library ▾", { open = true }, Modifier.fillMaxWidth(), enabled = library.isNotEmpty())
            DropdownMenu(open, { open = false }) {
                library.forEach { c ->
                    DropdownMenuItem(
                        text = { Text((c.meta?.role?.label?.let { "$it · " } ?: "") + c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { open = false; onPick(c.uri) },
                    )
                }
            }
        }
        GhostButton("＋ Files", { fileLauncher.launch(arrayOf("video/*")) }, Modifier.weight(1f))
    }
}

@Composable
private fun Roadmap() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(
        Triple("AVAILABLE NOW", "Capture + clip organisation", "Record on each phone, shared timed start with beep, clips in your gallery, manual alignment."),
        Triple("NEXT BUILD", "Automatic alignment + stitching", "Audio clap/beep correlation, lens calibration, overlap feature matching and panoramic export."),
        Triple("FUTURE", "AI match director", "Ball/player detection, virtual pan-and-zoom, event tagging and goal/shot highlights."),
    ).forEachIndexed { i, (tag, title, body) ->
        BdCard(Modifier.fillMaxWidth()) {
            Kicker(tag, if (i == 0) Bd.Accent else Bd.Muted)
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Bd.Muted)
        }
    }
}

private fun load(p: ExoPlayer, uri: Uri?) {
    p.stop()
    if (uri == null) { p.clearMediaItems(); return }
    p.setMediaItem(MediaItem.fromUri(uri))
    p.prepare()
}

private fun alignmentJson(lab: com.bdfutsal.campro.LabState): String {
    fun side(c: LabClip?) = JSONObject().apply {
        put("name", c?.name)
        put("uri", c?.uri?.toString())
        c?.meta?.let {
            put("role", it.role.name)
            put("recordedStartEpochMs", it.actualStartMs)
            put("scheduledStartEpochMs", it.scheduledStartMs)
            put("matchName", it.matchName)
            put("sessionCode", it.sessionCode)
        }
    }
    return JSONObject()
        .put("app", "BD Futsal Cam Pro (Android)")
        .put("version", 1)
        .put("exportedAt", System.currentTimeMillis())
        .put("rightOffsetSeconds", lab.offsetSec)
        .put("rightOffsetFrames30", (lab.offsetSec / FRAME).roundToInt())
        .put("left", side(lab.left))
        .put("right", side(lab.right))
        .toString(2)
}
