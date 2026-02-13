package com.tornado.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornado.ui.theme.StateTriggered
import com.tornado.voice.ModelState
import com.tornado.voice.WhisperLanguage
import com.tornado.voice.WhisperModel

@Composable
fun OptionsTab(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Options",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        // Voice model selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            val isDownloading = state.modelState is ModelState.Downloading
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Voice Model",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhisperModel.entries.forEach { model ->
                        val selected = model == state.selectedModel
                        if (selected) {
                            Button(onClick = {}, enabled = false) {
                                Text(model.displayName)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.setModel(model) },
                                enabled = !isDownloading,
                            ) {
                                Text(model.displayName)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (val ms = state.modelState) {
                    is ModelState.NotDownloaded -> {
                        OutlinedButton(onClick = { viewModel.downloadModel() }) {
                            Text("Download ${state.selectedModel.displayName} (~${state.selectedModel.totalSizeMB} MB)")
                        }
                    }
                    is ModelState.Downloading -> {
                        Text(
                            "Downloading ${state.selectedModel.displayName}... ${(ms.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { ms.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is ModelState.Ready -> {
                        Text(
                            "${ms.model.displayName} ready",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StateTriggered,
                        )
                    }
                    is ModelState.Error -> {
                        Text(
                            "Error: ${ms.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.downloadModel() }) {
                            Text("Retry Download")
                        }
                    }
                }
            }
        }

        // Language selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            val isDownloading = state.modelState is ModelState.Downloading
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Language",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhisperLanguage.entries.forEach { lang ->
                        val selected = lang == state.selectedLanguage
                        if (selected) {
                            Button(onClick = {}, enabled = false) {
                                Text(lang.label)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.setLanguage(lang) },
                                enabled = !isDownloading,
                            ) {
                                Text(lang.label)
                            }
                        }
                    }
                }
            }
        }
    }
}
