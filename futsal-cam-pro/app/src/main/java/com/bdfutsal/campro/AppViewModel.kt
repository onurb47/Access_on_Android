package com.bdfutsal.campro

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bdfutsal.campro.camera.CameraStation
import com.bdfutsal.campro.camera.RecordingInfo
import com.bdfutsal.campro.data.AppPrefs
import com.bdfutsal.campro.data.Clip
import com.bdfutsal.campro.data.ClipMeta
import com.bdfutsal.campro.data.ClipRepository
import com.bdfutsal.campro.data.MatchSession
import com.bdfutsal.campro.data.QualityOption
import com.bdfutsal.campro.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Settings(
    val role: Role,
    val quality: QualityOption,
    val mic: Boolean,
    val beep: Boolean,
    val matchName: String,
    val delaySeconds: Int,
)

/** A clip loaded into one side of the sync lab. */
data class LabClip(val uri: Uri, val name: String, val meta: ClipMeta?)

data class LabState(
    val left: LabClip? = null,
    val right: LabClip? = null,
    /** Seconds the right camera started after the left one. */
    val offsetSec: Double = 0.0,
)

data class LibraryState(
    val clips: List<Clip> = emptyList(),
    val freeBytes: Long = 0,
    val loading: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)
    val clipRepo = ClipRepository(app)
    val camera = CameraStation(app, viewModelScope, clipRepo)

    private val _settings = MutableStateFlow(
        Settings(prefs.role, prefs.quality, prefs.micEnabled, prefs.startBeep, prefs.matchName, prefs.delaySeconds),
    )
    val settings: StateFlow<Settings> = _settings

    private val _session = MutableStateFlow(prefs.session)
    val session: StateFlow<MatchSession?> = _session

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library

    private val _lab = MutableStateFlow(LabState())
    val lab: StateFlow<LabState> = _lab

    init {
        viewModelScope.launch { camera.clipSaved.collect { refreshLibrary() } }
        refreshLibrary()
    }

    fun setRole(r: Role) { prefs.role = r; _settings.update { it.copy(role = r) } }
    fun setQuality(q: QualityOption) { prefs.quality = q; _settings.update { it.copy(quality = q) } }
    fun setMic(on: Boolean) { prefs.micEnabled = on; _settings.update { it.copy(mic = on) } }
    fun setBeep(on: Boolean) { prefs.startBeep = on; _settings.update { it.copy(beep = on) } }
    fun setMatchName(n: String) { prefs.matchName = n; _settings.update { it.copy(matchName = n) } }
    fun setDelay(s: Int) { prefs.delaySeconds = s; _settings.update { it.copy(delaySeconds = s) } }

    fun createSession(): MatchSession {
        val s = MatchSession.create(_settings.value.matchName, _settings.value.delaySeconds)
        setSession(s)
        return s
    }

    /** Returns the joined session, or null if [text] holds no valid match link. */
    fun join(text: String): MatchSession? = MatchSession.parse(text)?.also(::setSession)

    fun clearSession() = setSession(null)

    private fun setSession(s: MatchSession?) {
        prefs.session = s
        _session.value = s
    }

    private fun recordingInfo() = _settings.value.let { RecordingInfo(it.role, _session.value, it.mic, it.beep) }
    fun recordNow() = camera.recordNow(recordingInfo())
    fun arm() = camera.arm(recordingInfo())

    fun refreshLibrary() {
        _library.update { it.copy(loading = true) }
        viewModelScope.launch {
            val (clips, free) = withContext(Dispatchers.IO) { clipRepo.loadClips() to clipRepo.freeBytes() }
            _library.value = LibraryState(clips, free, loading = false)
        }
    }

    fun setLabClip(right: Boolean, uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { clipRepo.displayNameOf(uri) } ?: uri.lastPathSegment ?: "clip"
            val clip = LabClip(uri, name, withContext(Dispatchers.IO) { clipRepo.metaFor(name) })
            _lab.update { if (right) it.copy(right = clip) else it.copy(left = clip) }
        }
    }

    fun setOffset(sec: Double) = _lab.update { it.copy(offsetSec = sec.coerceIn(-OFFSET_LIMIT, OFFSET_LIMIT)) }

    /** Offset implied by the two clips' recorded start times, if both are known. */
    fun suggestedOffset(): Double? {
        val l = _lab.value.left?.meta ?: return null
        val r = _lab.value.right?.meta ?: return null
        return (r.actualStartMs - l.actualStartMs) / 1000.0
    }

    override fun onCleared() {
        camera.release()
    }

    companion object {
        const val OFFSET_LIMIT = 120.0
    }
}
