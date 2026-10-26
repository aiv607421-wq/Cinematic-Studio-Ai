package com.cinematicstudio.ai.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.data.model.MediaAsset
import com.cinematicstudio.ai.data.model.SCHEMA_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Lightweight listing entry for the home screen. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val clipCount: Int,
    val durationMs: Long,
    val aspectRatio: String,
    val thumbnailUri: String?,
    val missingMediaCount: Int,
    val recovered: Boolean,
)

/**
 * File-backed project store.
 *
 * Layout: filesDir/projects/<id>.json plus <id>.autosave.json.
 * Writes are atomic (temp file + rename) so a crash mid-write can never
 * truncate an existing project. The autosave file is newer than the saved
 * project between explicit saves; on load the newest of the two wins, which is
 * what makes crash recovery work.
 */
class ProjectRepository(context: Context) {

    private val appContext = context.applicationContext
    private val projectsDir = File(appContext.filesDir, "projects").apply { mkdirs() }

    private fun projectFile(id: String) = File(projectsDir, "$id.json")
    private fun autosaveFile(id: String) = File(projectsDir, "$id.autosave.json")

    suspend fun listProjects(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        projectsDir.listFiles { file -> file.isFile && file.name.endsWith(".json") && !file.name.contains(".autosave") }
            .orEmpty()
            .mapNotNull { file ->
                val id = file.nameWithoutExtension
                val saved = readProjectFile(file) ?: return@mapNotNull null
                val autosave = autosaveFile(id).takeIf { it.exists() }?.let { readProjectFile(it) }
                val project = if (autosave != null && autosave.updatedAt > saved.updatedAt) autosave else saved
                summaryFor(project, recovered = autosave != null && autosave.updatedAt > saved.updatedAt)
            }
            .sortedByDescending { it.updatedAt }
    }

    /** Loads a project, preferring a newer autosave snapshot when one exists. */
    suspend fun load(id: String): EditorProject? = withContext(Dispatchers.IO) {
        val saved = readProjectFile(projectFile(id))
        val autosave = autosaveFile(id).takeIf { it.exists() }?.let { readProjectFile(it) }
        val project = when {
            saved == null && autosave == null -> null
            saved == null -> autosave
            autosave == null -> saved
            autosave.updatedAt > saved.updatedAt -> autosave
            else -> saved
        } ?: return@withContext null
        project.copy(missingAssetIds = findMissingAssets(project.assets))
    }

    suspend fun save(project: EditorProject) = withContext(Dispatchers.IO) {
        val stamped = project.copy(updatedAt = System.currentTimeMillis(), schemaVersion = SCHEMA_VERSION)
        writeAtomic(projectFile(stamped.id), ProjectJson.toJson(stamped))
        // An explicit save supersedes any pending autosave snapshot.
        autosaveFile(stamped.id).delete()
        stamped
    }

    /** Cheap checkpoint written after each committed edit. */
    suspend fun autosave(project: EditorProject) = withContext(Dispatchers.IO) {
        val stamped = project.copy(updatedAt = System.currentTimeMillis(), schemaVersion = SCHEMA_VERSION)
        writeAtomic(autosaveFile(stamped.id), ProjectJson.toJson(stamped))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        projectFile(id).delete()
        autosaveFile(id).delete()
        Unit
    }

    suspend fun rename(id: String, name: String): EditorProject? {
        val project = load(id) ?: return null
        return save(project.copy(name = name))
    }

    suspend fun duplicate(id: String): ProjectSummary? {
        val source = load(id) ?: return null
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} copy",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        save(copy)
        return summaryFor(copy, recovered = false)
    }

    suspend fun summary(id: String): ProjectSummary? = load(id)?.let { summaryFor(it, recovered = false) }

    private fun summaryFor(project: EditorProject, recovered: Boolean): ProjectSummary = ProjectSummary(
        id = project.id,
        name = project.name,
        createdAt = project.createdAt,
        updatedAt = project.updatedAt,
        clipCount = project.timeline.tracks.sumOf { it.clips.size },
        durationMs = project.timeline.durationMs,
        aspectRatio = project.settings.aspectRatio,
        thumbnailUri = project.assets.firstOrNull()?.uri,
        missingMediaCount = project.missingAssetIds.size,
        recovered = recovered,
    )

    private fun readProjectFile(file: File): EditorProject? {
        if (!file.exists()) return null
        return try {
            ProjectJson.fromJson(file.readText())
        } catch (e: Exception) {
            // Corrupt or future-schema file: keep it on disk, skip it in listings.
            null
        }
    }

    private fun writeAtomic(target: File, contents: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(contents)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    /** Missing-media detection: resolves each source document without opening its bytes. */
    private fun findMissingAssets(assets: List<MediaAsset>): List<String> =
        assets.filterNot { asset -> isUriResolvable(asset.uri) }.map { it.id }

    fun isUriResolvable(uriString: String): Boolean = try {
        val uri = Uri.parse(uriString)
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { true } ?: false
        } else {
            true
        }
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        false
    }

    companion object {
        fun displayNameFor(contentResolver: ContentResolver, uri: Uri): String? = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
