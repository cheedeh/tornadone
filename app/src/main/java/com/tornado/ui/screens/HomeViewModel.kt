package com.tornado.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tornado.gesture.GestureEvent
import com.tornado.gesture.GestureEventBus
import com.tornado.gesture.OnnxGestureClassifier
import com.tornado.voice.ModelManager
import com.tornado.voice.ModelState
import com.tornado.voice.WhisperLanguage
import com.tornado.voice.WhisperModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val serviceRunning: Boolean = false,
    val gestureState: OnnxGestureClassifier.State = OnnxGestureClassifier.State.IDLE,
    val detectionCount: Int = 0,
    val log: List<String> = emptyList(),
    val isListening: Boolean = false,
    val modelState: ModelState = ModelState.NotDownloaded,
    val selectedModel: WhisperModel = WhisperModel.TINY,
    val selectedLanguage: WhisperLanguage = WhisperLanguage.EN,
    val lastTranscription: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val eventBus: GestureEventBus,
    private val modelManager: ModelManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        observeEvents()
        observeModelState()
    }

    private fun observeEvents() {
        viewModelScope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is GestureEvent.StateChanged -> {
                        _uiState.update { it.copy(gestureState = event.state) }
                    }
                    is GestureEvent.ZDetected -> {
                        _uiState.update {
                            it.copy(detectionCount = it.detectionCount + 1)
                        }
                    }
                    is GestureEvent.Classified -> {
                        val ts = timeFormat.format(Date())
                        val marker = if (event.label == "z") " >>> Z!" else ""
                        val entry = "$ts  ${event.label}  (${event.samples} pts, ${event.durationMs}ms)$marker"
                        _uiState.update {
                            it.copy(log = (listOf(entry) + it.log).take(50))
                        }
                    }
                    is GestureEvent.Listening -> {
                        _uiState.update { it.copy(isListening = event.active) }
                    }
                    is GestureEvent.Transcribed -> {
                        val ts = timeFormat.format(Date())
                        val entry = "$ts  VOICE: \"${event.text}\""
                        _uiState.update {
                            it.copy(
                                log = (listOf(entry) + it.log).take(50),
                                lastTranscription = event.text,
                            )
                        }
                    }
                    is GestureEvent.VoiceError -> {
                        val ts = timeFormat.format(Date())
                        val entry = "$ts  VOICE ERROR: ${event.message}"
                        _uiState.update {
                            it.copy(log = (listOf(entry) + it.log).take(50))
                        }
                    }
                }
            }
        }
    }

    private fun observeModelState() {
        viewModelScope.launch {
            modelManager.state.collect { state ->
                _uiState.update { it.copy(modelState = state) }
            }
        }
        viewModelScope.launch {
            modelManager.language.collect { lang ->
                _uiState.update { it.copy(selectedLanguage = lang) }
            }
        }
        viewModelScope.launch {
            modelManager.selectedModel.collect { model ->
                _uiState.update { it.copy(selectedModel = model) }
            }
        }
    }

    fun setLanguage(lang: WhisperLanguage) {
        modelManager.setLanguage(lang)
    }

    fun setModel(model: WhisperModel) {
        modelManager.setModel(model)
    }

    fun downloadModel() {
        viewModelScope.launch {
            modelManager.ensureModel()
        }
    }

    fun setServiceRunning(running: Boolean) {
        _uiState.update { it.copy(serviceRunning = running) }
    }
}
