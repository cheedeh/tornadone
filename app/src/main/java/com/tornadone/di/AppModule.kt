package com.tornadone.di

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import com.tornadone.voice.Transcriber
import com.tornadone.voice.WhisperTranscriber
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSensorManager(@ApplicationContext context: Context): SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Provides
    @Singleton
    @Suppress("DEPRECATION")
    fun provideVibrator(@ApplicationContext context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

}

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceBindingsModule {
    @Binds
    @Singleton
    abstract fun bindTranscriber(impl: WhisperTranscriber): Transcriber
}
