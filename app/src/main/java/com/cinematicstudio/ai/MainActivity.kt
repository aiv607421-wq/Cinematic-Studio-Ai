package com.cinematicstudio.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.cinematicstudio.ai.ui.editor.EditorScreen
import com.cinematicstudio.ai.ui.home.HomeScreen
import com.cinematicstudio.ai.ui.theme.CinematicStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CinematicStudioTheme {
                AppRoot()
            }
        }
    }
}

/** Root navigation: two destinations, kept deliberately simple. */
@Composable
private fun AppRoot() {
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    if (projectId == null) {
        HomeScreen(onOpenProject = { id -> projectId = id })
    } else {
        EditorScreen(
            projectId = projectId!!,
            onBack = { projectId = null },
        )
    }
}
