package com.cinematicstudio.ai.data

import com.cinematicstudio.ai.data.model.Clip
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.data.model.MediaAsset
import com.cinematicstudio.ai.data.model.MediaType
import com.cinematicstudio.ai.data.model.ProjectSettings
import com.cinematicstudio.ai.data.model.SCHEMA_VERSION
import com.cinematicstudio.ai.data.model.Timeline
import com.cinematicstudio.ai.data.model.Track
import com.cinematicstudio.ai.data.model.TrackType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Versioned project file format (schema v1).
 *
 * The file stores only edit metadata — clip references, in/out points, tracks
 * and settings. Source media stays where it is, which keeps projects small and
 * makes missing-media relinking possible.
 */
object ProjectJson {

    fun toJson(project: EditorProject): String {
        val root = JSONObject().apply {
            put("schemaVersion", project.schemaVersion)
            put("id", project.id)
            put("name", project.name)
            put("createdAt", project.createdAt)
            put("updatedAt", project.updatedAt)
            put("settings", JSONObject().apply {
                put("width", project.settings.width)
                put("height", project.settings.height)
                put("frameRate", project.settings.frameRate)
                put("aspectRatio", project.settings.aspectRatio)
            })
            put("assets", JSONArray().apply {
                project.assets.forEach { put(assetToJson(it)) }
            })
            put("tracks", JSONArray().apply {
                project.timeline.tracks.forEach { put(trackToJson(it)) }
            })
        }
        return root.toString(2)
    }

    /**
     * Parses a project file. Throws [JSONException] for malformed content and
     * [SchemaException] when the file was written by a newer app version, so the
     * caller can keep the original file instead of overwriting it.
     */
    fun fromJson(json: String): EditorProject {
        val root = JSONObject(json)
        val version = root.optInt("schemaVersion", 1)
        if (version > SCHEMA_VERSION) {
            throw SchemaException("Project schema $version is newer than supported $SCHEMA_VERSION")
        }
        val id = root.getString("id")
        val settingsJson = root.optJSONObject("settings") ?: JSONObject()
        val settings = ProjectSettings(
            width = settingsJson.optInt("width", 1920),
            height = settingsJson.optInt("height", 1080),
            frameRate = settingsJson.optInt("frameRate", 30),
            aspectRatio = settingsJson.optString("aspectRatio", "16:9"),
        )
        val assets = mutableListOf<MediaAsset>()
        val assetsJson = root.optJSONArray("assets") ?: JSONArray()
        for (i in 0 until assetsJson.length()) {
            assetsJson.optJSONObject(i)?.let { assets += assetFromJson(it) }
        }
        val tracks = mutableListOf<Track>()
        val tracksJson = root.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until tracksJson.length()) {
            tracksJson.optJSONObject(i)?.let { tracks += trackFromJson(it) }
        }
        return EditorProject(
            id = id,
            name = root.optString("name", "Untitled"),
            schemaVersion = version,
            createdAt = root.optLong("createdAt", 0L),
            updatedAt = root.optLong("updatedAt", 0L),
            settings = settings,
            assets = assets,
            timeline = Timeline(tracks),
        )
    }

    fun fromJsonOrNull(json: String): EditorProject? = try {
        fromJson(json)
    } catch (e: JSONException) {
        null
    }

    private fun assetToJson(asset: MediaAsset): JSONObject = JSONObject().apply {
        put("id", asset.id)
        put("uri", asset.uri)
        put("displayName", asset.displayName)
        put("mimeType", asset.mimeType)
        put("type", asset.type.name)
        put("durationMs", asset.durationMs)
        put("width", asset.width)
        put("height", asset.height)
        put("sizeBytes", asset.sizeBytes)
    }

    private fun assetFromJson(json: JSONObject): MediaAsset = MediaAsset(
        id = json.getString("id"),
        uri = json.getString("uri"),
        displayName = json.optString("displayName", "media"),
        mimeType = json.optString("mimeType", ""),
        type = runCatching { MediaType.valueOf(json.getString("type")) }.getOrDefault(MediaType.VIDEO),
        durationMs = json.optLong("durationMs", 0L),
        width = json.optInt("width", 0),
        height = json.optInt("height", 0),
        sizeBytes = json.optLong("sizeBytes", 0L),
    )

    private fun trackToJson(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("type", track.type.name)
        put("name", track.name)
        put("muted", track.muted)
        put("hidden", track.hidden)
        put("locked", track.locked)
        put("clips", JSONArray().apply { track.clips.forEach { put(clipToJson(it)) } })
    }

    private fun trackFromJson(json: JSONObject): Track {
        val clips = mutableListOf<Clip>()
        val clipsJson = json.optJSONArray("clips") ?: JSONArray()
        for (i in 0 until clipsJson.length()) {
            clipsJson.optJSONObject(i)?.let { clips += clipFromJson(it) }
        }
        return Track(
            id = json.getString("id"),
            type = runCatching { TrackType.valueOf(json.getString("type")) }.getOrDefault(TrackType.VIDEO),
            name = json.optString("name", "Track"),
            clips = clips.sortedBy { it.startMs },
            muted = json.optBoolean("muted", false),
            hidden = json.optBoolean("hidden", false),
            locked = json.optBoolean("locked", false),
        )
    }

    private fun clipToJson(clip: Clip): JSONObject = JSONObject().apply {
        put("id", clip.id)
        put("assetId", clip.assetId)
        put("startMs", clip.startMs)
        put("durationMs", clip.durationMs)
        put("sourceInMs", clip.sourceInMs)
        clip.label?.let { put("label", it) }
    }

    private fun clipFromJson(json: JSONObject): Clip = Clip(
        id = json.getString("id"),
        assetId = json.getString("assetId"),
        startMs = json.getLong("startMs"),
        durationMs = json.getLong("durationMs"),
        sourceInMs = json.optLong("sourceInMs", 0L),
        label = if (json.isNull("label")) null else json.optString("label", null),
    )

    class SchemaException(message: String) : Exception(message)
}
