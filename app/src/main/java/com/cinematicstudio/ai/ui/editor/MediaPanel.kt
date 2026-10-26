package com.cinematicstudio.ai.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinematicstudio.ai.data.model.MediaAsset
import com.cinematicstudio.ai.data.model.MediaType
import com.cinematicstudio.ai.engine.Timecode
import com.cinematicstudio.ai.ui.components.MediaThumbnail

private val IMPORT_MIME_TYPES = arrayOf("video/*", "image/*", "audio/*")

/**
 * Media browser: imports documents through the Storage Access Framework (no
 * storage permission), shows metadata and places assets onto the timeline.
 */
@Composable
fun MediaPanel(
    assets: List<MediaAsset>,
    missingIds: Set<String>,
    onImport: (List<Uri>) -> Unit,
    onAddToTimeline: (String) -> Unit,
    onRelink: (String, Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }

    var relinkTarget by remember { androidx.compose.runtime.mutableStateOf<MediaAsset?>(null) }
    val relinkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = relinkTarget
        if (uri != null && target != null) onRelink(target.id, uri)
        relinkTarget = null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Media", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { importLauncher.launch(IMPORT_MIME_TYPES) }) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.height(16.dp))
                Spacer(Modifier.height(4.dp))
                Text("Import")
            }
        }
        Spacer(Modifier.height(6.dp))

        if (assets.isEmpty()) {
            Text(
                "No media yet. Import video, photos or audio — nothing is uploaded, files stay on the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(92.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(180.dp),
            ) {
                items(assets, key = { it.id }) { asset ->
                    val missing = asset.id in missingIds
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    if (missing) {
                                        relinkTarget = asset
                                        relinkLauncher.launch(arrayOf("*/*"))
                                    } else {
                                        onAddToTimeline(asset.id)
                                    }
                                },
                        ) {
                            MediaThumbnail(
                                uri = asset.uri,
                                modifier = Modifier.matchParentSize(),
                                contentDescription = asset.displayName,
                            )
                            if (missing) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.75f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.LinkOff,
                                        contentDescription = "Missing media — tap to relink",
                                        tint = MaterialTheme.colorScheme.onError,
                                    )
                                }
                            }
                            Text(
                                asset.type.name.lowercase().take(3),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                        Text(
                            asset.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(
                                    when (asset.type) {
                                        MediaType.IMAGE -> "${asset.width}×${asset.height}"
                                        else -> Timecode.formatDuration(asset.durationMs)
                                    }
                                )
                                if (asset.width > 0 && asset.type != MediaType.IMAGE) {
                                    append(" · ${asset.width}×${asset.height}")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (missing) {
                            Text(
                                "Tap to relink",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
