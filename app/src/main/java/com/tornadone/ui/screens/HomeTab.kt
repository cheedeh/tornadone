package com.tornadone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornadone.gesture.OnnxGestureClassifier
import com.tornadone.ui.theme.StateIdle
import com.tornadone.ui.theme.StateStroke1
import com.tornadone.ui.theme.StateTriggered
import java.io.File

@Composable
fun HomeTab(
    viewModel: HomeViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Tornadone",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        // Service control
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = { if (state.serviceRunning) onStopService() else onStartService() }) {
                    Text(if (state.serviceRunning) "Stop Service" else "Start Service")
                }
                Text(
                    if (state.serviceRunning) "Active" else "Stopped",
                    color = if (state.serviceRunning) StateTriggered else StateIdle,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // Power saving indicator
        if (state.isPowerSaving) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(StateStroke1),
                    )
                    Text(
                        "Power saving — sensors paused",
                        style = MaterialTheme.typography.titleMedium,
                        color = StateStroke1,
                    )
                }
            }
        }

        // Gesture state
        val stateColor = when {
            state.isPowerSaving -> StateStroke1
            state.isListening -> MaterialTheme.colorScheme.primary
            state.isTranscribing -> MaterialTheme.colorScheme.tertiary
            state.gestureState == OnnxGestureClassifier.State.COLLECTING -> StateStroke1
            else -> StateIdle
        }
        val stateLabel = when {
            state.isPowerSaving -> "POWER SAVING"
            state.isTranscribing -> "TRANSCRIBING..."
            state.isListening -> "LISTENING..."
            else -> state.gestureState.name
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(stateColor),
                )
                Text(
                    "State: $stateLabel",
                    style = MaterialTheme.typography.titleMedium,
                    color = stateColor,
                )
            }
        }

        // Detection count
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Detections",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${state.detectionCount}",
                    style = MaterialTheme.typography.headlineLarge,
                    color = StateTriggered,
                )
            }
        }

        // Last transcription
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Last Transcription",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.lastRecordingPath != null && !state.isRetranscribing && state.voiceEngine != "google" && state.developerModeEnabled) {
                        OutlinedButton(onClick = { viewModel.retranscribeLastRecording() }) {
                            Text("Re-transcribe")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.lastTranscription ?: "No transcriptions yet. Slash a gesture to start!",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.lastTranscription != null)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    val recordingPath = state.lastRecordingPath
                    if (recordingPath != null && File(recordingPath).exists()) {
                        AudioPlayButton(recordingPath, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

