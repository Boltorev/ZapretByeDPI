package com.zapret.byedpi.telegram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TelegramPhase {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class TelegramState(
    val phase: TelegramPhase = TelegramPhase.STOPPED,
    val port: Int = TelegramPrefs.DEFAULT_PORT,
    /** Полный секрет с префиксом dd/ee, пригодный для tg://proxy. */
    val secret: String? = null,
    val error: String? = null
)

object TelegramRuntime {
    private val _state = MutableStateFlow(TelegramState(port = TelegramPrefs.DEFAULT_PORT))
    val state: StateFlow<TelegramState> = _state.asStateFlow()

    fun update(transform: (TelegramState) -> TelegramState) {
        _state.update(transform)
    }
}
