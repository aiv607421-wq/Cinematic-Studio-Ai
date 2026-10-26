package com.cinematicstudio.ai.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cinematicstudio.ai.data.MediaLibrary
import com.cinematicstudio.ai.data.MediaStorePublisher
import com.cinematicstudio.ai.data.ProjectRepository
import com.cinematicstudio.ai.data.model.Clip
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.data.model.MediaAsset
import com.cinematicstudio.ai.data.model.TrackType
import com.cinematicstudio.ai.engine.ExportPlanner
import com.cinematicstudio.ai.engine.ExportRenderer
import com.cinematicstudio.ai.engine.History
import com.cinematicstudio.ai.engine.TimelineOps
import com.cinematicstudio.ai.engine.Timecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class EditorUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    val pixelsPerMs: Float = 0.06f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
    val missingMediaCount: Int = 0,
    val recovered: Boolean = false,
    val importedCount: Int = 0,
)

data class ExportUiState(
    val running: Boolean = false,
    val percent: Int = 0,
    val outputPath: String? = null,
    val error: String? = null,
    val publishedToGallery: Boolean = false,
    val cancelled: Boolean = false,
)

/**
 * Editor session: owns the project, the undo/redo history, playback head,
 * selection, autosave and the export pipeline.
 *
 * All edits go through [commit], which pushes an undo snapshot and schedules an
 * autosave, so no edit can be silently lost.
 */
class EditorViewModel(
    application: Application,
    private val projectId: String,
) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val mediaLibrary = MediaLibrary(application)
    private val exportRenderer = ExportRenderer(application)

    private var history: History? = null
    private var autosaveJob: Job? = null

    private val _project = MutableStateFlow<EditorProject?>(null)
    val project: StateFlow<EditorProject?> = _project

    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui

    private val _export = MutableStateFlow(ExportUiState())
    val export: StateFlow<ExportUiState> = _export

    val frameDurationMs: Long
        get() = _project.value?.settings?.frameDurationMs ?: 40L

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val loaded = repository.load(projectId)
            if (loaded == null) {
                _ui.value = _ui.value.copy(loading = false, error = "Project not found")
                return@launch
            }
            val recovered = runCatching {
                File(filesDir(), "$projectId.autosave.json").exists()
            }.getOrDefault(false)
            history = History(loaded)
            _project.value = loaded
            _ui.value = EditorUiState(
                loading = false,
                missingMediaCount = loaded.missingAssetIds.size,
                recovered = recovered,
            )
        }
    }

    private fun filesDir(): File = getApplication<Application>().filesDir

    // ------------------------------------------------------------------ edits

    /** Applies [transform], pushes an undo snapshot and schedules an autosave. */
    fun commit(transform: (EditorProject) -> EditorProject) {
        val h = history ?: return
        val next = transform(h.project)
        if (next == h.project) return
        h.commit(next)
        _project.value = h.project
        _ui.value = _ui.value.copy(
            canUndo = h.canUndo,
            canRedo = h.canRedo,
            missingMediaCount = h.project.missingAssetIds.size,
        )
        scheduleAutosave()
    }

    fun undo() {
        val h = history ?: return
        if (!h.undo()) return
        _project.value = h.project
        _ui.value = _ui.value.copy(canUndo = h.canUndo, canRedo = h.canRedo, message = "Undo")
        scheduleAutosave()
    }

    fun redo() {
        val h = history ?: return
        if (!h.redo()) return
        _project.value = h.project
        _ui.value = _ui.value.copy(canUndo = h.canUndo, canRedo = h.canRedo, message = "Redo")
        scheduleAutosave()
    }

    fun scheduleAutosave() {
        val snapshot = _project.value ?: return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            repository.autosave(snapshot)
        }
    }

    fun saveNow(onDone: (() -> Unit)? = null) {
        val snapshot = _project.value ?: return
        viewModelScope.launch {
            val saved = repository.save(snapshot)
            _project.value = saved
            history?.reset(saved)
            _ui.value = _ui.value.copy(canUndo = false, canRedo = false, message = "Project saved")
            onDone?.invoke()
        }
    }

    // ------------------------------------------------------------- selection

    fun selectClip(clipId: String?) {
        _ui.value = _ui.value.copy(selectedClipId = clipId)
    }

    fun setPlayhead(ms: Long) {
        _ui.value = _ui.value.copy(playheadMs = ms.coerceAtLeast(0L))
    }

    fun setZoom(pixelsPerMs: Float) {
        _ui.value = _ui.value.copy(pixelsPerMs = pixelsPerMs.coerceIn(MIN_PX_PER_MS, MAX_PX_PER_MS))
    }

    fun setMessage(message: String?) {
        _ui.value = _ui.value.copy(message = message)
    }

    fun selectedClip(): Pair<Clip, TrackType>? {
        val project = _project.value ?: return null
        val clipId = _ui.value.selectedClipId ?: return null
        val found = project.timeline.clip(clipId) ?: return null
        return found.second to found.first.type
    }

    // -------------------------------------------------------------- timeline

    fun splitAtPlayhead() {
        val project = _project.value ?: return
        val playhead = _ui.value.playheadMs
        val clipId = _ui.value.selectedClipId?.takeIf { id ->
            project.timeline.clip(id)?.second?.let { playhead > it.startMs && playhead < it.endMs } == true
        } ?: project.timeline.tracks.firstNotNullOfOrNull { track ->
            TimelineOps.clipAt(project, track.id, playhead)?.id
        }
        if (clipId == null) {
            setMessage("Nothing to split at the playhead")
            return
        }
        val clipsBefore = project.timeline.tracks.sumOf { it.clips.size }
        commit { TimelineOps.splitClip(it, clipId, playhead) }
        val clipsAfter = _project.value?.timeline?.tracks?.sumOf { it.clips.size } ?: clipsBefore
        setMessage(
            if (clipsAfter > clipsBefore) "Split at ${Timecode.formatTimecode(playhead, project.settings.frameRate)}"
            else "Cannot split here — too close to a clip edge"
        )
    }

    fun deleteSelected(ripple: Boolean = true) {
        val clipId = _ui.value.selectedClipId ?: return
        commit { TimelineOps.deleteClip(it, clipId, ripple) }
        _ui.value = _ui.value.copy(selectedClipId = null, message = if (ripple) "Clip deleted (ripple)" else "Clip deleted")
    }

    fun duplicateSelected() {
        val clipId = _ui.value.selectedClipId ?: return
        commit { TimelineOps.duplicateClip(it, clipId) }
        setMessage("Clip duplicated")
    }

    fun trimClip(clipId: String, newStartMs: Long, newEndMs: Long) {
        commit { TimelineOps.trimClip(it, clipId, newStartMs, newEndMs) }
    }

    fun moveClip(clipId: String, newStartMs: Long, targetTrackId: String? = null) {
        commit { TimelineOps.moveClip(it, clipId, newStartMs, targetTrackId) }
    }

    fun snapValue(value: Long, thresholdMs: Long, excludeClipId: String?): Long {
        val project = _project.value ?: return value
        return TimelineOps.snap(project, value, thresholdMs, excludeClipId, _ui.value.playheadMs)
    }

    fun toggleTrack(trackId: String, muted: Boolean? = null, hidden: Boolean? = null, locked: Boolean? = null) {
        commit { TimelineOps.setTrackFlags(it, trackId, muted, hidden, locked) }
    }

    fun addTrack(type: TrackType) {
        commit { TimelineOps.addTrack(it, type) }
        setMessage("Track added")
    }

    // ----------------------------------------------------------------- media

    fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val existing = _project.value?.assets.orEmpty()
        viewModelScope.launch {
            val imported = mediaLibrary.import(uris, existing)
            if (imported.isEmpty()) {
                setMessage("No new media imported")
                return@launch
            }
            commit { p -> p.copy(assets = p.assets + imported) }
            _ui.value = _ui.value.copy(importedCount = _ui.value.importedCount + imported.size)
            setMessage("Imported ${imported.size} file${if (imported.size == 1) "" else "s"}")
        }
    }

    /** Places an asset on the first compatible track at the playhead (or appends). */
    fun addAssetToTimeline(assetId: String, atPlayhead: Boolean = true) {
        val project = _project.value ?: return
        val asset = project.asset(assetId) ?: return
        val trackType = if (asset.type == com.cinematicstudio.ai.data.model.MediaType.AUDIO) {
            TrackType.AUDIO
        } else TrackType.VIDEO
        val track = project.timeline.tracks.firstOrNull { it.type == trackType && !it.locked } ?: return
        val duration = when {
            asset.type == com.cinematicstudio.ai.data.model.MediaType.IMAGE -> mediaLibrary.imageDefaultDurationMs
            asset.durationMs > 0 -> asset.durationMs
            else -> 5_000L
        }
        val clip = Clip(
            id = UUID.randomUUID().toString(),
            assetId = asset.id,
            startMs = if (atPlayhead) _ui.value.playheadMs else 0L,
            durationMs = duration,
            sourceInMs = 0L,
            label = asset.displayName,
        )
        var placedId = clip.id
        commit { p ->
            val (next, placed) = TimelineOps.insertAt(p, track.id, clip, clip.startMs)
            placedId = placed.id
            next
        }
        _ui.value = _ui.value.copy(selectedClipId = placedId, message = "Added ${asset.displayName}")
    }

    fun relinkAsset(assetId: String, newUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val probed = mediaLibrary.probe(newUri) ?: return@launch
            commit { p ->
                p.copy(
                    assets = p.assets.map {
                        if (it.id == assetId) it.copy(uri = newUri.toString(), displayName = probed.displayName)
                        else it
                    },
                    missingAssetIds = p.missingAssetIds.filterNot { it == assetId },
                )
            }
            withContext(Dispatchers.Main) { setMessage("Media relinked") }
        }
    }

    // ---------------------------------------------------------------- preview

    /** The composition consumed by the preview player (same mapping as export). */
    fun buildComposition() = _project.value?.let { ExportPlanner.buildComposition(it) }

    fun exportGaps(): List<ExportPlanner.GapInfo> =
        _project.value?.let { ExportPlanner.describeGaps(it) }.orEmpty()

    val currentTimecode: String
        get() = Timecode.formatTimecode(_ui.value.playheadMs, _project.value?.settings?.frameRate ?: 30)

    // ----------------------------------------------------------------- export

    fun startExport(fileName: String) {
        val project = _project.value ?: return
        if (_export.value.running) return
        if (project.missingAssetIds.isNotEmpty()) {
            _export.value = ExportUiState(error = "Relink missing media before exporting")
            return
        }
        val composition = ExportPlanner.buildComposition(project)
        if (composition == null) {
            _export.value = ExportUiState(error = "There is nothing to render yet — add clips to the timeline")
            return
        }
        val output = createOutputFile(fileName)
        _export.value = ExportUiState(running = true, percent = 0)
        exportRenderer.start(composition, output.absolutePath, object : ExportRenderer.Callback {
            override fun onProgress(percent: Int) {
                _export.value = _export.value.copy(percent = percent.coerceIn(0, 100))
            }

            override fun onCompleted(outputPath: String, result: androidx.media3.transformer.ExportResult) {
                _export.value = ExportUiState(percent = 100, outputPath = outputPath)
            }

            override fun onError(message: String, cause: Throwable?) {
                _export.value = ExportUiState(error = message)
            }

            override fun onCancelled() {
                _export.value = ExportUiState(cancelled = true)
            }
        })
    }

    fun cancelExport() {
        exportRenderer.cancel()
    }

    fun clearExportState() {
        _export.value = ExportUiState()
    }

    fun publishToGallery(path: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { MediaStorePublisher.publish(getApplication(), File(path)) }.getOrDefault(null) != null
            }
            _export.value = _export.value.copy(publishedToGallery = ok)
            if (!ok) setMessage("Could not save to the gallery on this Android version")
        }
    }

    private fun createOutputFile(fileName: String): File {
        val context = getApplication<Application>()
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        val dir = File(base, OUTPUT_FOLDER)
        dir.mkdirs()
        val safeName = fileName.ifBlank { "Cinematic Studio AI" }
            .replace(Regex("[^A-Za-z0-9 _.-]"), "")
            .trim()
            .ifBlank { "export" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${safeName}_$stamp.mp4")
    }

    override fun onCleared() {
        exportRenderer.cancel()
        super.onCleared()
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 1_200L
        private const val OUTPUT_FOLDER = "Cinematic Studio AI"
        const val MIN_PX_PER_MS = 0.008f
        const val MAX_PX_PER_MS = 1.2f

        fun factory(application: Application, projectId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(EditorViewModel::class.java)) {
                        "Unknown ViewModel ${modelClass.name}"
                    }
                    return EditorViewModel(application = application, projectId = projectId) as T
                }
            }
    }
}
