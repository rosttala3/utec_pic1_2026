package uy.edu.utec.pic1.link

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Nordic UART Service (ADR 0006): la app escribe en RX y la cámara notifica por TX. Debe coincidir con `Config.h`. */
@OptIn(ExperimentalUuidApi::class)
object Nus {
    const val DEVICE_NAME = "PIC1-ESP32"
    val SERVICE = Uuid.parse("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX = characteristicOf(SERVICE, Uuid.parse("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"))   // la app escribe
    val TX = characteristicOf(SERVICE, Uuid.parse("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"))   // la cámara notifica
    const val MTU = 185
    /** Con el MTU por defecto (23) entran 20 bytes por escritura; el firmware reensambla por `\n`. */
    const val CHUNK = 20
}

/** Reensambla líneas desde fragmentos de bytes: una línea por `\n`, ignora `\r` y las líneas vacías. */
class LineAssembler {
    private val buf = ByteArrayOutputStream()
    fun feed(chunk: ByteArray): List<String> {
        val out = ArrayList<String>()
        for (b in chunk) {
            if (b == '\n'.code.toByte()) {
                val s = buf.toString(Charsets.UTF_8).trimEnd('\r')
                buf.reset()
                if (s.isNotEmpty()) out += s
            } else buf.write(b.toInt())
        }
        return out
    }
}

/**
 * Transporte BLE real hacia la cámara. Las notificaciones pueden cortar una línea en cualquier byte o juntar
 * varias: se acumulan bytes y se emite una línea por cada `\n` (se ignora `\r`).
 */
@OptIn(ExperimentalUuidApi::class)
class BleChamberLink(private val scope: CoroutineScope) : ChamberLink {

    override val name = "Cámara real (BLE)"

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 8192)
    override val lines: SharedFlow<String> = _lines.asSharedFlow()

    private var peripheral: Peripheral? = null
    private var job: Job? = null

    override suspend fun connect() {
        if (job?.isActive == true) return
        _state.value = LinkState.Connecting
        val ready = CompletableDeferred<Unit>()
        job = scope.launch {
            try {
                val ad = withTimeoutOrNull(SCAN_TIMEOUT) {
                    Scanner().advertisements.first { it.name == Nus.DEVICE_NAME || Nus.SERVICE in it.uuids }
                }
                if (ad == null) {
                    _state.value = LinkState.Error("No se encontró ${Nus.DEVICE_NAME}. ¿Está encendida y cerca?")
                    ready.complete(Unit)
                    return@launch
                }
                val p = Peripheral(ad) { onServicesDiscovered { requestMtu(Nus.MTU) } }
                peripheral = p
                val link = p.connect()

                // Se suscribe a TX antes de avisar "conectado": el handshake de la sesión pide el estado enseguida.
                val subscribed = CompletableDeferred<Unit>()
                val asm = LineAssembler()
                link.launch {
                    p.observe(Nus.TX, onSubscription = { subscribed.complete(Unit) }).collect { chunk -> asm.feed(chunk).forEach { _lines.tryEmit(it) } }
                }
                if (withTimeoutOrNull(SUBSCRIBE_TIMEOUT) { subscribed.await() } == null) {
                    throw IllegalStateException("La cámara no ofrece el servicio UART (¿firmware viejo?)")
                }
                _state.value = LinkState.Connected
                ready.complete(Unit)

                p.state.first { it is State.Disconnected }
                if (_state.value is LinkState.Connected) _state.value = LinkState.Disconnected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = LinkState.Error(e.message ?: e.toString())
                ready.complete(Unit)
            } finally {
                peripheral?.close()
                peripheral = null
            }
        }
        ready.await()
    }

    override fun disconnect() {
        job?.cancel()
        job = null
        _state.value = LinkState.Disconnected
    }

    override suspend fun send(line: String) {
        val p = peripheral ?: throw IllegalStateException("Sin conexión")
        val bytes = (line.trim() + "\n").toByteArray(Charsets.UTF_8)
        bytes.asList().chunked(Nus.CHUNK).forEach { p.write(Nus.RX, it.toByteArray(), WriteType.WithResponse) }
    }

    private companion object {
        val SCAN_TIMEOUT = 15.seconds
        val SUBSCRIBE_TIMEOUT = 8.seconds
    }
}
