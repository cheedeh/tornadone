package com.tornado.service

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
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import com.tornado.MainActivity
import com.tornado.R
import com.tornado.gesture.GestureEvent
import com.tornado.gesture.GestureEventBus
import com.tornado.gesture.OnnxGestureClassifier
import com.tornado.voice.VoiceRecognitionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class GestureService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "tornado_gesture_channel"
        const val NOTIFICATION_ID = 1

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

    private var soundPool: SoundPool? = null
    private var spadeSound: Int = 0
    private var listenBeep: Int = 0
    private var doneBeep: Int = 0
    private var isListening = false

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initSound()
        setupClassifier()
        registerSensors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        voiceManager.stopListening()
        soundPool?.release()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            classifier.onSensorData(
                event.values[0], event.values[1], event.values[2],
                System.currentTimeMillis(),
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
        classifier.onZDetected = { timestamp ->
            eventBus.tryEmit(GestureEvent.ZDetected(timestamp))
        }
        classifier.onStateChanged = { state ->
            eventBus.tryEmit(GestureEvent.StateChanged(state))
        }
        classifier.onClassified = { label, samples, durationMs ->
            eventBus.tryEmit(GestureEvent.Classified(label, samples, durationMs))

            if (label == "z") {
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
            onResult = { text ->
                isListening = false
                soundPool?.play(doneBeep, 1f, 1f, 1, 0, 1f)
                eventBus.tryEmit(GestureEvent.Listening(false))
                eventBus.tryEmit(GestureEvent.Transcribed(text))
            },
            onError = { message ->
                isListening = false
                soundPool?.play(doneBeep, 1f, 1f, 1, 0, 1f)
                eventBus.tryEmit(GestureEvent.Listening(false))
                eventBus.tryEmit(GestureEvent.VoiceError(message))
            },
        )
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }
}
