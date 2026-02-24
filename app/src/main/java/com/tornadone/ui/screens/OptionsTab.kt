package com.tornadone.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornadone.ui.theme.StateTriggered
import com.tornadone.voice.ModelState
import com.tornadone.voice.WhisperLanguage
import com.tornadone.voice.WhisperModel

@Composable
fun OptionsTab(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
    onOpenApiSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDownloading = state.modelState is ModelState.Downloading
    val isWhisper = state.voiceEngine == "whisper"

    val modelPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importGestureModel(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Options",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // Language
        OptionCard("Language") {
            LanguageDropdown(
                selected = state.selectedLanguage,
                enabled = !isDownloading,
                onSelect = { viewModel.setLanguage(it) },
            )
        }

        // Task Backends
        OptionCard("Task Backend") {
            val selectedLabel = state.shareTargets
                .firstOrNull { it.packageName == state.selectedShareTarget }
                ?.label ?: "None"
            var expanded by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Share tasks with",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(selectedLabel)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("None") },
                            onClick = { viewModel.setShareTarget(""); expanded = false },
                        )
                        val openTasks = state.shareTargets.filter { it.method == ShareMethod.OPENTASKS }
                        val tasker = state.shareTargets.filter { it.method == ShareMethod.TASKER }
                        val knownShare = state.shareTargets.filter { it.method == ShareMethod.SHARE && it.knownTaskApp }
                        val otherApps = state.shareTargets.filter { it.method == ShareMethod.SHARE && !it.knownTaskApp }
                        var showOthers by remember { mutableStateOf(false) }

                        openTasks.forEach { target ->
                            DropdownMenuItem(
                                text = { Text("${target.label} (silent, confirmed)") },
                                onClick = { viewModel.setShareTarget(target.packageName); expanded = false },
                            )
                        }
                        tasker.forEach { target ->
                            DropdownMenuItem(
                                text = { Text("${target.label} (silent)") },
                                onClick = { viewModel.setShareTarget(target.packageName); expanded = false },
                            )
                        }
                        if ((openTasks.isNotEmpty() || tasker.isNotEmpty()) && knownShare.isNotEmpty()) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Task apps",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    )
                                },
                                enabled = false,
                                onClick = {},
                            )
                        }
                        knownShare.forEach { target ->
                            DropdownMenuItem(
                                text = { Text(target.label) },
                                onClick = { viewModel.setShareTarget(target.packageName); expanded = false },
                            )
                        }
                        if (otherApps.isNotEmpty()) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (showOthers) "Other apps" else "Other apps...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    )
                                },
                                onClick = { showOthers = !showOthers },
                            )
                            if (showOthers) {
                                otherApps.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.label) },
                                        onClick = { viewModel.setShareTarget(target.packageName); expanded = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Trigger Gesture
        OptionCard("Trigger Gesture") {
            CompactToggleRow(
                items = listOf("z", "o", "s", "m"),
                selected = state.triggerGesture,
                label = { it.uppercase() },
                onSelect = { viewModel.setTriggerGesture(it) },
            )
        }

        // Initial Prompt (Whisper only)
        if (isWhisper) {
        OptionCard("Initial Prompt") {
            Text(
                "Vocabulary hints to improve recognition accuracy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.initialPrompt,
                onValueChange = { viewModel.setInitialPrompt(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. buy milk, call dentist, fix login bug") },
                singleLine = false,
                maxLines = 3,
            )
        }
        }

        // — Developer-only settings —

        if (state.developerModeEnabled) {

        OptionCard("Voice Engine") {
            CompactToggleRow(
                items = listOf("whisper", "google", "openai", "custom"),
                selected = state.voiceEngine,
                label = { when (it) {
                    "whisper" -> "Whisper"
                    "google" -> "Google"
                    "openai" -> "OpenAI"
                    "custom" -> "Custom"
                    else -> it
                } },
                onSelect = { viewModel.setVoiceEngine(it) },
            )
            if (state.voiceEngine == "openai" || state.voiceEngine == "custom") {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenApiSettings,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("API Settings")
                }
            }
        }

        if (isWhisper) {
        OptionCard("Voice Model") {
            CompactToggleRow(
                items = WhisperModel.entries,
                selected = state.selectedModel,
                label = { it.displayName },
                enabled = !isDownloading,
                onSelect = { viewModel.setModel(it) },
            )
            Spacer(Modifier.height(8.dp))
            when (val ms = state.modelState) {
                is ModelState.NotDownloaded -> {
                    OutlinedButton(
                        onClick = { viewModel.downloadModel() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Download ${state.selectedModel.displayName} (~${state.selectedModel.totalSizeMB} MB)")
                    }
                }
                is ModelState.Downloading -> {
                    Text(
                        "Downloading ${state.selectedModel.displayName}… ${(ms.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
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
                        style = MaterialTheme.typography.bodySmall,
                        color = StateTriggered,
                    )
                }
                is ModelState.Error -> {
                    Text(
                        "Error: ${ms.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.downloadModel() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
        }

        OptionCard("Gesture Model") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.customGestureModelName != null)
                        "Custom: ${state.customGestureModelName}"
                    else
                        "Built-in",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.customGestureModelName != null)
                        StateTriggered
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { modelPickerLauncher.launch(arrayOf("application/octet-stream")) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text("Import") }
                    if (state.customGestureModelName != null) {
                        TextButton(
                            onClick = { viewModel.resetGestureModel() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) { Text("Reset") }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            when {
                state.isDownloadingGestureModel -> {
                    Text(
                        "Downloading… ${(state.gestureDownloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.gestureDownloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.gestureDownloadError != null -> {
                    Text(
                        "Error: ${state.gestureDownloadError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.downloadGestureModel() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text("Retry") }
                }
                else -> {
                    OutlinedButton(
                        onClick = { viewModel.downloadGestureModel() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text("Download from HuggingFace") }
                }
            }
        }

        } // end developerModeEnabled

        // Developer toggle — always last
        OptionCard("Developer") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Developer mode",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = state.developerModeEnabled,
                    onCheckedChange = { viewModel.setDeveloperMode(it) },
                )
            }
        }
    }
}

@Composable
private fun LanguageDropdown(
    selected: WhisperLanguage,
    enabled: Boolean,
    onSelect: (WhisperLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    Box {
        OutlinedButton(
            onClick = { expanded = true; search = "" },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("${selected.label} (${selected.code})")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 400.dp),
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text("Search…") },
                singleLine = true,
            )
            val filtered = if (search.isBlank()) WhisperLanguage.ALL else {
                val q = search.lowercase()
                WhisperLanguage.ALL.filter {
                    it.label.lowercase().contains(q) || it.code.lowercase().contains(q)
                }
            }
            filtered.forEach { lang ->
                DropdownMenuItem(
                    text = { Text("${lang.label} (${lang.code})") },
                    onClick = { onSelect(lang); expanded = false },
                )
            }
            if (filtered.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text("No matches", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    },
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun OptionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun <T> CompactToggleRow(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            if (item == selected) {
                Button(
                    onClick = {},
                    enabled = false,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(label(item), style = MaterialTheme.typography.labelMedium)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(item) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(label(item), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
