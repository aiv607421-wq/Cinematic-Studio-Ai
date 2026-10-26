package com.cinematicstudio.ai.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.cinematicstudio.ai.data.model.MediaAsset
import com.cinematicstudio.ai.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Imports media chosen with the Storage Access Framework and probes their
 * metadata (duration, resolution, size) so the timeline can be built without
 * copying the user's files.
 */
class MediaLibrary(context: Context) {

    private val appContext = context.applicationContext

    /** Default on-timeline length for still images (milliseconds). */
    val imageDefaultDurationMs = 5_000L

    /** Imports [uris], skipping ones already present in [existing] (duplicate detection). */
    suspend fun import(uris: List<Uri>, existing: List<MediaAsset> = emptyList()): List<MediaAsset> =
        withContext(Dispatchers.IO) {
            val known = existing.map { it.uri }.toSet()
            uris.distinct()
                .filterNot { it.toString() in known }
                .mapNotNull { uri -> probe(uri) }
        }

    /** Reads metadata for a single document. Returns null when it cannot be read. */
    fun probe(uri: Uri): MediaAsset? {
        val resolver = appContext.contentResolver
        val mime = try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        } ?: ""

        var name = uri.lastPathSegment ?: "media"
        var size = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // Keep the fallback values.
        }

        val type = typeOf(mime, name)
        var duration = 0L
        var width = 0
        var height = 0

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width == 0 || height == 0) {
                // Stills often report through the image keys instead.
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH)?.toIntOrNull() ?: width
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT)?.toIntOrNull() ?: height
            }
        } catch (e: Exception) {
            // Unreadable media: still import it so the user can see and remove it.
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore.
            }
        }

        if (type == MediaType.IMAGE) duration = 0L

        return MediaAsset(
            id = UUID.randomUUID().toString(),
            uri = uri.toString(),
            displayName = name,
            mimeType = mime.ifEmpty { guessMime(name, type) },
            type = type,
            durationMs = duration.coerceAtLeast(0L),
            width = width,
            height = height,
            sizeBytes = size,
        )
    }

    /** True when the document can still be opened (used for relink detection). */
    fun isAvailable(uri: String): Boolean = try {
        val parsed = Uri.parse(uri)
        appContext.contentResolver.query(parsed, null, null, null, null)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun typeOf(mime: String, name: String): MediaType = when {
        mime.startsWith("video/") -> MediaType.VIDEO
        mime.startsWith("audio/") -> MediaType.AUDIO
        mime.startsWith("image/") -> MediaType.IMAGE
        else -> when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "mov", "mkv", "webm", "m4v", "3gp" -> MediaType.VIDEO
            "mp3", "wav", "aac", "m4a", "flac", "ogg", "opus" -> MediaType.AUDIO
            "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" -> MediaType.IMAGE
            else -> MediaType.VIDEO
        }
    }

    private fun guessMime(name: String, type: MediaType): String = when (type) {
        MediaType.IMAGE -> "image/*"
        MediaType.AUDIO -> "audio/*"
        MediaType.VIDEO -> "video/*"
    }
}
