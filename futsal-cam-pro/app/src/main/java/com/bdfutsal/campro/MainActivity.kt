package com.bdfutsal.campro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bdfutsal.campro.ui.Bd
import com.bdfutsal.campro.ui.BdTheme
import com.bdfutsal.campro.ui.CaptureScreen
import com.bdfutsal.campro.ui.Kicker
import com.bdfutsal.campro.ui.LibraryScreen
import com.bdfutsal.campro.ui.SessionScreen
import com.bdfutsal.campro.ui.SyncLabScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val pendingTab = mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A mounted camera must never let the screen sleep: that would stop the recording.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) handleLink(intent)
        setContent { BdTheme { App(vm, pendingTab.intValue) { pendingTab.intValue = -1 } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val data = intent?.dataString ?: return
        val s = vm.join(data)
        Toast.makeText(this, if (s != null) "Joined match ${s.name}" else "That match link isn't valid", Toast.LENGTH_SHORT).show()
        if (s != null) pendingTab.intValue = 1
    }
}

private val TABS = listOf("◉ Capture", "⌗ Match", "▣ Library", "⇄ Sync lab")

private fun requiredPermissions() = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}.toTypedArray()

@Composable
private fun App(vm: AppViewModel, requestedTab: Int, onTabConsumed: () -> Unit) {
    val context = LocalView.current.context
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(requestedTab) { if (requestedTab >= 0) { tab = requestedTab; onTabConsumed() } }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var hasCamera by rememberSaveable { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCamera = granted(Manifest.permission.CAMERA)
        if (!hasCamera) Toast.makeText(context, "Camera access is needed to record. You can allow it in Settings.", Toast.LENGTH_LONG).show()
        vm.refreshLibrary()
    }
    LaunchedEffect(Unit) { if (!hasCamera) permLauncher.launch(requiredPermissions()) }

    // Keep the camera bound to the activity, independent of which tab is showing.
    val owner = LocalLifecycleOwner.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    LaunchedEffect(hasCamera, settings.quality, owner) {
        if (hasCamera) runCatching { vm.camera.bind(owner, settings.quality) }
            .onFailure { Toast.makeText(context, "Camera error: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val view = LocalView.current
    val config = LocalConfiguration.current
    LaunchedEffect(config.orientation) { view.display?.rotation?.let(vm.camera::setRotation) }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(100) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bd.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 1100.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header { showAbout = true }
            TabRow(selectedTabIndex = tab, containerColor = Bd.Bg, contentColor = Bd.Accent) {
                TABS.forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i }, text = { Text(t, maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) })
                }
            }
            when (tab) {
                0 -> CaptureScreen(vm, now, hasCamera, { permLauncher.launch(requiredPermissions()) }) { tab = 1 }
                1 -> SessionScreen(vm, now)
                2 -> LibraryScreen(vm) { tab = 3 }
                else -> SyncLabScreen(vm)
            }
            Footer()
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Understood") } },
            title = { Text("Know the limitations") },
            text = {
                Text(
                    "Each phone records on its own. There's no remote start/stop, automatic stitching, cloud upload or AI tracking in this version. " +
                        "Match links share a start time only; there's no live pairing server, so the start is as accurate as the phones' clocks.\n\n" +
                        "Keep the app open on screen while recording: leaving it stops the camera. The screen is kept awake automatically. " +
                        "Make a 30-second test recording before every match.",
                )
            },
        )
    }
}

@Composable
private fun Header(onAbout: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(Bd.Accent, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("BD", color = Bd.Bg, fontWeight = FontWeight.Black) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("FUTSAL CAM PRO", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Kicker("TWO CAMERAS. ONE MATCH.", Bd.Muted)
        }
        Kicker("● LOCAL-FIRST")
        IconButton(
            onClick = onAbout,
            modifier = Modifier.padding(start = 8.dp).border(1.dp, Bd.Outline, RoundedCornerShape(50)).size(36.dp),
        ) { Text("?", fontWeight = FontWeight.Bold) }
    }
    Column {
        Kicker("BD INTELLIGENCE / CAPTURE STUDIO · ANDROID V1.0", Bd.Muted)
        Text(
            "Capture every moment.",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )
        Text(
            "Built for two phones on one mount. Record each half, organise your match and prepare footage for panoramic processing.",
            style = MaterialTheme.typography.bodyMedium,
            color = Bd.Muted,
        )
    }
}

@Composable
private fun Footer() = Row(
    Modifier.fillMaxWidth().padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    Text("BD / FUTSAL INTELLIGENCE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Bd.Muted)
    Text("V1.0 · LOCAL-FIRST", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Bd.Muted)
}
