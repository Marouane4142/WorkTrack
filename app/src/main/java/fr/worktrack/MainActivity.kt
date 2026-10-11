package fr.worktrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fr.worktrack.ui.WorkTrackApp
import fr.worktrack.ui.WorkTrackViewModel
import fr.worktrack.ui.theme.WorkTrackTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: WorkTrackViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            WorkTrackTheme(state.settings.preferences.appearance) { WorkTrackApp(viewModel, state) }
        }
    }
}
