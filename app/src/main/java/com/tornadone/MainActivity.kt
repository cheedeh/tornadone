package com.tornadone

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornadone.data.PreferencesManager
import com.tornadone.service.GestureService
import com.tornadone.ui.screens.HomeViewModel
import com.tornadone.ui.screens.MainScreen
import com.tornadone.ui.screens.OnboardingScreen
import com.tornadone.ui.theme.TornadoneTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()

    @Inject lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        autoStartServiceIfNeeded()
        handleIncomingIntent(intent)

        setContent {
            TornadoneTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()

                    if (state.onboardingComplete) {
                        MainScreen(
                            viewModel = viewModel,
                            onStartService = {
                                GestureService.start(this)
                                viewModel.setServiceRunning(true)
                            },
                            onStopService = {
                                GestureService.stop(this)
                                viewModel.setServiceRunning(false)
                            },
                        )
                    } else {
                        OnboardingScreen(
                            viewModel = viewModel,
                            onComplete = {
                                autoStartServiceIfNeeded()
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null && uri.toString().endsWith(".onnx", ignoreCase = true)) {
            viewModel.importGestureModel(uri)
            Toast.makeText(this, "Gesture model imported. Restart service to apply.", Toast.LENGTH_LONG).show()
        }
    }

    private fun autoStartServiceIfNeeded() {
        if (preferencesManager.onboardingComplete && preferencesManager.autoStartService) {
            GestureService.start(this)
            viewModel.setServiceRunning(true)
        }
    }
}
