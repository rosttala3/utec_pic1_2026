package uy.edu.utec.pic1.link

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface LinkState {
    data object Disconnected : LinkState
    data object Connecting : LinkState
    data object Connected : LinkState
    data class Error(val message: String) : LinkState
}

/**
 * Transporte de líneas de texto hacia la cámara (ADR 0006: un mismo parser atiende UART y BLE).
 * Hoy solo existe [FakeChamberLink]; el transporte BLE/NUS real implementará esta misma interfaz.
 */
interface ChamberLink {
    val name: String
    val state: StateFlow<LinkState>
    /** Líneas que llegan de la cámara, sin el `\n` final. */
    val lines: SharedFlow<String>
    suspend fun connect()
    fun disconnect()
    /** Envía una línea (sin `\n`). */
    suspend fun send(line: String)
}
