package dev.aichecker.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aichecker.app.ui.AiCheckerTheme
import dev.aichecker.app.ui.HomeScreen
import dev.aichecker.app.ui.ResultScreen

class MainActivity : ComponentActivity() {

    private val viewModel: CheckerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            AiCheckerTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val history by viewModel.history.reports.collectAsStateWithLifecycle()
                val report by viewModel.openReport.collectAsStateWithLifecycle()

                BackHandler(enabled = report != null) { viewModel.open(null) }

                AnimatedContent(targetState = report, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { open ->
                    if (open == null) {
                        HomeScreen(
                            state = state,
                            history = history,
                            onCheckLink = viewModel::checkSpotify,
                            onCheckFile = viewModel::checkFile,
                            onOpenReport = viewModel::open,
                            onDismissError = viewModel::dismissError,
                            onClearHistory = { viewModel.clearHistory() },
                        )
                    } else {
                        ResultScreen(open, onBack = { viewModel.open(null) })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Spotify's share sheet sends text; file managers send an audio stream; links arrive as VIEW. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null && intent.type?.startsWith("audio/") == true -> {
                        viewModel.open(null)
                        viewModel.checkFile(stream)
                    }
                    !text.isNullOrBlank() -> {
                        viewModel.open(null)
                        viewModel.checkSpotify(text)
                    }
                }
            }
            Intent.ACTION_VIEW -> intent.dataString?.let {
                viewModel.open(null)
                viewModel.checkSpotify(it)
            }
        }
    }
}
