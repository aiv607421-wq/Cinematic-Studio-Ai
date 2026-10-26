package com.cinematicstudio.ai.engine

import com.cinematicstudio.ai.data.model.Clip
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.data.model.MediaAsset
import com.cinematicstudio.ai.data.model.MediaType
import com.cinematicstudio.ai.data.model.ProjectSettings
import com.cinematicstudio.ai.data.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOpsTest {

    private fun baseProject(): EditorProject {
        var project = EditorProject(
            id = "p1",
            name = "Test",
            createdAt = 0L,
            updatedAt = 0L,
            settings = ProjectSettings(width = 1920, height = 1080, frameRate = 30, aspectRatio = "16:9"),
            assets = listOf(
                MediaAsset(
                    id = "a1",
                    uri = "content://media/1",
                    displayName = "one.mp4",
                    type = MediaType.VIDEO,
                    durationMs = 10_000L,
                    width = 1920,
                    height = 1080,
                ),
                MediaAsset(
                    id = "a2",
                    uri = "content://media/2",
                    displayName = "music.mp3",
                    type = MediaType.AUDIO,
                    durationMs = 30_000L,
                ),
            ),
        )
        project = TimelineOps.addTrack(project, TrackType.VIDEO, "Video 1")
        project = TimelineOps.addTrack(project, TrackType.AUDIO, "Audio 1")
        return project
    }

    private fun videoTrackId(project: EditorProject): String =
        project.timeline.tracks.first { it.type == TrackType.VIDEO }.id

    private fun audioTrackId(project: EditorProject): String =
        project.timeline.tracks.first { it.type == TrackType.AUDIO }.id

    private fun addClip(project: EditorProject, trackId: String, startMs: Long, durationMs: Long, sourceInMs: Long = 0L, id: String = "c"): EditorProject =
        TimelineOps.addClip(
            project,
            trackId,
            Clip(id = id, assetId = "a1", startMs = startMs, durationMs = durationMs, sourceInMs = sourceInMs),
        ).first

    @Test
    fun splitProducesTwoAdjacentClipsAndPreservesTotalDuration() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        val project = addClip(base, trackId, startMs = 0L, durationMs = 6_000L, id = "c1")

        val split = TimelineOps.splitClip(project, "c1", 2_500L)
        val clips = split.timeline.track(trackId)!!.clips.sortedBy { it.startMs }

        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startMs)
        assertEquals(2_500L, clips[0].durationMs)
        assertEquals(2_500L, clips[1].startMs)
        assertEquals(3_500L, clips[1].durationMs)
        // The right half continues from the same source position.
        assertEquals(2_500L, clips[1].sourceInMs)
        assertEquals(6_000L, clips.sumOf { it.durationMs })
    }

    @Test
    fun splitOutsideClipOrTooCloseToEdgeIsRejected() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        val project = addClip(base, trackId, startMs = 0L, durationMs = 1_000L, id = "c1")

        assertEquals(project, TimelineOps.splitClip(project, "c1", 5_000L))
        assertEquals(project, TimelineOps.splitClip(project, "c1", -100L))
        // 10ms from the start is below the minimum clip length.
        assertEquals(project, TimelineOps.splitClip(project, "c1", 10L))
    }

    @Test
    fun trimRespectsSourceBounds() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        // Source is 10s long; the clip may never extend past it.
        val project = addClip(base, trackId, startMs = 0L, durationMs = 4_000L, id = "c1")

        val trimmed = TimelineOps.trimClip(project, "c1", newEndMs = 60_000L)
        val clip = trimmed.timeline.track(trackId)!!.clips.single()

        assertEquals(10_000L, clip.endMs)
        assertEquals(10_000L, clip.durationMs)
    }

    @Test
    fun trimNeverOverlapsNeighbouringClips() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        var project = addClip(base, trackId, startMs = 0L, durationMs = 2_000L, id = "c1")
        project = addClip(project, trackId, startMs = 2_000L, durationMs = 2_000L, id = "c2")

        val trimmed = TimelineOps.trimClip(project, "c1", newEndMs = 8_000L)
        val clips = trimmed.timeline.track(trackId)!!.clips.sortedBy { it.startMs }

        assertEquals(2_000L, clips[0].endMs)
        assertEquals(2_000L, clips[1].startMs)
    }

    @Test
    fun trimMovesSourceWindowWithTheStartEdge() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        val project = addClip(base, trackId, startMs = 0L, durationMs = 5_000L, id = "c1")

        val trimmed = TimelineOps.trimClip(project, "c1", newStartMs = 1_500L)
        val clip = trimmed.timeline.track(trackId)!!.clips.single()

        assertEquals(1_500L, clip.startMs)
        assertEquals(3_500L, clip.durationMs)
        assertEquals(1_500L, clip.sourceInMs)
    }

    @Test
    fun moveIsClampedToTheFreeGapBetweenNeighbours() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        var project = addClip(base, trackId, startMs = 0L, durationMs = 2_000L, id = "c1")
        project = addClip(project, trackId, startMs = 4_000L, durationMs = 2_000L, id = "c2")

        // c1 is 2s long and the free gap before c2 is 2s wide, so a request to
        // start at 3.5s must clamp back to 2s instead of overlapping c2.
        val moved = TimelineOps.moveClip(project, "c1", 3_500L)
        val clips = moved.timeline.track(trackId)!!.clips.sortedBy { it.startMs }

        assertEquals(2_000L, clips[0].startMs)
        assertEquals(4_000L, clips[0].endMs)
        assertEquals(4_000L, clips[1].startMs)

        // Pushing it further must not break the no-overlap invariant either.
        val pushed = TimelineOps.moveClip(moved, "c1", 5_000L)
        val afterPush = pushed.timeline.track(trackId)!!.clips.sortedBy { it.startMs }
        assertEquals(2_000L, afterPush[0].startMs)
        assertEquals(4_000L, afterPush[0].endMs)
        assertTrue(afterPush[0].endMs <= afterPush[1].startMs)
    }

    @Test
    fun crossTrackMoveOnlyLandsOnACompatibleTrack() {
        val base = baseProject()
        val videoTrack = videoTrackId(base)
        val audioTrack = audioTrackId(base)
        val project = addClip(base, videoTrack, startMs = 0L, durationMs = 3_000L, id = "c1")

        // Video clip cannot be moved onto the audio track.
        val rejected = TimelineOps.moveClip(project, "c1", 5_000L, audioTrack)
        assertTrue(rejected.timeline.track(videoTrack)!!.clips.isNotEmpty())
        assertTrue(rejected.timeline.track(audioTrack)!!.clips.isEmpty())
    }

    @Test
    fun deleteWithRippleClosesTheGap() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        var project = addClip(base, trackId, startMs = 0L, durationMs = 2_000L, id = "c1")
        project = addClip(project, trackId, startMs = 2_000L, durationMs = 3_000L, id = "c2")

        val deleted = TimelineOps.deleteClip(project, "c1", ripple = true)
        val remaining = deleted.timeline.track(trackId)!!.clips

        assertEquals(1, remaining.size)
        assertEquals(0L, remaining.single().startMs)
        assertEquals(3_000L, remaining.single().durationMs)
    }

    @Test
    fun deleteWithoutRippleKeepsPositions() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        var project = addClip(base, trackId, startMs = 0L, durationMs = 2_000L, id = "c1")
        project = addClip(project, trackId, startMs = 2_000L, durationMs = 3_000L, id = "c2")

        val deleted = TimelineOps.deleteClip(project, "c1", ripple = false)
        val remaining = deleted.timeline.track(trackId)!!.clips

        assertEquals(1, remaining.size)
        assertEquals(2_000L, remaining.single().startMs)
    }

    @Test
    fun lockedTrackRejectsEdits() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        val project = addClip(base, trackId, startMs = 0L, durationMs = 4_000L, id = "c1")
        val locked = TimelineOps.setTrackFlags(project, trackId, locked = true)

        assertEquals(locked, TimelineOps.deleteClip(locked, "c1"))
        assertEquals(locked, TimelineOps.splitClip(locked, "c1", 2_000L))
        assertEquals(locked, TimelineOps.moveClip(locked, "c1", 3_000L))
    }

    @Test
    fun duplicateOpensSpaceWithoutOverlap() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        var project = addClip(base, trackId, startMs = 0L, durationMs = 2_000L, id = "c1")
        project = addClip(project, trackId, startMs = 2_000L, durationMs = 2_000L, id = "c2")

        val duplicated = TimelineOps.duplicateClip(project, "c1")
        val clips = duplicated.timeline.track(trackId)!!.clips.sortedBy { it.startMs }

        assertEquals(3, clips.size)
        val sorted = clips.sortedBy { it.startMs }
        for (i in 0 until sorted.lastIndex) {
            assertTrue(
                "clips must not overlap",
                sorted[i].endMs <= sorted[i + 1].startMs,
            )
        }
    }

    @Test
    fun snappingFindsNearbyClipEdgesAndPlayhead() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        val project = addClip(base, trackId, startMs = 4_000L, durationMs = 3_000L, id = "c1")

        assertEquals(4_000L, TimelineOps.snap(project, 4_050L, thresholdMs = 200L, excludeClipId = null))
        assertEquals(7_000L, TimelineOps.snap(project, 6_950L, thresholdMs = 200L, excludeClipId = null))
        // Beyond the threshold nothing moves.
        assertEquals(5_500L, TimelineOps.snap(project, 5_500L, thresholdMs = 100L, excludeClipId = null))
        // Excluded clip edges are not snap targets.
        assertEquals(4_100L, TimelineOps.snap(project, 4_100L, thresholdMs = 50L, excludeClipId = "c1"))
        assertEquals(2_500L, TimelineOps.snap(project, 2_560L, thresholdMs = 200L, excludeClipId = null, playheadMs = 2_500L))
    }

    @Test
    fun clipAtReturnsTheClipCoveringTheTime() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        val project = addClip(base, trackId, startMs = 1_000L, durationMs = 2_000L, id = "c1")

        assertNull(TimelineOps.clipAt(project, trackId, 500L))
        assertEquals("c1", TimelineOps.clipAt(project, trackId, 1_000L)?.id)
        assertEquals("c1", TimelineOps.clipAt(project, trackId, 2_999L)?.id)
        assertNull(TimelineOps.clipAt(project, trackId, 3_000L))
    }

    @Test
    fun historyUndoRedoWalksBackThroughEdits() {
        val base = baseProject()
        val trackId = videoTrackId(base)
        val history = History(base)

        val first = addClip(base, trackId, startMs = 0L, durationMs = 2_000L, id = "c1")
        history.commit(first)
        val second = TimelineOps.splitClip(history.project, "c1", 1_000L)
        history.commit(second)

        assertEquals(2, history.project.timeline.track(trackId)!!.clips.size)
        assertTrue(history.canUndo)

        history.undo()
        assertEquals(1, history.project.timeline.track(trackId)!!.clips.size)
        assertTrue(history.canRedo)

        history.undo()
        assertEquals(0, history.project.timeline.track(trackId)!!.clips.size)
        assertFalse(history.canUndo)

        history.redo()
        history.redo()
        assertEquals(2, history.project.timeline.track(trackId)!!.clips.size)
        assertFalse(history.canRedo)
    }

    @Test
    fun committingAnIdenticalStateDoesNotGrowHistory() {
        val base = baseProject()
        val history = History(base)
        history.commit(base)
        assertFalse(history.canUndo)
        assertNotEquals(base.id, "")
    }

    @Test
    fun audioAssetsOnlyGoToAudioTracks() {
        val base = baseProject()
        val videoTrack = videoTrackId(base)
        val audioTrack = audioTrackId(base)

        val withAudio = TimelineOps.addClip(
            base,
            audioTrack,
            Clip(id = "ac1", assetId = "a2", startMs = 0L, durationMs = 5_000L),
        ).first

        assertTrue(withAudio.timeline.track(audioTrack)!!.clips.isNotEmpty())
        // Moving that audio clip onto the video track must be rejected.
        val moved = TimelineOps.moveClip(withAudio, "ac1", 0L, videoTrack)
        assertTrue(moved.timeline.track(videoTrack)!!.clips.isEmpty())
        assertEquals(1, moved.timeline.track(audioTrack)!!.clips.size)
    }
}
