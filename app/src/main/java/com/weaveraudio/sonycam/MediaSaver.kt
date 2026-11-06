package com.weaveraudio.sonycam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException

/** Writes results into the shared gallery: Pictures/HLG709 and Movies/HLG709. */
object MediaSaver {
    const val FOLDER = "HLG709"

    fun createPending(context: Context, displayName: String, mimeType: String, isVideo: Boolean): Uri {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val dir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
            ?: throw IOException("Could not create $displayName in the gallery")
    }

    fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun discard(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun displayName(context: Context, uri: Uri): String {
        val fromQuery = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return fromQuery ?: uri.lastPathSegment ?: "media"
    }

    /** "C0012.MP4" -> "C0012_709.mp4" */
    fun outputName(sourceName: String, extension: String): String {
        val base = sourceName.substringBeforeLast('.').ifBlank { "media" }
        return "${base}_709.$extension"
    }

    /**
     * Output file name for [uri]. The system photo picker hides real file names (it reports
     * e.g. "1000000034.jpg"), so for those the capture time is used instead.
     */
    fun outputNameFor(context: Context, uri: Uri, isVideo: Boolean): String {
        val ext = if (isVideo) "mp4" else "jpg"
        val name = displayName(context, uri)
        val base = name.substringBeforeLast('.')
        if (!base.all { it.isDigit() }) return outputName(name, ext)
        val stamp = if (isVideo) videoTimestamp(context, uri) else photoTimestamp(context, uri)
        return if (stamp != null) {
            "${if (isVideo) "VID" else "IMG"}_${stamp}_709.$ext"
        } else {
            outputName(name, ext)
        }
    }

    /** EXIF "2026:11:05 11:47:26" -> "20261105_114726". */
    private fun photoTimestamp(context: Context, uri: Uri): String? = runCatching {
        val exif = context.contentResolver.openInputStream(uri)?.use {
            androidx.exifinterface.media.ExifInterface(it)
        }
        val raw = exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif?.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
        raw?.let { Regex("(\\d{4}):(\\d{2}):(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})").find(it) }
            ?.groupValues?.let { g -> "${g[1]}${g[2]}${g[3]}_${g[4]}${g[5]}${g[6]}" }
    }.getOrNull()

    /** MP4 creation time "20261105T114727.000Z" -> "20261105_114727". */
    private fun videoTimestamp(context: Context, uri: Uri): String? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.let { Regex("(\\d{8})T(\\d{6})").find(it) }
                ?.groupValues?.let { g -> "${g[1]}_${g[2]}" }
                ?.takeUnless { it.startsWith("1904") || it.startsWith("1970") }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
