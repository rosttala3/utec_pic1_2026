package uy.edu.utec.pic1.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

enum class LinkMode { Simulada, Real }

/** Enlace único para la sesión que delega en el simulador o en el BLE real según [mode]. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchLink(
    scope: CoroutineScope,
    private val fake: ChamberLink,
    private val real: ChamberLink,
    initial: LinkMode,
    private val onMode: (LinkMode) -> Unit = {},
) : ChamberLink {
    val mode = MutableStateFlow(initial)
    private val current get() = if (mode.value == LinkMode.Real) real else fake

    override val name get() = current.name
    override val state: StateFlow<LinkState> =
        mode.flatMapLatest { if (it == LinkMode.Real) real.state else fake.state }
            .stateIn(scope, SharingStarted.Eagerly, LinkState.Disconnected)
    override val lines: SharedFlow<String> =
        mode.flatMapLatest { if (it == LinkMode.Real) real.lines else fake.lines }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    /** Cambia de transporte; el que estaba conectado se desconecta. */
    fun select(m: LinkMode) {
        if (m == mode.value) return
        current.disconnect()
        mode.value = m
        onMode(m)
    }

    override suspend fun connect() = current.connect()
    override fun disconnect() = current.disconnect()
    override suspend fun send(line: String) = current.send(line)
}
