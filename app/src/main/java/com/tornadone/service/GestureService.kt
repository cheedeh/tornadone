package com.tornadone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.tornadone.MainActivity
import com.tornadone.R
import com.tornadone.data.PreferencesManager
import com.tornadone.backend.TaskDispatcher
import com.tornadone.gesture.GestureEvent
import com.tornadone.gesture.GestureEventBus
import com.tornadone.gesture.OnnxGestureClassifier
import com.tornadone.voice.GoogleSpeechRecognizer
import com.tornadone.voice.RemoteTranscriber
import com.tornadone.voice.VoiceRecognitionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GestureService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "tornado_gesture_channel"
        const val NOTIFICATION_ID = 1
        private const val IDLE_TIMEOUT_MS = 30_000L
        private const val BATCH_LATENCY_US = 50_000

        fun start(context: Context) {
            val intent = Intent(context, GestureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureService::class.java))
        }
    }

    @Inject lateinit var sensorManager: SensorManager
    @Inject lateinit var vibrator: Vibrator
    @Inject lateinit var classifier: OnnxGestureClassifier
    @Inject lateinit var eventBus: GestureEventBus
    @Inject lateinit var voiceManager: VoiceRecognitionManager
    @Inject lateinit var googleSpeech: GoogleSpeechRecognizer
    @Inject lateinit var remoteTranscriber: RemoteTranscriber
    @Inject lateinit var taskDispatcher: TaskDispatcher
    @Inject lateinit var preferencesManager: PreferencesManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var soundPool: SoundPool? = null
    private var spadeSound: Int = 0
    private var listenBeep: Int = 0
    private var doneBeep: Int = 0
    private var isListening = false

    private val handler = Handler(Looper.getMainLooper())
    private var accelRegistered = false
    private var isPowerSaving = false
    private var lastSensorEventMs = 0L

    private val idleCheckRunnable = Runnable { checkIdle() }

    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            onSignificantMotion()
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initSound()
        setupClassifier()
        registerAccelerometer()
        scheduleIdleCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        voiceManager.stopListening()
        googleSpeech.shutdown()
        remoteTranscriber.stopListening()
        soundPool?.release()
        classifier.close()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            lastSensorEventMs = System.currentTimeMillis()
            classifier.onSensorData(
                event.values[0], event.values[1], event.values[2],
                lastSensorEventMs,
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_tornado)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun initSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()
        spadeSound = soundPool?.load(this, R.raw.spade, 1) ?: 0
        listenBeep = soundPool?.load(this, R.raw.listen_start, 1) ?: 0
        doneBeep = soundPool?.load(this, R.raw.listen_done, 1) ?: 0
    }

    private fun setupClassifier() {
        classifier.onsetThreshold = preferencesManager.gestureSensitivity
        classifier.cooldownMs = preferencesManager.gestureCooldownMs

        classifier.onZDetected = { timestamp ->
            eventBus.tryEmit(GestureEvent.ZDetected(timestamp))
        }
        classifier.onStateChanged = { state ->
            eventBus.tryEmit(GestureEvent.StateChanged(state))
        }
        classifier.onClassified = { label, samples, durationMs ->
            eventBus.tryEmit(GestureEvent.Classified(label, samples, durationMs))

            if (label == preferencesManager.triggerGesture) {
                soundPool?.play(spadeSound, 1f, 1f, 1, 0, 1f)
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 80, 60, 120),
                        intArrayOf(0, 255, 0, 255),
                        -1,
                    )
                )
                startVoiceCapture()
            }
        }
    }

    private fun startVoiceCapture() {
        if (isListening) return

        when (preferencesManager.voiceEngine) {
            "google" -> startGoogleVoiceCapture()
            "openai", "custom" -> startRemoteVoiceCapture()
            else -> startWhisperVoiceCapture()
        }
    }

    private fun startWhisperVoiceCapture() {
        if (!voiceManager.isModelReady) {
            eventBus.tryEmit(GestureEvent.VoiceError("Model not downloaded yet"))
            return
        }
        if (!voiceManager.initModel()) {
            eventBus.tryEmit(GestureEvent.VoiceError("Failed to load voice model"))
            return
        }

        isListening = true
        soundPool?.play(listenBeep, 1f, 1f, 1, 0, 1f)
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        eventBus.tryEmit(GestureEvent.Listening(true))

        voiceManager.startListening(
            onResult = { text, audio ->
                handleTranscriptionResult(text, audio, voiceManager.lastRecordingPath)
            },
            onError = { message ->
                handleTranscriptionError(message)
            },
            onRecordingDone = {
                isListening = false
                eventBus.tryEmit(GestureEvent.Listening(false))
                eventBus.tryEmit(GestureEvent.Transcribing(true))
            },
        )
    }

    private fun startGoogleVoiceCapture() {
        isListening = true
        soundPool?.play(listenBeep, 1f, 1f, 1, 0, 1f)
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        eventBus.tryEmit(GestureEvent.Listening(true))

        googleSpeech.startListening(
            onResult = { text, _ ->
                handleTranscriptionResult(text, null, null)
            },
            onError = { message ->
                handleTranscriptionError(message)
            },
            onRecordingDone = {
                isListening = false
                eventBus.tryEmit(GestureEvent.Listening(false))
            },
        )
    }

    private fun startRemoteVoiceCapture() {
        isListening = true
        soundPool?.play(listenBeep, 1f, 1f, 1, 0, 1f)
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        eventBus.tryEmit(GestureEvent.Listening(true))

        remoteTranscriber.startListening(
            onResult = { text, audio ->
                handleTranscriptionResult(text, audio, remoteTranscriber.lastRecordingPath)
            },
            onError = { message ->
                handleTranscriptionError(message)
            },
            onRecordingDone = {
                isListening = false
                eventBus.tryEmit(GestureEvent.Listening(false))
                eventBus.tryEmit(GestureEvent.Transcribing(true))
            },
        )
    }

    private fun handleTranscriptionResult(text: String, audio: FloatArray?, recordingPath: String?) {
        eventBus.tryEmit(GestureEvent.Transcribing(false))
        soundPool?.play(doneBeep, 1f, 1f, 1, 0, 1f)
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        eventBus.tryEmit(GestureEvent.Transcribed(text, recordingPath))

        if (isGibberish(text)) {
            serviceScope.launch {
                val audioPath = if (audio != null && audio.isNotEmpty()) {
                    taskDispatcher.saveRejectedRecording(audio)
                } else {
                    null
                }
                eventBus.tryEmit(GestureEvent.Rejected(text, audioPath))
            }
        } else {
            serviceScope.launch {
                val result = taskDispatcher.createTask(text)
                eventBus.tryEmit(GestureEvent.TaskCreated(text, result.detail, result.success))
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 60, 80, 60),
                        intArrayOf(0, 200, 0, 200),
                        -1,
                    )
                )
            }
        }
    }

    private fun handleTranscriptionError(message: String) {
        eventBus.tryEmit(GestureEvent.Transcribing(false))
        soundPool?.play(doneBeep, 1f, 1f, 1, 0, 1f)
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        eventBus.tryEmit(GestureEvent.VoiceError(message))
    }

    private fun registerAccelerometer() {
        if (accelRegistered) return
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, BATCH_LATENCY_US)
        }
        accelRegistered = true
        lastSensorEventMs = System.currentTimeMillis()
        if (isPowerSaving) {
            isPowerSaving = false
            eventBus.tryEmit(GestureEvent.PowerSaving(false))
        }
    }

    private fun unregisterAccelerometer() {
        if (!accelRegistered) return
        sensorManager.unregisterListener(this)
        accelRegistered = false
        classifier.reset()
    }

    private fun enterPowerSaving() {
        if (isPowerSaving) return
        isPowerSaving = true
        unregisterAccelerometer()
        eventBus.tryEmit(GestureEvent.PowerSaving(true))
        registerSignificantMotion()
    }

    private fun registerSignificantMotion() {
        sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { sensor ->
            sensorManager.requestTriggerSensor(significantMotionListener, sensor)
        }
    }

    private fun onSignificantMotion() {
        registerAccelerometer()
        scheduleIdleCheck()
    }

    private fun scheduleIdleCheck() {
        handler.removeCallbacks(idleCheckRunnable)
        handler.postDelayed(idleCheckRunnable, IDLE_TIMEOUT_MS)
    }

    private fun checkIdle() {
        val elapsed = System.currentTimeMillis() - lastSensorEventMs
        if (elapsed >= IDLE_TIMEOUT_MS && !isListening) {
            enterPowerSaving()
        } else {
            scheduleIdleCheck()
        }
    }

    private fun isGibberish(text: String): Boolean {
        val trimmed = text.trim()
        // Too short to be a real task
        if (trimmed.length < 3) return true
        // Only non-letter characters
        if (trimmed.none { it.isLetter() }) return true
        // Whisper hallucination markers
        val lower = trimmed.lowercase()
        val hallucinations = listOf(
            "[music]", "(music)", "[silence]", "(silence)",
            "[applause]", "(applause)", "[laughter]", "(laughter)",
            "thank you.", "thanks for watching", "subscribe",
            "dziękuję", "napisy", "tłumaczenie",
        )
        if (hallucinations.any { lower.contains(it) }) return true
        // Excessive repetition: same word 3+ times in a row
        val words = lower.split("\\s+".toRegex())
        if (words.size >= 3) {
            var repeats = 1
            for (i in 1 until words.size) {
                if (words[i] == words[i - 1]) {
                    repeats++
                    if (repeats >= 3) return true
                } else {
                    repeats = 1
                }
            }
        }
        return false
    }
}
