package com.weaveraudio.sonycam

import android.content.Context
import android.net.Uri
import com.weaveraudio.sonycam.color.CubeLut
import com.weaveraudio.sonycam.color.GradeSettings
import java.io.File
import java.io.IOException

/** Imported .cube files, kept in app storage so they survive between sessions. */
class LutStore(private val context: Context) {
    private val dir = File(context.filesDir, "luts").apply { mkdirs() }

    fun list(): List<String> =
        dir.listFiles { f -> f.isFile && f.extension.equals("cube", ignoreCase = true) }
            ?.map { it.name }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()

    fun load(name: String): CubeLut = File(dir, name).reader().use { CubeLut.parse(it) }

    /** Copies and validates a .cube file. Returns the stored name. */
    fun import(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val data = input.readBytes()
            if (data.size > 64 * 1024 * 1024) throw IOException("LUT file is too large")
            data
        } ?: throw IOException("Could not open the LUT file")
        CubeLut.parse(bytes.inputStream().reader()) // throws with a readable message if invalid
        var name = MediaSaver.displayName(context, uri)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "LUT" }
        if (!name.endsWith(".cube", ignoreCase = true)) name += ".cube"
        File(dir, name).writeBytes(bytes)
        return name
    }

    fun delete(name: String) {
        File(dir, name).delete()
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("grade", Context.MODE_PRIVATE)

    fun load(): GradeSettings {
        val d = GradeSettings()
        return GradeSettings(
            mode = enumOr(prefs.getString("mode", null), d.mode),
            exposureEv = prefs.getFloat("ev", d.exposureEv),
            exposureStage = enumOr(prefs.getString("stage", null), d.exposureStage),
            lutInput = enumOr(prefs.getString("lutInput", null), d.lutInput),
            lutName = prefs.getString("lutName", null),
            videoCodec = enumOr(prefs.getString("codec", null), d.videoCodec),
        )
    }

    fun save(s: GradeSettings) {
        prefs.edit()
            .putString("mode", s.mode.name)
            .putFloat("ev", s.exposureEv)
            .putString("stage", s.exposureStage.name)
            .putString("lutInput", s.lutInput.name)
            .putString("lutName", s.lutName)
            .putString("codec", s.videoCodec.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: fallback
}
