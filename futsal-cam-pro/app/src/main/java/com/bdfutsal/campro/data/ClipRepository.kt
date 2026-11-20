package com.bdfutsal.campro.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Extra facts about a clip that the video file itself doesn't hold. */
data class ClipMeta(
    val displayName: String,
    val role: Role,
    val matchName: String?,
    val sessionCode: String?,
    val scheduledStartMs: Long?,
    /** Wall-clock time when CameraX reported the recording started. */
    val actualStartMs: Long,
    val quality: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("displayName", displayName)
        .put("role", role.name)
        .put("matchName", matchName)
        .put("sessionCode", sessionCode)
        .put("scheduledStartMs", scheduledStartMs)
        .put("actualStartMs", actualStartMs)
        .put("quality", quality)

    companion object {
        fun fromJson(o: JSONObject) = ClipMeta(
            displayName = o.getString("displayName"),
            role = runCatching { Role.valueOf(o.getString("role")) }.getOrDefault(Role.LEFT),
            matchName = o.optString("matchName").takeIf { o.has("matchName") && it.isNotEmpty() },
            sessionCode = o.optString("sessionCode").takeIf { o.has("sessionCode") && it.isNotEmpty() },
            scheduledStartMs = if (o.has("scheduledStartMs")) o.getLong("scheduledStartMs") else null,
            actualStartMs = o.getLong("actualStartMs"),
            quality = o.optString("quality"),
        )
    }
}

data class Clip(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
    val meta: ClipMeta?,
)

/**
 * Clips are written to the shared Movies/BDFutsalCam folder through MediaStore, so
 * they show up in Gallery/Files and survive uninstalling the app.
 */
class ClipRepository(private val context: Context) {
    private val metaFile = File(context.filesDir, "clip_meta.json")

    fun saveMeta(meta: ClipMeta) = synchronized(this) {
        val all = readMeta().filterKeys { it != meta.displayName } + (meta.displayName to meta)
        val arr = JSONArray()
        all.values.forEach { arr.put(it.toJson()) }
        metaFile.writeText(arr.toString())
    }

    fun metaFor(displayName: String?): ClipMeta? =
        displayName?.let { name -> readMeta()[name.substringBeforeLast('.')] ?: readMeta()[name] }

    private fun readMeta(): Map<String, ClipMeta> = synchronized(this) {
        if (!metaFile.exists()) return emptyMap()
        runCatching {
            val arr = JSONArray(metaFile.readText())
            (0 until arr.length()).map { ClipMeta.fromJson(arr.getJSONObject(it)) }
                .associateBy { it.displayName }
        }.getOrDefault(emptyMap())
    }

    fun loadClips(): List<Clip> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to arrayOf("$RELATIVE_DIR%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/$RELATIVE_DIR/%")
        }
        val meta = readMeta()
        val clips = mutableListOf<Clip>()
        runCatching {
            context.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    clips += Clip(
                        uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        displayName = name,
                        sizeBytes = c.getLong(sizeCol),
                        durationMs = c.getLong(durCol),
                        dateAddedSec = c.getLong(dateCol),
                        meta = meta[name.substringBeforeLast('.')] ?: meta[name],
                    )
                }
            }
        }
        return clips
    }

    /** Display name of any content Uri (e.g. one picked from Files). */
    fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun freeBytes(): Long = runCatching {
        Environment.getExternalStorageDirectory().usableSpace
    }.getOrDefault(0L)

    companion object {
        const val FOLDER = "BDFutsalCam"
        val RELATIVE_DIR = "${Environment.DIRECTORY_MOVIES}/$FOLDER"
    }
}
