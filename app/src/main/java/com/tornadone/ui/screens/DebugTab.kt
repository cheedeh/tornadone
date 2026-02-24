package com.tornadone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornadone.ui.theme.StateStroke1
import com.tornadone.ui.theme.StateTriggered
import java.io.File

@Composable
fun DebugTab(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Event Log",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.log.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear logs",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
        if (state.log.isEmpty()) {
            item {
                Text(
                    "No events yet. Slash a Z in the air!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        items(state.log) { entry ->
            val color = when {
                entry.contains(">>> Z!") -> StateTriggered
                entry.contains("TASK:") -> StateTriggered
                entry.contains("REJECTED:") -> StateStroke1
                entry.contains("VOICE:") -> MaterialTheme.colorScheme.primary
                entry.contains("VOICE ERROR:") -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            }
            Text(
                entry,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }

        if (state.rejectedRecordings.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Rejected Recordings (${state.rejectedRecordings.size})",
                    style = MaterialTheme.typography.headlineMedium,
                    color = StateStroke1,
                )
            }
            items(state.rejectedRecordings) { rejected ->
                RejectedItem(rejected)
            }
        }
    }
}

@Composable
private fun RejectedItem(rejected: RejectedRecording) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "\"${rejected.text}\"",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = StateStroke1,
            )
            Text(
                rejected.timestamp.substringBefore("."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            if (rejected.audioPath != null && File(rejected.audioPath).exists()) {
                AudioPlayButton(rejected.audioPath, tint = StateStroke1)
            }
        }
    }
}

