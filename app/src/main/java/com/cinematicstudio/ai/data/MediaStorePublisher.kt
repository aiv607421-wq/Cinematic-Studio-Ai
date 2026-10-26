package com.cinematicstudio.ai.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Publishes a finished export into the device gallery.
 *
 * On Android 10+ this uses MediaStore with a relative path, which needs no
 * storage permission. Older versions get the file copied through
 * MediaScannerConnection; the export always remains available in the app's own
 * Movies folder regardless.
 */
object MediaStorePublisher {

    private const val ALBUM = "Cinematic Studio AI"

    fun publish(context: Context, source: File): Uri? {
        if (!source.exists()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(context, source)
        } else {
            publishLegacy(context, source)
        }
    }

    private fun publishWithMediaStore(context: Context, source: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + File.separator + ALBUM
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return run {
                resolver.delete(uri, null, null)
                null
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun publishLegacy(context: Context, source: File): Uri? {
        return try {
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                ALBUM
            )
            if (!moviesDir.exists() && !moviesDir.mkdirs()) return null
            val target = File(moviesDir, source.name)
            source.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf("video/mp4"), null)
            Uri.fromFile(target)
        } catch (e: Exception) {
            null
        }
    }
}
