package com.cinematicstudio.ai.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cinematicstudio.ai.data.ProjectSummary
import com.cinematicstudio.ai.data.model.AspectPreset
import com.cinematicstudio.ai.data.model.ProjectSettings
import com.cinematicstudio.ai.engine.Timecode
import com.cinematicstudio.ai.ui.components.AlertDialogShell
import com.cinematicstudio.ai.ui.components.ConfirmDialog
import com.cinematicstudio.ai.ui.components.FilterChipLike
import com.cinematicstudio.ai.ui.components.MediaThumbnail
import com.cinematicstudio.ai.ui.components.TextPromptDialog
import com.cinematicstudio.ai.viewmodel.HomeViewModel

/**
 * Home screen: create/open/search projects plus rename, duplicate and delete.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenProject: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val projects by viewModel.projects.collectAsState()
    val query by viewModel.query.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var menuFor by remember { mutableStateOf<ProjectSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cinematic Studio AI") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search projects") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create project")
                }
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            Spacer(Modifier.height(12.dp))

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (projects.isEmpty() && !busy) {
                EmptyState(hasQuery = query.isNotBlank())
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onOpen = { onOpenProject(project.id) },
                            onRename = { renameTarget = project },
                            onDuplicate = { viewModel.duplicate(project.id) },
                            onDelete = { deleteTarget = project },
                            menuOpen = menuFor?.id == project.id,
                            onToggleMenu = { menuFor = if (menuFor?.id == project.id) null else project },
                            onDismissMenu = { menuFor = null },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onConfirm = { name, settings ->
                showCreate = false
                viewModel.createProject(name, settings) { id -> onOpenProject(id) }
            },
            onDismiss = { showCreate = false },
        )
    }

    renameTarget?.let { target ->
        TextPromptDialog(
            title = "Rename project",
            label = "Project name",
            initialValue = target.name,
            confirmLabel = "Rename",
            onConfirm = { newName ->
                viewModel.rename(target.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete project?",
            message = "\"${target.name}\" and its timeline will be removed. Source media files are never deleted.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    menuOpen: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            Box {
                MediaThumbnail(
                    uri = project.thumbnailUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentDescription = project.name,
                )
                IconButton(
                    onClick = onToggleMenu,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Project options",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .size(18.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(3.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { onDismissMenu(); onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = { onDismissMenu(); onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { onDismissMenu(); onDelete() },
                    )
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${project.aspectRatio} · ${project.clipCount} clip" +
                        (if (project.clipCount == 1) "" else "s") +
                        (if (project.durationMs > 0) " · ${Timecode.formatDuration(project.durationMs)}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Edited ${Timecode.formatRelativeTime(project.updatedAt, System.currentTimeMillis())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (project.missingMediaCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${project.missingMediaCount} missing media",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(hasQuery: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (hasQuery) "No projects match your search" else "No projects yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasQuery) "Try a different name."
            else "Create a project to import media and start cutting on a multitrack timeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreateProjectDialog(
    onConfirm: (String, ProjectSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("My first cut") }
    var preset by remember { mutableStateOf(AspectPreset.ALL.first()) }
    var frameRate by remember { mutableStateOf(30) }

    AlertDialogShell(
        title = "New project",
        onDismiss = onDismiss,
        onConfirm = { onConfirm(name, ProjectSettings(preset.width, preset.height, frameRate, preset.label)) },
        confirmLabel = "Create",
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Project name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text("Aspect ratio", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AspectPreset.ALL.forEach { option ->
                FilterChipLike(
                    label = option.label,
                    selected = option.label == preset.label,
                    onClick = { preset = option },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Frame rate", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24, 25, 30, 60).forEach { fps ->
                FilterChipLike(
                    label = "${fps}p",
                    selected = fps == frameRate,
                    onClick = { frameRate = fps },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Output ${preset.width}×${preset.height} at ${frameRate} fps. " +
                "Export resolution follows the source clips in Phase 1.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
