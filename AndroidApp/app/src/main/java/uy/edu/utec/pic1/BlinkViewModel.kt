package uy.edu.utec.pic1

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Scanning : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

data class BlinkUiState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    /** null mientras no se conoce el estado del ESP32. */
    val blinking: Boolean? = null,
)

class BlinkViewModel : ViewModel() {

    private val _state = MutableStateFlow(BlinkUiState())
    val state: StateFlow<BlinkUiState> = _state.asStateFlow()

    private val scanner = Scanner {
        filters {
            match { services = listOf(BleConstants.SERVICE_UUID) }
        }
    }

    private var peripheral: Peripheral? = null
    private var connectionJob: Job? = null

    fun connect() {
        if (connectionJob?.isActive == true) return

        connectionJob = viewModelScope.launch {
            try {
                setStatus(ConnectionStatus.Scanning)
                val advertisement = withTimeoutOrNull(SCAN_TIMEOUT) {
                    scanner.advertisements.first()
                }
                if (advertisement == null) {
                    setStatus(ConnectionStatus.Error("No se encontró ${BleConstants.DEVICE_NAME}. ¿Está encendido?"))
                    return@launch
                }

                setStatus(ConnectionStatus.Connecting)
                val p = Peripheral(advertisement)
                peripheral = p
                val connectionScope = p.connect()
                setStatus(ConnectionStatus.Connected)

                // Notificaciones del ESP32 (por ejemplo si se cambia por Serial)
                connectionScope.launch {
                    p.observe(BleConstants.BLINK_CHARACTERISTIC).collect { data ->
                        _state.update { it.copy(blinking = data.isOn()) }
                    }
                }
                val initial = p.read(BleConstants.BLINK_CHARACTERISTIC)
                _state.update { it.copy(blinking = initial.isOn()) }

                // Esperar hasta que se pierda la conexión
                p.state.first { it is State.Disconnected }
                setStatus(ConnectionStatus.Disconnected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(ConnectionStatus.Error(e.message ?: e.toString()))
            } finally {
                peripheral?.close()
                peripheral = null
                _state.update { it.copy(blinking = null) }
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        setStatus(ConnectionStatus.Disconnected)
    }

    fun setBlinking(on: Boolean) {
        val p = peripheral ?: return
        viewModelScope.launch {
            try {
                p.write(
                    BleConstants.BLINK_CHARACTERISTIC,
                    byteArrayOf(if (on) 1 else 0),
                    WriteType.WithResponse,
                )
                _state.update { it.copy(blinking = on) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(ConnectionStatus.Error("No se pudo escribir: ${e.message}"))
            }
        }
    }

    private fun setStatus(status: ConnectionStatus) {
        _state.update { it.copy(status = status) }
    }

    private fun ByteArray.isOn() = firstOrNull()?.toInt() == 1

    private companion object {
        val SCAN_TIMEOUT = 15.seconds
    }
}
