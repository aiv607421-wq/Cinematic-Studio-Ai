package com.cinematicstudio.ai.data.model

/**
 * Core project model.
 *
 * The timeline is stored as plain immutable data so every edit produces a new
 * project value; undo/redo is then a simple state stack (see engine/History.kt).
 * Original media is never modified — clips only reference source URIs plus
 * in/out points, which keeps editing non-destructive.
 */

const val SCHEMA_VERSION = 1

enum class MediaType { VIDEO, IMAGE, AUDIO }

enum class TrackType { VIDEO, AUDIO, TEXT }

data class MediaAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val mimeType: String = "",
    val type: MediaType = MediaType.VIDEO,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
) {
    /** True when the underlying document can still be opened (missing-media detection). */
    val hasInfiniteDuration: Boolean get() = type == MediaType.IMAGE
}

data class Clip(
    val id: String,
    val assetId: String,
    /** Position of the clip start on the timeline, milliseconds. */
    val startMs: Long,
    /** Timeline duration of the clip, milliseconds. */
    val durationMs: Long,
    /** In point inside the source media, milliseconds. */
    val sourceInMs: Long = 0L,
    val label: String? = null,
) {
    val endMs: Long get() = startMs + durationMs
}

data class Track(
    val id: String,
    val type: TrackType,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val muted: Boolean = false,
    val hidden: Boolean = false,
    val locked: Boolean = false,
) {
    /** Clips are always kept ordered by start time. */
    val sortedClips: List<Clip> get() = clips.sortedBy { it.startMs }
    val endMs: Long get() = clips.maxOfOrNull { it.endMs } ?: 0L
}

data class Timeline(
    val tracks: List<Track> = emptyList(),
) {
    val durationMs: Long get() = tracks.maxOfOrNull { it.endMs } ?: 0L

    fun track(trackId: String): Track? = tracks.firstOrNull { it.id == trackId }

    fun clip(clipId: String): Pair<Track, Clip>? =
        tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == clipId }?.let { t to it } }
}

data class ProjectSettings(
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
    val aspectRatio: String = "16:9",
) {
    val frameDurationMs: Long get() = (1000.0 / frameRate).toLong()
}

data class EditorProject(
    val id: String,
    val name: String,
    val schemaVersion: Int = SCHEMA_VERSION,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val settings: ProjectSettings = ProjectSettings(),
    val assets: List<MediaAsset> = emptyList(),
    val timeline: Timeline = Timeline(),
    /** Ids of assets that could not be resolved at load time (relink candidates). */
    val missingAssetIds: List<String> = emptyList(),
) {
    fun asset(assetId: String): MediaAsset? = assets.firstOrNull { it.id == assetId }
}

/** Common aspect-ratio presets offered by the new-project dialog. */
data class AspectPreset(val label: String, val width: Int, val height: Int) {
    companion object {
        val ALL = listOf(
            AspectPreset("16:9", 1920, 1080),
            AspectPreset("9:16", 1080, 1920),
            AspectPreset("1:1", 1080, 1080),
            AspectPreset("4:3", 1440, 1080),
            AspectPreset("21:9", 2560, 1080),
        )
    }
}

/** Export/render targets offered by the export panel. */
data class ExportPreset(val label: String, val width: Int, val height: Int, val frameRate: Int) {
    companion object {
        /**
         * Presets are derived from the project aspect ratio by scaling the short
         * edge, so portrait projects produce portrait outputs (1080x1920 -> 720x1280).
         */
        fun forProject(settings: ProjectSettings): List<ExportPreset> {
            val shortEdge = minOf(settings.width, settings.height)
            fun preset(label: String, targetShortEdge: Int): ExportPreset {
                val factor = targetShortEdge.toDouble() / shortEdge.toDouble()
                return ExportPreset(
                    label = label,
                    width = even(settings.width * factor),
                    height = even(settings.height * factor),
                    frameRate = settings.frameRate,
                )
            }
            return listOf(
                preset("720p", 720),
                preset("1080p", 1080),
                preset("4K", 2160),
                ExportPreset("Source", settings.width, settings.height, settings.frameRate),
            )
        }

        private fun even(value: Double): Int = (value.toInt() / 2) * 2
    }
}
