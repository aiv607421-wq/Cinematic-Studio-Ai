package com.cinematicstudio.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cinematicstudio.ai.data.ProjectRepository
import com.cinematicstudio.ai.data.ProjectSummary
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.data.model.ProjectSettings
import com.cinematicstudio.ai.data.model.TrackType
import com.cinematicstudio.ai.engine.TimelineOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.util.UUID

/** Home screen: project listing, search and project lifecycle actions. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)

    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _busy = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)

    val query: StateFlow<String> = _query
    val busy: StateFlow<Boolean> = _busy
    val message: StateFlow<String?> = _message

    val projects: StateFlow<List<ProjectSummary>> =
        combine(_projects, _query) { list, query ->
            if (query.isBlank()) list
            else list.filter { it.name.contains(query.trim(), ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            _projects.value = repository.listProjects()
            _busy.value = false
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Creates a project with a video and an audio track ready for editing. */
    fun createProject(name: String, settings: ProjectSettings, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            var project = EditorProject(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "Untitled project" },
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                settings = settings,
            )
            project = TimelineOps.addTrack(project, TrackType.VIDEO, "Video 1")
            project = TimelineOps.addTrack(project, TrackType.AUDIO, "Audio 1")
            val saved = repository.save(project)
            _message.value = "Created ${saved.name}"
            refresh()
            onCreated(saved.id)
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            repository.rename(id, name.ifBlank { "Untitled project" })
            refresh()
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            repository.duplicate(id)
            _message.value = "Project duplicated"
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            _message.value = "Project deleted"
            refresh()
        }
    }
}
