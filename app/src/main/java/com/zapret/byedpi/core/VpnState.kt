package com.zapret.byedpi.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Состояние VPN-службы. UI никогда не читает поля службы напрямую —
 * он подписывается на VpnRuntime.state.
 */
enum class VpnPhase {
    /** Туннель не создан. */
    STOPPED,

    /** Идёт запрос разрешения/создание TUN. */
    STARTING,

    /** TUN создан, служба работает. */
    RUNNING,

    /** Идёт закрытие TUN. */
    STOPPING,

    /** Запуск не удался; errorMessage содержит причину. */
    ERROR
}

/**
 * Снимок состояния для UI.
 *
 * @param phase         текущая фаза.
 * @param presetName    имя активного пресета (этап 1 — просто строка).
 * @param rotationCount сколько раз ротатор сменил стратегию (этап 3).
 * @param tunName       имя TUN-интерфейса или null.
 * @param startedAt     время запуска (millis) для счётчика «В работе».
 * @param errorMessage  текст последней ошибки.
 */
data class VpnState(
    val phase: VpnPhase = VpnPhase.STOPPED,
    val presetName: String = "AUTO",
    val rotationCount: Int = 0,
    val tunName: String? = null,
    val startedAt: Long? = null,
    val errorMessage: String? = null,
    /** Подпись активного движка, например "ciadpi + hev". */
    val engineLabel: String? = null
)

/**
 * Потокобезопасное хранилище состояния.
 *
 * Почему object, а не ViewModel: состоянием владеет служба, которая живёт
 * дольше Activity. Activity может пересоздаться (поворот экрана), а служба
 * продолжает работать — состояние должно пережить Activity.
 */
object VpnRuntime {
    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state.asStateFlow()

    /** Атомарно обновляет состояние. Можно вызывать из любого потока. */
    fun update(transform: (VpnState) -> VpnState) {
        _state.update(transform)
    }
}
