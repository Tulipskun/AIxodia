package com.tulipskun.aixodia.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenStage { IDLE, SEEING, WAITING, ACTING, VERIFYING, ERROR }

data class ScreenControlStatus(
    val enabled: Boolean = false,
    val stage: ScreenStage = ScreenStage.IDLE,
    val action: String = "",
    val target: String = "",
    val message: String = "Screen Control พร้อมใช้งาน",
)

object ScreenControlUiState {
    private val _state = MutableStateFlow(ScreenControlStatus())
    val state: StateFlow<ScreenControlStatus> = _state.asStateFlow()

    fun update(
        enabled: Boolean = _state.value.enabled,
        stage: ScreenStage = _state.value.stage,
        action: String = _state.value.action,
        target: String = _state.value.target,
        message: String = _state.value.message,
    ) {
        _state.value = ScreenControlStatus(enabled, stage, action, target, message)
    }
}
