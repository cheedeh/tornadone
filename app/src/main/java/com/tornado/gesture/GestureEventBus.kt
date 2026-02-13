package com.tornado.gesture

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GestureEvent {
    data class StateChanged(val state: OnnxGestureClassifier.State) : GestureEvent
    data class ZDetected(val timestamp: Long) : GestureEvent
    data class Classified(val label: String, val samples: Int, val durationMs: Long) : GestureEvent
    data class Listening(val active: Boolean) : GestureEvent
    data class Transcribed(val text: String) : GestureEvent
    data class VoiceError(val message: String) : GestureEvent
}

@Singleton
class GestureEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<GestureEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun tryEmit(event: GestureEvent) {
        _events.tryEmit(event)
    }
}
