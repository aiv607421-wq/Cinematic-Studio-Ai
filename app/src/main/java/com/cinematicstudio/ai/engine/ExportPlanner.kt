package com.cinematicstudio.ai.engine

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.data.model.Track
import com.cinematicstudio.ai.data.model.TrackType

/**
 * Maps an [EditorProject] onto a Media3 [Composition].
 *
 * This is the single source of truth for "what the timeline means": the preview
 * player and the exporter both consume the composition built here, so what you
 * see while editing is what the render produces.
 *
 * Timeline fidelity rules:
 *  - clips keep their source in/out points,
 *  - empty regions between clips are preserved with [addGap] (black for video,
 *    silence for audio) instead of silently closing them,
 *  - muted tracks drop their audio, hidden tracks are still rendered (visibility
 *    is a timeline convenience, not an edit).
 */
object ExportPlanner {

    /** Nothing to render when every track is empty. */
    fun hasRenderableContent(project: EditorProject): Boolean =
        project.timeline.tracks.any { it.clips.isNotEmpty() }

    fun mainVideoTrack(project: EditorProject): Track? =
        project.timeline.tracks.firstOrNull { it.type == TrackType.VIDEO && it.clips.isNotEmpty() }
            ?: project.timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }

    fun audioTracks(project: EditorProject): List<Track> =
        project.timeline.tracks.filter { it.type == TrackType.AUDIO && it.clips.isNotEmpty() }

    /** Video clips on tracks other than the main one (not rendered in Phase 1). */
    fun unrenderedTracks(project: EditorProject): List<Track> {
        val main = mainVideoTrack(project)?.id
        return project.timeline.tracks.filter {
            it.type == TrackType.VIDEO && it.id != main && it.clips.isNotEmpty()
        }
    }

    /**
     * Builds the export composition. Returns null when there is nothing to
     * render or when a clip points at media that is missing.
     */
    fun buildComposition(project: EditorProject): Composition? {
        if (!hasRenderableContent(project)) return null
        if (project.missingAssetIds.isNotEmpty()) return null

        val sequences = mutableListOf<EditedMediaItemSequence>()
        mainVideoTrack(project)?.let { track ->
            sequences += buildSequence(track, project, includeAudio = !track.muted)
        }
        audioTracks(project).forEach { track ->
            sequences += buildSequence(track, project, includeAudio = !track.muted)
        }
        if (sequences.isEmpty()) return null
        return Composition.Builder(sequences).build()
    }

    private fun buildSequence(
        track: Track,
        project: EditorProject,
        includeAudio: Boolean,
    ): EditedMediaItemSequence {
        val trackTypes = if (track.type == TrackType.AUDIO) {
            setOf(C.TRACK_TYPE_AUDIO)
        } else {
            setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
        }
        val builder = EditedMediaItemSequence.Builder(trackTypes)
        var cursorMs = 0L
        for (clip in track.sortedClips) {
            val gapMs = (clip.startMs - cursorMs).coerceAtLeast(0L)
            if (gapMs > 0) builder.addGap(gapMs)
            if (project.asset(clip.assetId) == null) continue
            builder.addItem(toEditedMediaItem(project, clip.assetId, clip.startMs, clip.durationMs, clip.sourceInMs, !includeAudio))
            cursorMs = clip.endMs
        }
        return builder.build()
    }

    private fun toEditedMediaItem(
        project: EditorProject,
        assetId: String,
        startMs: Long,
        durationMs: Long,
        sourceInMs: Long,
        removeAudio: Boolean,
    ): EditedMediaItem {
        val asset = project.asset(assetId)
            ?: throw IllegalStateException("Clip references unknown asset $assetId")
        val mediaItemBuilder = MediaItem.Builder().setUri(asset.uri)
        if (asset.type == com.cinematicstudio.ai.data.model.MediaType.IMAGE) {
            mediaItemBuilder.setImageDurationMs(durationMs)
        } else {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceInMs)
                    .setEndPositionMs(sourceInMs + durationMs)
                    .build()
            )
        }
        return EditedMediaItem.Builder(mediaItemBuilder.build())
            .setDurationUs(durationMs * 1_000)
            .setRemoveAudio(removeAudio || asset.type == com.cinematicstudio.ai.data.model.MediaType.IMAGE)
            .build()
    }

    /**
     * Gap summary used by the export panel so the user can see, before
     * rendering, exactly where empty timeline regions will be preserved.
     */
    fun describeGaps(project: EditorProject): List<GapInfo> {
        val gaps = mutableListOf<GapInfo>()
        for (track in project.timeline.tracks) {
            if (track.clips.isEmpty()) continue
            val sorted = track.sortedClips
            if (sorted.first().startMs > 0) {
                gaps += GapInfo(track.name, 0L, sorted.first().startMs)
            }
            for (i in 0 until sorted.lastIndex) {
                val gapStart = sorted[i].endMs
                val gapEnd = sorted[i + 1].startMs
                if (gapEnd > gapStart) gaps += GapInfo(track.name, gapStart, gapEnd)
            }
        }
        return gaps
    }

    data class GapInfo(val trackName: String, val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    /** Audio processors placeholder for Phase 2 processing chain. */
    fun noAudioProcessors(): List<AudioProcessor> = emptyList()
}
