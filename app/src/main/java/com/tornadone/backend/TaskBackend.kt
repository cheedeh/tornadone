package com.tornadone.backend

enum class DispatchMethod { OPENTASKS, TASKER, SHARE, NONE }

data class DispatchResult(
    val method: DispatchMethod,
    val success: Boolean,
    val detail: String,
)

interface TaskBackend {
    val name: String
    suspend fun notifyTaskCreated(description: String): DispatchResult
}
