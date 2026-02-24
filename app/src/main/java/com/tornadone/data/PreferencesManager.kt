package com.tornadone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tornado_settings", Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tornado_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var gestureSensitivity: Float
        get() = prefs.getFloat("gesture_sensitivity", 4.0f)
        set(value) = prefs.edit().putFloat("gesture_sensitivity", value).apply()

    var gestureCooldownMs: Long
        get() = prefs.getLong("gesture_cooldown_ms", 2000L)
        set(value) = prefs.edit().putLong("gesture_cooldown_ms", value).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboarding_complete", false)
        set(value) = prefs.edit().putBoolean("onboarding_complete", value).apply()

    var autoStartService: Boolean
        get() = prefs.getBoolean("auto_start_service", false)
        set(value) = prefs.edit().putBoolean("auto_start_service", value).apply()

    var shareTargetPackage: String
        get() = prefs.getString("share_target_package", "") ?: ""
        set(value) = prefs.edit().putString("share_target_package", value).apply()

    var savedLog: String
        get() = prefs.getString("event_log", "") ?: ""
        set(value) = prefs.edit().putString("event_log", value).apply()

    var initialPrompt: String
        get() = prefs.getString("initial_prompt", "") ?: ""
        set(value) = prefs.edit().putString("initial_prompt", value).apply()

    var triggerGesture: String
        get() = prefs.getString("trigger_gesture", "z") ?: "z"
        set(value) = prefs.edit().putString("trigger_gesture", value).apply()

    var customGestureModelPath: String
        get() = prefs.getString("custom_gesture_model_path", "") ?: ""
        set(value) = prefs.edit().putString("custom_gesture_model_path", value).apply()

    var voiceEngine: String
        get() = prefs.getString("voice_engine", "whisper") ?: "whisper"
        set(value) = prefs.edit().putString("voice_engine", value).apply()

    var openaiApiKey: String
        get() = securePrefs.getString("openai_api_key", "") ?: ""
        set(value) = securePrefs.edit().putString("openai_api_key", value).apply()

    var customTranscriptionUrl: String
        get() = securePrefs.getString("custom_transcription_url", "") ?: ""
        set(value) = securePrefs.edit().putString("custom_transcription_url", value).apply()

    var customTranscriptionAuthHeader: String
        get() = securePrefs.getString("custom_transcription_auth_header", "") ?: ""
        set(value) = securePrefs.edit().putString("custom_transcription_auth_header", value).apply()

    var developerModeEnabled: Boolean
        get() = prefs.getBoolean("developer_mode", false)
        set(value) = prefs.edit().putBoolean("developer_mode", value).apply()
}
