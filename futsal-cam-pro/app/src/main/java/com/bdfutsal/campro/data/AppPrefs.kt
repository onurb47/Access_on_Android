package com.bdfutsal.campro.data

import android.content.Context
import androidx.core.content.edit

enum class Role(val label: String, val camNumber: String) {
    LEFT("LEFT", "01"), RIGHT("RIGHT", "02")
}

enum class QualityOption(val label: String, val detail: String, val approxMbps: Double) {
    HD("720p", "stable test", 8.0),
    FHD("1080p", "recommended", 17.0),
    UHD("4K", "experimental", 45.0),
}

/** Small key-value store for settings and the current match session. */
class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("bd_cam_pro", Context.MODE_PRIVATE)

    var role: Role
        get() = runCatching { Role.valueOf(p.getString("role", null)!!) }.getOrDefault(Role.LEFT)
        set(v) = p.edit { putString("role", v.name) }

    var quality: QualityOption
        get() = runCatching { QualityOption.valueOf(p.getString("quality", null)!!) }
            .getOrDefault(QualityOption.FHD)
        set(v) = p.edit { putString("quality", v.name) }

    var micEnabled: Boolean
        get() = p.getBoolean("mic", true)
        set(v) = p.edit { putBoolean("mic", v) }

    var startBeep: Boolean
        get() = p.getBoolean("beep", true)
        set(v) = p.edit { putBoolean("beep", v) }

    var matchName: String
        get() = p.getString("matchName", "University Futsal Match")!!
        set(v) = p.edit { putString("matchName", v) }

    var delaySeconds: Int
        get() = p.getInt("delay", 120)
        set(v) = p.edit { putInt("delay", v) }

    var session: MatchSession?
        get() = p.getString("sessionLink", null)?.let(MatchSession::parse)
        set(v) = p.edit { if (v == null) remove("sessionLink") else putString("sessionLink", v.toLink()) }
}
