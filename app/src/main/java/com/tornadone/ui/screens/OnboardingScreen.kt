package com.tornadone.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornadone.ui.theme.StateTriggered
import com.tornadone.voice.ModelState
import com.tornadone.voice.WhisperModel
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: HomeViewModel,
    onComplete: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        // Page indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(4) { index ->
                Text(
                    if (index == pagerState.currentPage) " ● " else " ○ ",
                    color = if (index == pagerState.currentPage)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> PermissionsPage()
                2 -> ModelPage(viewModel, state)
                3 -> ReadyPage(
                    autoStart = state.autoStartService,
                    onAutoStartChanged = { viewModel.setAutoStartService(it) },
                    onComplete = {
                        viewModel.completeOnboarding()
                        onComplete()
                    },
                )
            }
        }

        // Navigation buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (pagerState.currentPage > 0) {
                TextButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }) {
                    Text("Back")
                }
            } else {
                Spacer(Modifier)
            }

            if (pagerState.currentPage < 3) {
                Button(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }) {
                    Text("Next")
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Tornadone",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Slash a Z in the air to create tasks by voice",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Text(
                "Gesture  →  Voice  →  Task",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "No app opening, no typing.\nJust slash and speak.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current

    var audioGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioGranted = granted
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Permissions",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tornadone needs a few permissions to work in the background.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))

        PermissionRow(
            name = "Microphone",
            rationale = "Record voice commands after gesture",
            granted = audioGranted,
            onRequest = { audioLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        Spacer(Modifier.height(16.dp))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(
                name = "Notifications",
                rationale = "Show foreground service notification",
                granted = notifGranted,
                onRequest = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
    }
}

@Composable
private fun PermissionRow(
    name: String,
    rationale: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (granted) {
                Text("Granted", color = StateTriggered, style = MaterialTheme.typography.labelLarge)
            } else {
                OutlinedButton(onClick = onRequest) {
                    Text("Grant")
                }
            }
        }
    }
}

@Composable
private fun ModelPage(viewModel: HomeViewModel, state: HomeUiState) {
    val isDownloading = state.modelState is ModelState.Downloading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Voice Model",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Download a Whisper model for offline speech recognition.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WhisperModel.entries.forEach { model ->
                if (model == state.selectedModel) {
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

        Spacer(Modifier.height(16.dp))

        when (val ms = state.modelState) {
            is ModelState.NotDownloaded -> {
                OutlinedButton(onClick = { viewModel.downloadModel() }) {
                    Text("Download ${state.selectedModel.displayName} (~${state.selectedModel.totalSizeMB} MB)")
                }
            }
            is ModelState.Downloading -> {
                Text(
                    "Downloading... ${(ms.progress * 100).toInt()}%",
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
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun ReadyPage(
    autoStart: Boolean,
    onAutoStartChanged: (Boolean) -> Unit,
    onComplete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Ready!",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-start service", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Start gesture detection when app opens",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Switch(
                        checked = autoStart,
                        onCheckedChange = onAutoStartChanged,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(onClick = onComplete) {
                Text("Get started")
            }
        }
    }
}
