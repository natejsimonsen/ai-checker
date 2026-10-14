package dev.aichecker.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.aichecker.app.CheckState
import dev.aichecker.app.data.Report
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: CheckState,
    history: List<Report>,
    onCheckLink: (String) -> Unit,
    onCheckFile: (android.net.Uri) -> Unit,
    onOpenReport: (Report) -> Unit,
    onDismissError: () -> Unit,
    onClearHistory: () -> Unit,
) {
    var link by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onCheckFile(uri)
    }
    val busy = state is CheckState.Working
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("AI Song Checker") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Check a Spotify song", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Paste a track link, or tap Share → AI Song Checker in the Spotify app.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !busy,
                            placeholder = { Text("https://open.spotify.com/track/…") },
                            leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    scope.launch {
                                        val text = clipboard.getClipEntry()?.clipData
                                            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                        if (!text.isNullOrBlank()) link = text
                                    }
                                }) { Icon(Icons.Default.ContentPaste, contentDescription = "Paste") }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (link.isNotBlank()) onCheckLink(link) }),
                        )
                        Button(
                            onClick = { onCheckLink(link) },
                            enabled = !busy && link.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Analyze")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        OutlinedButton(
                            onClick = { pickAudio.launch(arrayOf("audio/*")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Check an audio file instead")
                        }
                    }
                }
            }

            when (state) {
                is CheckState.Working -> item { ProgressCard(state) }
                is CheckState.Failed -> item { ErrorCard(state.message, onDismissError) }
                CheckState.Idle -> Unit
            }

            if (history.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent checks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearHistory) { Text("Clear") }
                    }
                }
                items(history, key = { it.id }) { report -> HistoryRow(report) { onOpenReport(report) } }
            } else {
                item { HowItWorks() }
            }
        }
    }
}

@Composable
private fun ProgressCard(state: CheckState.Working) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(state.step.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun HistoryRow(report: Report, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val tint = probabilityColor(report.aiProbability, dark)
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            headlineContent = { Text(report.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text("${report.artist} · ${report.verdict.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = { Artwork(report.coverUrl, 48) },
            trailingContent = {
                Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.18f)) {
                    Text(
                        "${(report.aiProbability * 100).roundToInt()}%",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = tint,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
        )
    }
}

@Composable
fun Artwork(url: String?, sizeDp: Int) {
    val shape = RoundedCornerShape(if (sizeDp > 100) 24.dp else 8.dp)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = "Cover art",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(shape),
        )
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(sizeDp.dp)) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding((sizeDp / 4).dp),
            )
        }
    }
}

@Composable
private fun HowItWorks() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How it works", style = MaterialTheme.typography.titleMedium)
            val body = MaterialTheme.typography.bodyMedium
            val c = MaterialTheme.colorScheme.onSurfaceVariant
            Text("1. The app grabs Spotify's public 30-second preview of the song.", style = body, color = c)
            Text(
                "2. AI generators such as Suno and Udio build audio with neural vocoders that leave a faint, regular comb of peaks in the frequency spectrum.",
                style = body, color = c,
            )
            Text(
                "3. An on-device model scores that fingerprint for the whole clip and for each 10-second window. Nothing is uploaded anywhere.",
                style = body, color = c,
            )
            Text(
                "Treat the score as a signal, not proof. Upload the full track for the most reliable read.",
                style = MaterialTheme.typography.bodySmall, color = c,
            )
        }
    }
}
