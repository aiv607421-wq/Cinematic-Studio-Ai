package com.cinematicstudio.ai.data

import com.cinematicstudio.ai.data.model.Clip
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.data.model.MediaAsset
import com.cinematicstudio.ai.data.model.MediaType
import com.cinematicstudio.ai.data.model.ProjectSettings
import com.cinematicstudio.ai.data.model.SCHEMA_VERSION
import com.cinematicstudio.ai.data.model.Track
import com.cinematicstudio.ai.data.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectJsonTest {

    private fun sampleProject(): EditorProject = EditorProject(
        id = "project-1",
        name = "Sunset reel",
        schemaVersion = SCHEMA_VERSION,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_500_000L,
        settings = ProjectSettings(width = 1080, height = 1920, frameRate = 24, aspectRatio = "9:16"),
        assets = listOf(
            MediaAsset(
                id = "asset-1",
                uri = "content://media/external/file/1",
                displayName = "clip.mp4",
                mimeType = "video/mp4",
                type = MediaType.VIDEO,
                durationMs = 12_345L,
                width = 3840,
                height = 2160,
                sizeBytes = 987_654L,
            ),
        ),
        timeline = com.cinematicstudio.ai.data.model.Timeline(
            tracks = listOf(
                Track(
                    id = "v1",
                    type = TrackType.VIDEO,
                    name = "Video 1",
                    clips = listOf(
                        Clip(id = "c1", assetId = "asset-1", startMs = 0L, durationMs = 4_000L, sourceInMs = 500L, label = "Opening"),
                        Clip(id = "c2", assetId = "asset-1", startMs = 6_000L, durationMs = 2_000L, sourceInMs = 4_500L),
                    ),
                    muted = false,
                    hidden = true,
                    locked = true,
                ),
                Track(id = "a1", type = TrackType.AUDIO, name = "Audio 1"),
            ),
        ),
    )

    @Test
    fun roundTripPreservesEverythingThatIsSerialized() {
        val original = sampleProject()
        val restored = ProjectJson.fromJson(ProjectJson.toJson(original))

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.settings, restored.settings)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.updatedAt, restored.updatedAt)
        assertEquals(original.assets, restored.assets)
        assertEquals(2, restored.timeline.tracks.size)
        assertEquals(original.timeline.tracks[0].hidden, restored.timeline.tracks[0].hidden)
        assertEquals(original.timeline.tracks[0].locked, restored.timeline.tracks[0].locked)
        assertEquals(original.timeline.tracks[0].clips, restored.timeline.tracks[0].clips)
        assertEquals(SCHEMA_VERSION, restored.schemaVersion)
    }

    @Test
    fun clipsAreSortedByStartTimeOnLoad() {
        val json = ProjectJson.toJson(sampleProject())
        val restored = ProjectJson.fromJson(json)
        val clips = restored.timeline.tracks.first().clips
        assertEquals(clips.sortedBy { it.startMs }, clips)
    }

    @Test
    fun futureSchemaIsRejectedSoTheOriginalFileIsNeverOverwritten() {
        val json = ProjectJson.toJson(sampleProject()).replace(
            "\"schemaVersion\": $SCHEMA_VERSION",
            "\"schemaVersion\": 99",
        )
        val thrown = runCatching { ProjectJson.fromJson(json) }.exceptionOrNull()
        assertTrue(thrown is ProjectJson.SchemaException)
    }

    @Test
    fun malformedContentReturnsNullInsteadOfThrowing() {
        assertNull(ProjectJson.fromJsonOrNull("{ not valid json"))
        assertNull(ProjectJson.fromJsonOrNull(""))
        assertNotNull(ProjectJson.fromJsonOrNull(ProjectJson.toJson(sampleProject())))
    }

    @Test
    fun missingOptionalFieldsFallBackToDefaults() {
        val minimal = """{"schemaVersion":1,"id":"x","name":"Minimal"}"""
        val restored = ProjectJson.fromJson(minimal)
        assertEquals("x", restored.id)
        assertEquals(ProjectSettings(), restored.settings)
        assertTrue(restored.assets.isEmpty())
        assertTrue(restored.timeline.tracks.isEmpty())
    }
}
