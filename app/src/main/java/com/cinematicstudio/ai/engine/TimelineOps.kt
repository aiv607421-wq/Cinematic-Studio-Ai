package com.cinematicstudio.ai.engine

import com.cinematicstudio.ai.data.model.Clip
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.data.model.Track
import com.cinematicstudio.ai.data.model.TrackType
import java.util.UUID

/**
 * Pure timeline editing operations.
 *
 * Every function returns a new [EditorProject]; nothing is mutated in place, so
 * history (undo/redo) is a stack of project snapshots. Clipping, splitting and
 * moving only change metadata — source files are never touched.
 *
 * Track invariant: clips on one track never overlap and are kept sorted by
 * start time. All operations preserve that invariant.
 */
object TimelineOps {

    /** Shortest clip we allow (~1 frame at 24 fps); prevents zero-length edits. */
    const val MIN_CLIP_MS = 40L

    private fun newId(): String = UUID.randomUUID().toString()

    private fun EditorProject.mapTrack(trackId: String, transform: (Track) -> Track): EditorProject =
        copy(timeline = timeline.copy(tracks = timeline.tracks.map { if (it.id == trackId) transform(it) else it }))

    fun track(project: EditorProject, trackId: String): Track? = project.timeline.track(trackId)

    // ---------------------------------------------------------------- tracks

    fun addTrack(project: EditorProject, type: TrackType, name: String? = null): EditorProject {
        val count = project.timeline.tracks.count { it.type == type }
        val label = name ?: "${type.name.lowercase().replaceFirstChar { it.uppercase() }} ${count + 1}"
        val track = Track(
            id = newId(),
            type = type,
            name = label,
            clips = emptyList(),
            locked = false,
        )
        return project.copy(timeline = project.timeline.copy(tracks = project.timeline.tracks + track))
    }

    fun removeTrack(project: EditorProject, trackId: String): EditorProject {
        val tracks = project.timeline.tracks
        if (tracks.count { it.type == TrackType.VIDEO } <= 1 &&
            tracks.firstOrNull { it.id == trackId }?.type == TrackType.VIDEO
        ) return project
        return project.copy(timeline = project.timeline.copy(tracks = tracks.filterNot { it.id == trackId }))
    }

    fun setTrackFlags(
        project: EditorProject,
        trackId: String,
        muted: Boolean? = null,
        hidden: Boolean? = null,
        locked: Boolean? = null,
    ): EditorProject = project.mapTrack(trackId) {
        it.copy(
            muted = muted ?: it.muted,
            hidden = hidden ?: it.hidden,
            locked = locked ?: it.locked,
        )
    }

    // ------------------------------------------------------------------ clips

    /**
     * Adds [clip] to the track, resolving overlaps by shifting it after the last
     * existing clip. Returns the new project and the clip actually placed.
     */
    fun addClip(project: EditorProject, trackId: String, clip: Clip): Pair<EditorProject, Clip> {
        val target = project.timeline.track(trackId) ?: return project to clip
        if (target.locked) return project to clip
        val end = target.endMs
        val placed = if (clip.startMs < end) clip.copy(startMs = end) else clip
        return project.mapTrack(trackId) { t -> t.copy(clips = t.clips + placed) } to placed
    }

    /** Inserts [clip] at [atMs] if the space is free, otherwise appends it after the last clip. */
    fun insertAt(project: EditorProject, trackId: String, clip: Clip, atMs: Long): Pair<EditorProject, Clip> {
        val target = project.timeline.track(trackId) ?: return project to clip
        if (target.locked) return project to clip
        val candidate = clip.copy(startMs = atMs.coerceAtLeast(0L))
        val overlaps = target.clips.any { it.id != candidate.id && it.startMs < candidate.endMs && candidate.startMs < it.endMs }
        val placed = if (overlaps) candidate.copy(startMs = target.endMs) else candidate
        return project.mapTrack(trackId) { t -> t.copy(clips = t.clips + placed) } to placed
    }

    fun deleteClip(project: EditorProject, clipId: String, ripple: Boolean = false): EditorProject {
        val found = project.timeline.clip(clipId) ?: return project
        val (track, clip) = found
        if (track.locked) return project
        return project.mapTrack(track.id) { t ->
            val remaining = t.clips.filterNot { it.id == clipId }
            val shifted = if (ripple) {
                remaining.map { if (it.startMs >= clip.endMs) it.copy(startMs = it.startMs - clip.durationMs) else it }
            } else remaining
            t.copy(clips = shifted.sortedBy { it.startMs })
        }
    }

    fun duplicateClip(project: EditorProject, clipId: String): EditorProject {
        val found = project.timeline.clip(clipId) ?: return project
        val (track, clip) = found
        if (track.locked) return project
        // The copy sits directly after the original; everything that started at or
        // after that point shifts right so no overlap is ever created.
        val copy = clip.copy(
            id = newId(),
            startMs = clip.endMs,
            label = clip.label?.let { "$it copy" },
        )
        return project.mapTrack(track.id) { t ->
            val shifted = t.clips.map {
                if (it.startMs >= clip.endMs) it.copy(startMs = it.startMs + clip.durationMs) else it
            }
            t.copy(clips = (shifted + copy).sortedBy { it.startMs })
        }
    }

    /**
     * Splits [clipId] at [atMs] (timeline time) into two adjacent clips.
     * The right half inherits the source offset so playback is continuous.
     */
    fun splitClip(project: EditorProject, clipId: String, atMs: Long): EditorProject {
        val found = project.timeline.clip(clipId) ?: return project
        val (track, clip) = found
        if (track.locked) return project
        if (atMs <= clip.startMs || atMs >= clip.endMs) return project
        val leftDuration = atMs - clip.startMs
        val rightDuration = clip.durationMs - leftDuration
        if (leftDuration < MIN_CLIP_MS || rightDuration < MIN_CLIP_MS) return project

        val left = clip.copy(durationMs = leftDuration)
        val right = clip.copy(
            id = newId(),
            startMs = atMs,
            durationMs = rightDuration,
            sourceInMs = clip.sourceInMs + leftDuration,
            label = clip.label?.let { "$it (2)" },
        )
        return project.mapTrack(track.id) { t ->
            t.copy(clips = (t.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) })
                .sortedBy { it.startMs })
        }
    }

    /**
     * Trims a clip by moving its start and/or end on the timeline.
     * [newStartMs]/[newEndMs] are absolute timeline positions; null leaves that edge untouched.
     * Source bounds, minimum length and neighbouring clips are all respected.
     */
    fun trimClip(
        project: EditorProject,
        clipId: String,
        newStartMs: Long? = null,
        newEndMs: Long? = null,
    ): EditorProject {
        val found = project.timeline.clip(clipId) ?: return project
        val (track, clip) = found
        if (track.locked) return project
        val asset = project.asset(clip.assetId)

        val sorted = track.clips.sortedBy { it.startMs }
        val index = sorted.indexOfFirst { it.id == clipId }
        if (index < 0) return project
        val prevEnd = if (index > 0) sorted[index - 1].endMs else 0L
        val nextStart = if (index < sorted.lastIndex) sorted[index + 1].startMs else Long.MAX_VALUE

        var start = newStartMs ?: clip.startMs
        var end = newEndMs ?: clip.endMs

        // Never cross neighbours and never go negative.
        start = start.coerceIn(prevEnd, clip.endMs - MIN_CLIP_MS)
        end = end.coerceIn(clip.startMs + MIN_CLIP_MS, nextStart)
        if (end - start < MIN_CLIP_MS) return project

        // Keep the source window inside the original media.
        val sourceIn = clip.sourceInMs + (start - clip.startMs)
        val duration = end - start
        val maxSourceIn = if (asset != null && !asset.hasInfiniteDuration && asset.durationMs > 0) {
            asset.durationMs - MIN_CLIP_MS
        } else Long.MAX_VALUE

        var finalSourceIn = sourceIn.coerceAtLeast(0L)
        if (finalSourceIn > maxSourceIn) {
            val overflow = finalSourceIn - maxSourceIn
            finalSourceIn = maxSourceIn
            start += overflow
        }
        if (asset != null && !asset.hasInfiniteDuration && asset.durationMs > 0) {
            val maxDuration = asset.durationMs - finalSourceIn
            if (duration > maxDuration) end = start + maxDuration.coerceAtLeast(MIN_CLIP_MS)
        }

        return project.mapTrack(track.id) { t ->
            t.copy(clips = t.clips.map {
                if (it.id == clipId) it.copy(startMs = start, durationMs = end - start, sourceInMs = finalSourceIn)
                else it
            }.sortedBy { it.startMs })
        }
    }

    /** Moves a clip to [newStartMs], optionally onto [targetTrackId] of the same media kind. */
    fun moveClip(
        project: EditorProject,
        clipId: String,
        newStartMs: Long,
        targetTrackId: String? = null,
    ): EditorProject {
        val found = project.timeline.clip(clipId) ?: return project
        val (sourceTrack, clip) = found
        if (sourceTrack.locked) return project
        val destinationId = targetTrackId ?: sourceTrack.id
        val destination = project.timeline.track(destinationId) ?: return project
        if (destination.locked) return project
        if (!isTrackCompatible(destination, clip, project)) return project
        if (destinationId == sourceTrack.id) {
            // Same-track move: the clip itself is excluded from gap checks.
            val sorted = sourceTrack.clips.sortedBy { it.startMs }
            val index = sorted.indexOfFirst { it.id == clipId }
            val prevEnd = if (index > 0) sorted[index - 1].endMs else 0L
            val nextStart = if (index < sorted.lastIndex) sorted[index + 1].startMs else Long.MAX_VALUE
            val target = newStartMs.coerceIn(prevEnd, (nextStart - clip.durationMs).coerceAtLeast(prevEnd))
            if (target == clip.startMs) return project
            return project.mapTrack(sourceTrack.id) { t ->
                t.copy(clips = t.clips.map { if (it.id == clipId) it.copy(startMs = target) else it }.sortedBy { it.startMs })
            }
        }

        // Cross-track move: must fit into a free gap on the destination track.
        val destinationClips = destination.clips.sortedBy { it.startMs }
        val start = newStartMs.coerceAtLeast(0L)
        val end = start + clip.durationMs
        val overlaps = destinationClips.any { it.startMs < end && start < it.endMs }
        if (overlaps) return project

        val removed = project.mapTrack(sourceTrack.id) { t -> t.copy(clips = t.clips.filterNot { it.id == clipId }) }
        return removed.mapTrack(destinationId) { t ->
            t.copy(clips = (t.clips + clip.copy(startMs = start)).sortedBy { it.startMs })
        }
    }

    private fun isTrackCompatible(track: Track, clip: Clip, project: EditorProject): Boolean {
        val asset = project.asset(clip.assetId) ?: return track.type == TrackType.VIDEO
        return when (asset.type) {
            com.cinematicstudio.ai.data.model.MediaType.AUDIO -> track.type == TrackType.AUDIO
            else -> track.type == TrackType.VIDEO
        }
    }

    // ----------------------------------------------------------------- snapping

    /** Candidate snap targets: clip edges, timeline start and the playhead. */
    fun snapPoints(project: EditorProject, excludeClipId: String? = null, playheadMs: Long? = null): List<Long> {
        val points = mutableListOf(0L)
        playheadMs?.let { points += it }
        project.timeline.tracks.forEach { track ->
            track.clips.forEach { clip ->
                if (clip.id != excludeClipId) {
                    points += clip.startMs
                    points += clip.endMs
                }
            }
        }
        return points
    }

    /** Returns [value] moved to the nearest snap point within [thresholdMs], else [value]. */
    fun snap(
        project: EditorProject,
        value: Long,
        thresholdMs: Long,
        excludeClipId: String? = null,
        playheadMs: Long? = null,
    ): Long {
        var best = value
        var bestDistance = thresholdMs + 1
        for (point in snapPoints(project, excludeClipId, playheadMs)) {
            val distance = kotlin.math.abs(point - value)
            if (distance < bestDistance) {
                bestDistance = distance
                best = point
            }
        }
        return if (bestDistance <= thresholdMs) best else value
    }

    // ------------------------------------------------------------------ queries

    /** Clip under the playhead on a track, if any. */
    fun clipAt(project: EditorProject, trackId: String, timeMs: Long): Clip? =
        project.timeline.track(trackId)?.clips?.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }
}
