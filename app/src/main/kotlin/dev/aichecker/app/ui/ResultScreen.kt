package dev.aichecker.app.ui

import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.aichecker.app.data.Report
import dev.aichecker.app.data.Source
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(report: Report, onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val color = verdictColor(report.verdict, dark)
    val percent = (report.aiProbability * 100).roundToInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Result") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        val text = buildString {
                            append("\"${report.title}\" by ${report.artist}: $percent% AI likelihood (${report.verdict.label})")
                            report.spotifyUrl?.let { append("\n$it") }
                        }
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Share result"))
                    }) { Icon(Icons.Default.Share, contentDescription = "Share result") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Artwork(report.coverUrl, 160)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(report.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(report.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LikelihoodGauge(report.aiProbability, color)

            Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
                Text(
                    report.verdict.label,
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            Text(
                report.verdict.explanation,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (report.segments.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Section by section", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Each bar scores a 10-second window on its own. Short windows are much noisier than the overall score, so judge by the score above, not single bars.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SegmentTimeline(report.segments, dark)
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Details", style = MaterialTheme.typography.titleSmall)
                    Detail(
                        "Audio analysed",
                        when (report.source) {
                            Source.SPOTIFY_PREVIEW -> "Spotify preview, ${formatSeconds(report.analysedSeconds)}"
                            Source.FILE -> "Uploaded file, ${formatSeconds(report.analysedSeconds)}"
                        },
                    )
                    report.releaseDate?.let { Detail("Released", it) }
                    Detail("Model", report.modelName)
                    Detail("Processed", "On this phone. No audio leaves the device")
                    HorizontalDivider()
                    Text(
                        buildString {
                            append("The detector targets the vocoder fingerprint of Suno (≤ v5) and Udio (≤ v1.5) style generators. ")
                            append("Newer or unseen generators, heavy mastering and re-recorded vocals can hide it, so a low score is not proof of human work. ")
                            if (report.source == Source.SPOTIFY_PREVIEW) {
                                append("This check only used Spotify's 30-second preview; checking the full file gives a steadier result.")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            report.spotifyUrl?.let { url ->
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open in Spotify")
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Check another song") }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}
