package com.bdfutsal.campro.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bdfutsal.campro.data.ClipMeta
import com.bdfutsal.campro.data.ClipRepository
import com.bdfutsal.campro.data.MatchSession
import com.bdfutsal.campro.data.QualityOption
import com.bdfutsal.campro.data.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class RecPhase { OFF, READY, ARMED, RECORDING, FINALIZING }

data class CameraUiState(
    val phase: RecPhase = RecPhase.OFF,
    val elapsedMs: Long = 0,
    val bytes: Long = 0,
    val message: String = "Camera permission is required. No videos leave this phone.",
    val armedForMs: Long? = null,
    val supportedQualities: List<QualityOption> = emptyList(),
    val activeQuality: QualityOption? = null,
    val zoomPresets: List<Float> = listOf(1f),
    val zoom: Float = 1f,
)

/** What the recording is for, captured when it starts. */
data class RecordingInfo(val role: Role, val session: MatchSession?, val mic: Boolean, val beep: Boolean)

/**
 * Owns the CameraX use cases. It is kept by the ViewModel, so switching tabs never
 * stops a recording: the preview surface is simply detached and re-attached.
 */
class CameraStation(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clips: ClipRepository,
) {
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state

    private val _clipSaved = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val clipSaved: SharedFlow<Unit> = _clipSaved

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val preview = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build(),
        )
        .build()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var armJob: Job? = null
    private var boundQuality: QualityOption? = null
    private var boundOwner: LifecycleOwner? = null
    private var rotation: Int = Surface.ROTATION_0

    val isBusy get() = _state.value.phase.let { it == RecPhase.RECORDING || it == RecPhase.ARMED || it == RecPhase.FINALIZING }

    fun previewUseCase(): Preview = preview

    /** Binds (or rebinds for a new quality) the back camera. Ignored while busy. */
    suspend fun bind(owner: LifecycleOwner, quality: QualityOption) {
        if (isBusy) return
        if (camera != null && boundQuality == quality && boundOwner === owner) return
        val provider = provider ?: awaitProvider().also { provider = it }
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: run {
            _state.update { s -> s.copy(message = "No rear camera found on this device.") }
            return
        }
        val supported = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        val options = QualityOption.entries.filter { it.toCameraX() in supported }
        val chosen = quality.takeIf { it in options } ?: options.lastOrNull { it <= quality } ?: options.firstOrNull() ?: QualityOption.HD

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(chosen.toCameraX(), FallbackStrategy.lowerQualityOrHigherThan(chosen.toCameraX())),
            )
            .build()
        val vc = VideoCapture.withOutput(recorder).also { it.targetRotation = rotation }
        preview.targetRotation = rotation
        provider.unbindAll()
        val cam = provider.bindToLifecycle(owner, selector, preview, vc)
        camera = cam
        videoCapture = vc
        boundQuality = quality
        boundOwner = owner

        val zs = cam.cameraInfo.zoomState.value
        val min = zs?.minZoomRatio ?: 1f
        val max = zs?.maxZoomRatio ?: 1f
        val presets = buildList {
            if (min < 0.95f) add(min)
            add(1f)
            if (max >= 2f) add(2f)
        }
        cam.cameraControl.setZoomRatio(1f)
        _state.update {
            it.copy(
                phase = RecPhase.READY,
                supportedQualities = options,
                activeQuality = chosen,
                zoomPresets = presets,
                zoom = 1f,
                message = if (chosen != quality) "${quality.label} isn't supported here, using ${chosen.label}."
                else "Camera ready. Clips save to Movies/${ClipRepository.FOLDER}.",
            )
        }
    }

    fun setRotation(r: Int) {
        rotation = r
        // A rotation change only affects recordings started afterwards.
        if (_state.value.phase != RecPhase.RECORDING) videoCapture?.targetRotation = r
        preview.targetRotation = r
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
        _state.update { it.copy(zoom = ratio) }
    }

    fun recordNow(info: RecordingInfo) {
        if (_state.value.phase != RecPhase.READY) return
        start(info, lateByMs = null)
    }

    fun arm(info: RecordingInfo) {
        val session = info.session ?: return
        if (_state.value.phase != RecPhase.READY) return
        if (session.startEpochMs <= System.currentTimeMillis()) {
            _state.update { it.copy(message = "That start time has passed. Generate or join a new match.") }
            return
        }
        _state.update { it.copy(phase = RecPhase.ARMED, armedForMs = session.startEpochMs, message = "Armed. Keep this screen open until the start.") }
        armJob = scope.launch {
            while (true) {
                val remaining = session.startEpochMs - System.currentTimeMillis()
                if (remaining <= 0) break
                // Coarse waits first, then a short final wait for precision.
                delay(if (remaining > 1500) remaining - 1000 else remaining)
            }
            start(info, lateByMs = System.currentTimeMillis() - session.startEpochMs)
        }
    }

    fun disarm() {
        armJob?.cancel()
        armJob = null
        if (_state.value.phase == RecPhase.ARMED) {
            _state.update { it.copy(phase = RecPhase.READY, armedForMs = null, message = "Timed start cancelled.") }
        }
    }

    fun stop() {
        armJob?.cancel()
        val r = recording ?: return
        _state.update { it.copy(phase = RecPhase.FINALIZING, message = "Saving clip…") }
        r.stop()
        recording = null
    }

    @SuppressLint("MissingPermission")
    private fun start(info: RecordingInfo, lateByMs: Long?) {
        val vc = videoCapture ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "BDCAM_${info.session?.code ?: "NOMATCH"}_${info.role.name}_$stamp"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, ClipRepository.RELATIVE_DIR)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ClipRepository.FOLDER)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(dir, "$name.mp4").absolutePath)
            }
        }
        val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        val audioAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var pending = vc.output.prepareRecording(context, output)
        if (info.mic && audioAllowed) pending = pending.withAudioEnabled()

        val qualityLabel = _state.value.activeQuality?.label ?: ""
        var startedAt = 0L
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    startedAt = System.currentTimeMillis()
                    val note = when {
                        lateByMs == null -> "Recording. Do one loud clap in view of both cameras."
                        lateByMs > 1000 -> "Started ${"%.1f".format(lateByMs / 1000.0)} s late. Was the screen off or the app hidden?"
                        else -> "Timed start OK. Clap once in view of both cameras."
                    }
                    _state.update { it.copy(phase = RecPhase.RECORDING, armedForMs = null, elapsedMs = 0, bytes = 0, message = note) }
                    if (info.beep && lateByMs != null) beep()
                }
                is VideoRecordEvent.Status -> _state.update {
                    it.copy(elapsedMs = event.recordingStats.recordedDurationNanos / 1_000_000, bytes = event.recordingStats.numBytesRecorded)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    val uri = event.outputResults.outputUri
                    val saved = uri != android.net.Uri.EMPTY
                    if (saved) {
                        clips.saveMeta(
                            ClipMeta(
                                displayName = name,
                                role = info.role,
                                matchName = info.session?.name,
                                sessionCode = info.session?.code,
                                scheduledStartMs = if (lateByMs != null) info.session?.startEpochMs else null,
                                actualStartMs = if (startedAt > 0) startedAt else System.currentTimeMillis(),
                                quality = qualityLabel,
                            ),
                        )
                        _clipSaved.tryEmit(Unit)
                    }
                    val msg = when {
                        !event.hasError() -> "Saved $name.mp4 to Movies/${ClipRepository.FOLDER}."
                        saved -> "Recording stopped early (${errorText(event.error)}). What was captured was saved."
                        else -> "Recording failed: ${errorText(event.error)}."
                    }
                    _state.update { it.copy(phase = if (camera != null) RecPhase.READY else RecPhase.OFF, message = msg) }
                }
            }
        }
    }

    private fun beep() = scope.launch {
        // Short delay so the beep lands inside the recording. If the phones sit side
        // by side, each phone's beep is heard by both mics: a shared sync reference.
        delay(400)
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            delay(600)
            tg.release()
        }
    }

    fun release() {
        armJob?.cancel()
        recording?.stop()
        recording = null
        provider?.unbindAll()
        camera = null
        boundOwner = null
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun QualityOption.toCameraX(): Quality = when (this) {
        QualityOption.HD -> Quality.HD
        QualityOption.FHD -> Quality.FHD
        QualityOption.UHD -> Quality.UHD
    }

    private fun errorText(code: Int) = when (code) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "storage full"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "camera closed, the app left the screen"
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "file size limit reached"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "encoder error"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "no video data"
        else -> "error $code"
    }
}
