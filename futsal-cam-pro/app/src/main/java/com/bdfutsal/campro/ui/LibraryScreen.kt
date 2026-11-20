package com.bdfutsal.campro.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bdfutsal.campro.AppViewModel
import com.bdfutsal.campro.data.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(vm: AppViewModel, onOpenLab: () -> Unit) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<Clip?>(null) }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) vm.refreshLibrary()
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading("03 / YOUR RECORDINGS", "Match library") {
            GhostButton("↻ Refresh", vm::refreshLibrary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("CLIPS", lib.clips.size.toString(), Modifier.weight(1f))
            Stat("RECORDED", formatBytes(lib.clips.sumOf { it.sizeBytes }), Modifier.weight(1f))
            Stat("FREE SPACE", if (lib.freeBytes > 0) formatBytes(lib.freeBytes) else "—", Modifier.weight(1f))
        }

        if (lib.clips.isEmpty()) {
            BdCard(Modifier.fillMaxWidth()) {
                Text("▣", fontSize = 28.sp, color = Bd.Accent)
                Text("Your clips will appear here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Make a short recording in Capture. Clips from this app in Movies/BDFutsalCam are listed here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Bd.Muted,
                )
            }
        }
        lib.clips.forEach { clip ->
            ClipCard(
                clip = clip,
                onPlay = { play(context, clip) },
                onShare = { shareClip(context, clip) },
                onLeft = { vm.setLabClip(false, clip.uri); onOpenLab() },
                onRight = { vm.setLabClip(true, clip.uri); onOpenLab() },
                onDelete = { toDelete = clip },
            )
        }

        Notice(
            "Your clips are ordinary video files.",
            "They're saved in the phone's shared Movies/BDFutsalCam folder, so Gallery, Files and a USB cable can all reach them, " +
                "and they stay if you uninstall the app. Copy them to a computer or cloud drive after each match.",
        )
    }

    toDelete?.let { clip ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete clip?") },
            text = { Text("${clip.displayName} will be removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    try {
                        context.contentResolver.delete(clip.uri, null, null)
                        vm.refreshLibrary()
                    } catch (e: SecurityException) {
                        // Files from an earlier install of the app need the user's consent to delete.
                        val sender = when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                                MediaStore.createDeleteRequest(context.contentResolver, listOf(clip.uri)).intentSender
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                                e.userAction.actionIntent.intentSender
                            else -> null
                        }
                        if (sender != null) deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        else Toast.makeText(context, "Couldn't delete this clip.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Delete", color = Bd.Danger) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipCard(
    clip: Clip,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onDelete: () -> Unit,
) = BdCard(Modifier.fillMaxWidth()) {
    val context = LocalContext.current
    var thumb by remember(clip.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(clip.uri) { thumb = withContext(Dispatchers.IO) { loadThumb(context, clip) } }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 112.dp, height = 63.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Bd.SurfaceHigh),
        ) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f)) {
            val meta = clip.meta
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                meta?.let { Pill(it.role.label, Bd.Accent, dot = false) }
                Text(formatDuration(clip.durationMs) + " · " + formatBytes(clip.sizeBytes), color = Bd.Muted, fontSize = 12.sp)
            }
            Text(
                meta?.matchName ?: clip.displayName,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                meta?.let { "Started " + SimpleDateFormat("d MMM HH:mm:ss.SSS", Locale.getDefault()).format(Date(it.actualStartMs)) }
                    ?: SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(clip.dateAddedSec * 1000)),
                color = Bd.Muted,
                fontSize = 12.sp,
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GhostButton("▶ Play", onPlay)
        GhostButton("Share", onShare)
        GhostButton("Lab: Left", onLeft)
        GhostButton("Lab: Right", onRight)
        TextButton(onClick = onDelete) { Text("Delete", color = Bd.Danger) }
    }
}

private fun loadThumb(context: Context, clip: Clip): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(clip.uri, Size(320, 180), null)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver,
            clip.uri.lastPathSegment!!.toLong(),
            MediaStore.Video.Thumbnails.MINI_KIND,
            null,
        )
    }
}.getOrNull()

private fun play(context: Context, clip: Clip) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(clip.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No video player found.", Toast.LENGTH_SHORT).show() }
}

private fun shareClip(context: Context, clip: Clip) {
    val intent = Intent(Intent.ACTION_SEND).setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, clip.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share clip"))
}
