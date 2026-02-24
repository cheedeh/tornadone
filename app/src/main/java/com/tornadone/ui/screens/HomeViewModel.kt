package com.tornadone.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tornadone.data.PreferencesManager
import com.tornadone.backend.IntentBackend
import com.tornadone.backend.TaskDispatcher
import com.tornadone.gesture.GestureEvent
import com.tornadone.gesture.GestureEventBus
import com.tornadone.gesture.GestureModelDownloader
import com.tornadone.gesture.OnnxGestureClassifier
import com.tornadone.voice.ModelManager
import com.tornadone.voice.ModelState
import com.tornadone.voice.VoiceRecognitionManager
import com.tornadone.voice.WhisperLanguage
import com.tornadone.voice.WhisperModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class ShareMethod { OPENTASKS, TASKER, SHARE }

data class ShareTarget(
    val packageName: String,
    val label: String,
    val method: ShareMethod,
    val knownTaskApp: Boolean = false,
)

private val KNOWN_TASK_PACKAGES = setOf(
    "org.tasks",
    "com.todoist",
    "com.ticktick.task",
    "com.clickup.mobile",
    "com.google.android.apps.tasks",
    "com.microsoft.todos",
    "com.anydo",
    "com.asana.app",
    "com.trello",
    "com.rememberthemilk.MobileRTM",
    "org.dmfs.tasks",
    "ch.teamtasks.tasks.paid",
    "com.habitrpg.android.habitica",
    "com.notion.id",
    "net.dinglisch.android.taskerm",
    "prox.lab.calclock",
    "com.appgenix.bizcal",
)

data class HomeUiState(
    val serviceRunning: Boolean = false,
    val gestureState: OnnxGestureClassifier.State = OnnxGestureClassifier.State.IDLE,
    val detectionCount: Int = 0,
    val log: List<String> = emptyList(),
    val isListening: Boolean = false,
    val isTranscribing: Boolean = false,
    val isPowerSaving: Boolean = false,
    val modelState: ModelState = ModelState.NotDownloaded,
    val selectedModel: WhisperModel = WhisperModel.TINY,
    val selectedLanguage: WhisperLanguage = WhisperLanguage.default(),
    val lastTranscription: String? = null,
    val lastRecordingPath: String? = null,
    val isRetranscribing: Boolean = false,
    val gestureSensitivity: Float = 4.0f,
    val gestureCooldownMs: Long = 2000L,
    val onboardingComplete: Boolean = false,
    val autoStartService: Boolean = false,
    val rejectedRecordings: List<RejectedRecording> = emptyList(),
    val shareTargets: List<ShareTarget> = emptyList(),
    val selectedShareTarget: String = "",
    val initialPrompt: String = "",
    val triggerGesture: String = "z",
    val customGestureModelName: String? = null,
    val voiceEngine: String = "whisper",
    val openaiApiKey: String = "",
    val customTranscriptionUrl: String = "",
    val customTranscriptionAuthHeader: String = "",
    val developerModeEnabled: Boolean = false,
    val isDownloadingGestureModel: Boolean = false,
    val gestureDownloadProgress: Float = 0f,
    val gestureDownloadError: String? = null,
)

data class RejectedRecording(
    val text: String,
    val audioPath: String?,
    val timestamp: String,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
    private val eventBus: GestureEventBus,
    private val modelManager: ModelManager,
    private val taskDispatcher: TaskDispatcher,
    private val intentBackend: IntentBackend,
    private val preferencesManager: PreferencesManager,
    private val voiceManager: VoiceRecognitionManager,
    private val classifier: OnnxGestureClassifier,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    companion object {
        private const val CUSTOM_MODEL_FILENAME = "custom_gesture_model.onnx"
    }

    init {
        loadPreferences()
        queryShareTargets()
        observeEvents()
        observeModelState()
        autoDownloadGestureModelIfNeeded()
    }

    private fun autoDownloadGestureModelIfNeeded() {
        val path = preferencesManager.customGestureModelPath
        if (path.isEmpty() || !File(path).exists()) {
            downloadGestureModel()
        }
    }

    private fun loadPreferences() {
        val savedLog = preferencesManager.savedLog
            .split("\n")
            .filter { it.isNotEmpty() }
        val recordingPath = voiceManager.lastRecordingPath
        val customPath = preferencesManager.customGestureModelPath
        val customName = if (customPath.isNotEmpty() && File(customPath).exists()) {
            File(customPath).name
        } else {
            null
        }
        _uiState.update {
            it.copy(
                log = savedLog,
                gestureSensitivity = preferencesManager.gestureSensitivity,
                gestureCooldownMs = preferencesManager.gestureCooldownMs,
                onboardingComplete = preferencesManager.onboardingComplete,
                autoStartService = preferencesManager.autoStartService,
                selectedShareTarget = preferencesManager.shareTargetPackage,
                lastRecordingPath = if (File(recordingPath).exists()) recordingPath else null,
                initialPrompt = preferencesManager.initialPrompt,
                triggerGesture = preferencesManager.triggerGesture,
                customGestureModelName = customName,
                voiceEngine = preferencesManager.voiceEngine,
                openaiApiKey = preferencesManager.openaiApiKey,
                customTranscriptionUrl = preferencesManager.customTranscriptionUrl,
                customTranscriptionAuthHeader = preferencesManager.customTranscriptionAuthHeader,
                developerModeEnabled = preferencesManager.developerModeEnabled,
            )
        }
    }

    fun importGestureModel(uri: Uri) {
        val destFile = File(application.filesDir, CUSTOM_MODEL_FILENAME)
        try {
            application.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return
            preferencesManager.customGestureModelPath = destFile.absolutePath
            classifier.reload()
            _uiState.update { it.copy(customGestureModelName = CUSTOM_MODEL_FILENAME) }
            Log.i("HomeViewModel", "Imported custom gesture model: ${destFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to import gesture model", e)
        }
    }

    fun resetGestureModel() {
        val destFile = File(application.filesDir, CUSTOM_MODEL_FILENAME)
        if (destFile.exists()) destFile.delete()
        preferencesManager.customGestureModelPath = ""
        _uiState.update { it.copy(customGestureModelName = null) }
        Log.i("HomeViewModel", "Reset to built-in gesture model")
    }

    fun setInitialPrompt(prompt: String) {
        preferencesManager.initialPrompt = prompt
        _uiState.update { it.copy(initialPrompt = prompt) }
    }

    fun setVoiceEngine(engine: String) {
        preferencesManager.voiceEngine = engine
        _uiState.update { it.copy(voiceEngine = engine) }
    }

    fun setOpenaiApiKey(key: String) {
        preferencesManager.openaiApiKey = key
        _uiState.update { it.copy(openaiApiKey = key) }
    }

    fun setCustomTranscriptionUrl(url: String) {
        preferencesManager.customTranscriptionUrl = url
        _uiState.update { it.copy(customTranscriptionUrl = url) }
    }

    fun setCustomTranscriptionAuthHeader(header: String) {
        preferencesManager.customTranscriptionAuthHeader = header
        _uiState.update { it.copy(customTranscriptionAuthHeader = header) }
    }

    private fun addLogEntry(entry: String) {
        _uiState.update {
            val newLog = (listOf(entry) + it.log).take(50)
            it.copy(log = newLog)
        }
        viewModelScope.launch(Dispatchers.IO) {
            preferencesManager.savedLog = _uiState.value.log.joinToString("\n")
        }
    }

    private fun queryShareTargets() {
        val pm = application.packageManager
        val sendIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        val resolveInfos = pm.queryIntentActivities(sendIntent, 0)
        val myPackage = application.packageName
        val targets = resolveInfos
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val appLabel = ri.activityInfo.applicationInfo.loadLabel(pm).toString()
                val actLabel = ri.loadLabel(pm).toString()
                val label = if (actLabel != appLabel) "$appLabel — $actLabel" else appLabel
                val method = detectMethod(pm, pkg)
                val known = pkg in KNOWN_TASK_PACKAGES
                ShareTarget(pkg, label, method, known)
            }
            .distinctBy { it.packageName }
            .filter { it.packageName != myPackage }
            .sortedWith(
                compareBy<ShareTarget> { it.method.ordinal }
                    .thenByDescending { it.knownTaskApp }
                    .thenBy { it.label.lowercase() }
            )
        _uiState.update { it.copy(shareTargets = targets) }
    }

    private fun detectMethod(pm: android.content.pm.PackageManager, pkg: String): ShareMethod {
        if (pm.resolveContentProvider("$pkg.opentasks", 0) != null) {
            return ShareMethod.OPENTASKS
        }
        val probe = Intent("com.twofortyfouram.locale.intent.action.FIRE_SETTING").apply {
            setPackage(pkg)
        }
        if (pm.queryBroadcastReceivers(probe, 0).isNotEmpty()) {
            return ShareMethod.TASKER
        }
        return ShareMethod.SHARE
    }

    fun setShareTarget(packageName: String) {
        preferencesManager.shareTargetPackage = packageName
        _uiState.update { it.copy(selectedShareTarget = packageName) }
        if (packageName.isNotEmpty()) {
            val filters = intentBackend.dumpIntentFilters(packageName)
            Log.d("HomeViewModel", "Intent filters for $packageName:")
            filters.forEach { Log.d("HomeViewModel", "  $it") }
        }
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
                        val trigger = _uiState.value.triggerGesture
                        val marker = if (event.label == trigger) " >>> ${trigger.uppercase()}!" else ""
                        addLogEntry("$ts  ${event.label}  (${event.samples} pts, ${event.durationMs}ms)$marker")
                    }
                    is GestureEvent.Listening -> {
                        _uiState.update { it.copy(isListening = event.active) }
                    }
                    is GestureEvent.Transcribing -> {
                        _uiState.update { it.copy(isTranscribing = event.active) }
                    }
                    is GestureEvent.Transcribed -> {
                        val ts = timeFormat.format(Date())
                        addLogEntry("$ts  VOICE: \"${event.text}\"")
                        _uiState.update {
                            it.copy(
                                lastTranscription = event.text,
                                lastRecordingPath = event.recordingPath,
                            )
                        }
                    }
                    is GestureEvent.TaskCreated -> {
                        val ts = timeFormat.format(Date())
                        val status = if (event.confirmed) "OK" else "FAILED"
                        addLogEntry("$ts  TASK [$status]: \"${event.description}\" — ${event.method}")
                    }
                    is GestureEvent.VoiceError -> {
                        val ts = timeFormat.format(Date())
                        addLogEntry("$ts  VOICE ERROR: ${event.message}")
                    }
                    is GestureEvent.PowerSaving -> {
                        _uiState.update { it.copy(isPowerSaving = event.active) }
                    }
                    is GestureEvent.Rejected -> {
                        val ts = timeFormat.format(Date())
                        addLogEntry("$ts  REJECTED: \"${event.text}\"")
                        val rejected = RejectedRecording(
                            text = event.text,
                            audioPath = event.audioPath,
                            timestamp = ts,
                        )
                        _uiState.update {
                            it.copy(
                                rejectedRecordings = (listOf(rejected) + it.rejectedRecordings).take(50),
                            )
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

    fun setDeveloperMode(enabled: Boolean) {
        preferencesManager.developerModeEnabled = enabled
        _uiState.update { it.copy(developerModeEnabled = enabled) }
    }

    fun downloadGestureModel() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isDownloadingGestureModel = true, gestureDownloadProgress = 0f, gestureDownloadError = null)
            }
            try {
                val destFile = File(application.filesDir, CUSTOM_MODEL_FILENAME)
                GestureModelDownloader.download(destFile) { progress ->
                    _uiState.update { it.copy(gestureDownloadProgress = progress) }
                }
                preferencesManager.customGestureModelPath = destFile.absolutePath
                classifier.reload()
                _uiState.update {
                    it.copy(
                        isDownloadingGestureModel = false,
                        gestureDownloadProgress = 1f,
                        customGestureModelName = CUSTOM_MODEL_FILENAME,
                    )
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to download gesture model", e)
                _uiState.update {
                    it.copy(
                        isDownloadingGestureModel = false,
                        gestureDownloadError = e.message ?: "Download failed",
                    )
                }
            }
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(log = emptyList(), rejectedRecordings = emptyList()) }
        preferencesManager.savedLog = ""
    }

    fun setServiceRunning(running: Boolean) {
        _uiState.update { it.copy(serviceRunning = running) }
    }

    fun setSensitivity(value: Float) {
        preferencesManager.gestureSensitivity = value
        _uiState.update { it.copy(gestureSensitivity = value) }
    }

    fun setCooldownMs(value: Long) {
        preferencesManager.gestureCooldownMs = value
        _uiState.update { it.copy(gestureCooldownMs = value) }
    }

    fun setTriggerGesture(gesture: String) {
        preferencesManager.triggerGesture = gesture
        _uiState.update { it.copy(triggerGesture = gesture) }
    }

    fun setAutoStartService(enabled: Boolean) {
        preferencesManager.autoStartService = enabled
        _uiState.update { it.copy(autoStartService = enabled) }
    }

    fun completeOnboarding() {
        preferencesManager.onboardingComplete = true
        _uiState.update { it.copy(onboardingComplete = true) }
    }

    fun retranscribeLastRecording() {
        val path = voiceManager.lastRecordingPath
        if (!File(path).exists()) return
        if (!voiceManager.initModel()) {
            val ts = timeFormat.format(Date())
            addLogEntry("$ts  VOICE ERROR: Failed to load model for retranscription")
            return
        }
        _uiState.update { it.copy(isRetranscribing = true) }
        voiceManager.transcribeFile(
            path = path,
            onResult = { text ->
                val ts = timeFormat.format(Date())
                addLogEntry("$ts  RE-VOICE: \"$text\"")
                _uiState.update { it.copy(lastTranscription = text, isRetranscribing = false) }
            },
            onError = { message ->
                val ts = timeFormat.format(Date())
                addLogEntry("$ts  VOICE ERROR: $message")
                _uiState.update { it.copy(isRetranscribing = false) }
            },
        )
    }
}
