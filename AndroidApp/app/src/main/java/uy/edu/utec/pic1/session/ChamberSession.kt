package uy.edu.utec.pic1.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import uy.edu.utec.pic1.data.AppDatabase
import uy.edu.utec.pic1.data.Ensayo
import uy.edu.utec.pic1.data.EstadoEnsayo
import uy.edu.utec.pic1.data.Muestra
import uy.edu.utec.pic1.link.ChamberLink
import uy.edu.utec.pic1.link.LinkState
import uy.edu.utec.pic1.protocol.ChamberLine
import uy.edu.utec.pic1.protocol.ChamberStatus
import uy.edu.utec.pic1.protocol.AssayConfig
import uy.edu.utec.pic1.protocol.Fase

enum class NoticeLevel { Info, Warn, Error }
data class Notice(val atMs: Long, val level: NoticeLevel, val text: String)
/** Fase en curso; [segInicio] es el segundo del ensayo en que empezó (para medir el avance de la última fase). */
data class StepProgress(val index: Int, val total: Int, val op: String, val segInicio: Long? = null)
data class AbortInfo(val motivo: String, val atMs: Long, val step: StepProgress?)

data class LiveState(
    val link: LinkState = LinkState.Disconnected,
    val status: ChamberStatus? = null,
    val ensayo: Ensayo? = null,
    val lastSample: Muestra? = null,
    val step: StepProgress? = null,
    val syncing: Boolean = false,
    val syncReceived: Int = 0,
    val uploading: Boolean = false,
    val notices: List<Notice> = emptyList(),
    val aborted: AbortInfo? = null,
) {
    val running: Boolean get() = ensayo?.estado == EstadoEnsayo.EN_CURSO
}

class ChamberException(val code: String, message: String) : Exception(message)

/** Metadatos del ensayo que la app está por iniciar; se aplican cuando el firmware anuncia el id. */
private data class PendingMeta(val nombre: String, val cfg: AssayConfig, val calAgua: Double?, val calSal: Double?)

/**
 * Sesión con la cámara: envía órdenes, interpreta las líneas que llegan, guarda las muestras en Room
 * y expone un estado observable para la UI. Funciona igual con el simulador o con un transporte real.
 */
class ChamberSession(
    val link: ChamberLink,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val _console = MutableStateFlow<List<String>>(emptyList())
    /** Tráfico crudo (`→` enviado, `←` recibido), para la pantalla de diagnóstico. */
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val cmdMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<ChamberLine>? = null
    private var syncDone: CompletableDeferred<Unit>? = null
    private var currentEnsayo: Long? = null
    private var pendingMeta: PendingMeta? = null
    private val sampleBuf = ArrayList<Muestra>()
    private val anchorLater = mutableSetOf<Long>()
    private var syncSeen = 0

    init {
        scope.launch { link.lines.collect { onLine(it) } }
        scope.launch { link.state.collect { onLink(it) } }
    }

    // ---- Conexión ----------------------------------------------------------------------------
    fun connect() { scope.launch { link.connect() } }
    fun disconnect() = link.disconnect()

    private suspend fun onLink(s: LinkState) {
        _live.update { it.copy(link = s, syncing = if (s is LinkState.Connected) it.syncing else false) }
        if (s is LinkState.Connected) scope.launch { handshake() }
        else pending?.completeExceptionally(ChamberException("enlace", "Se perdió la conexión"))
    }

    /** Al conectar: estado actual y sincronización de lo que falta (ADR 0004 / 0006). */
    private suspend fun handshake() {
        val st = refreshStatus().getOrNull()
        if (st != null && st.running && st.ensayoId > 0) {
            // Ensayo en curso (quizá iniciado desde otro teléfono o antes de reiniciar la app): las muestras que lleguen son suyas.
            currentEnsayo = st.ensayoId
            Fase.of(st.fase)?.let { f -> _live.update { it.copy(step = StepProgress(f.index, Fase.TOTAL, f.wire, st.faseInicioS)) } }
            if (db.ensayos().get(st.ensayoId) == null)
                db.ensayos().insert(newEnsayo(st.ensayoId, null, st, inicioMs = now() - st.segundos * 1000))
        }
        sync()
        st?.let { _live.update { l -> l.copy(ensayo = if (it.running) db.ensayos().get(it.ensayoId) else l.ensayo) } }
    }

    suspend fun sync() {
        val done = CompletableDeferred<Unit>()
        syncDone = done; syncSeen = 0
        _live.update { it.copy(syncing = true, syncReceived = 0) }
        val last = db.muestras().maxSeq()
        try {
            log("→ historial,$last")
            link.send("historial,$last")
            withTimeout(15_000) { done.await() }
        } catch (_: Exception) {
            _live.update { it.copy(syncing = false) }
        }
    }

    suspend fun refreshStatus(): Result<ChamberStatus> = run("estado,json").mapCatching {
        ChamberStatus.parse(it) ?: throw ChamberException("estado", "Estado ilegible")
    }.onSuccess { st -> _live.update { it.copy(status = st) } }

    // ---- Órdenes -----------------------------------------------------------------------------
    /** Envía una orden y espera `ok`, `err` o una línea de datos. */
    suspend fun run(line: String, timeoutMs: Long = 5_000): Result<String> = cmdMutex.withLock {
        val d = CompletableDeferred<ChamberLine>()
        pending = d
        try {
            log("→ $line")
            link.send(line)
            when (val r = withTimeout(timeoutMs) { d.await() }) {
                is ChamberLine.Ok -> Result.success("")
                is ChamberLine.Err -> Result.failure(ChamberException(r.code, friendly(r)))
                is ChamberLine.Text -> Result.success(r.raw)
                else -> Result.success("")
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(ChamberException("timeout", "La cámara no respondió"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pending = null
        }
    }

    fun friendly(e: ChamberLine.Err) = when (e.code) {
        "ocupado" -> "La cámara está ocupada: ${e.message}."
        "rango" -> "Valor fuera de rango: ${e.message}."
        else -> "Error ${e.code}: ${e.message}"
    }

    /** Inicia un ensayo automático: la cámara llena, dosifica, ajusta por conductividad y corre la duración pedida. */
    suspend fun startAssay(cfg: AssayConfig): Result<Unit> {
        cfg.validate().firstOrNull()?.let { return Result.failure(ChamberException("rango", it)) }
        val st = _live.value.status
        pendingMeta = PendingMeta(assayName(cfg), cfg, st?.calAgua, st?.calSal)
        _live.update { it.copy(aborted = null) }
        return run(cfg.command()).map { }.onFailure { pendingMeta = null }
    }

    suspend fun stop() = run("detener").map { }

    suspend fun calibrate(kind: String, value: Double): Result<Unit> =
        run("calibrar($kind,$value)").map { }.also { refreshStatus() }

    suspend fun manual(order: String): Result<Unit> = run(order).map { }.also { refreshStatus() }

    suspend fun sendRaw(line: String) { run(line) }

    fun dismissAbort() = _live.update { it.copy(aborted = null) }
    fun clearNotices() = _live.update { it.copy(notices = emptyList()) }

    // ---- Líneas entrantes ----------------------------------------------------------------------
    private suspend fun onLine(raw: String) {
        log("← $raw")
        val line = ChamberLine.parse(raw)
        if (!line.isEvent) { pending?.complete(line); return }
        when (line) {
            is ChamberLine.EnsayoMark -> onMark(line)
            is ChamberLine.Sample -> onSample(line)
            is ChamberLine.SyncEnd -> onSyncEnd()
            is ChamberLine.StepStart -> _live.update { it.copy(step = StepProgress(line.index, line.total, line.op, line.seg)) }
            is ChamberLine.Dose -> onDose(line)
            is ChamberLine.Fin -> onFin()
            is ChamberLine.Abort -> onAbort(line)
            is ChamberLine.ManualFin -> {
                notice(NoticeLevel.Info, "${line.actuador} se apagó solo (${when (line.motivo) { "lleno" -> "tanque lleno"; "vacio" -> "tanque vacío"; else -> "tiempo máximo" }}).")
                scope.launch { refreshStatus() }
            }
            else -> Unit
        }
    }

    private fun newEnsayo(id: Long, meta: PendingMeta?, st: ChamberStatus?, inicioMs: Long) = Ensayo(
        id = id,
        nombre = meta?.nombre ?: st?.concObjetivo?.let { assayName(it, st.duracionS) } ?: "Ensayo #$id",
        concentracion = meta?.cfg?.concPercent ?: st?.concObjetivo,
        duracionS = meta?.cfg?.duracionS?.toLong() ?: st?.duracionS,
        muestreoS = meta?.cfg?.muestreoS,
        calAgua = meta?.calAgua ?: st?.calAgua,
        calSal = meta?.calSal ?: st?.calSal,
        inicioMs = inicioMs,
        estado = if (meta != null || (st?.running == true && st.ensayoId == id)) EstadoEnsayo.EN_CURSO else EstadoEnsayo.COMPLETO,
    )

    private suspend fun onMark(m: ChamberLine.EnsayoMark) {
        currentEnsayo = m.id
        val meta = if (_live.value.syncing) null else pendingMeta.also { pendingMeta = null }
        if (db.ensayos().get(m.id) == null) {
            db.ensayos().insert(newEnsayo(m.id, meta, _live.value.status, inicioMs = now()))
            if (_live.value.syncing) anchorLater += m.id
        }
        if (!_live.value.syncing) _live.update { it.copy(ensayo = null, step = null, lastSample = null) }
        refreshLiveEnsayo(m.id)
    }

    private suspend fun refreshLiveEnsayo(id: Long) {
        val e = db.ensayos().get(id)
        _live.update { it.copy(ensayo = e) }
    }

    private suspend fun onSample(s: ChamberLine.Sample) {
        val id = currentEnsayo ?: return
        val m = Muestra(id, s.seq, s.seg, s.temp, s.cond, s.vol)
        if (_live.value.syncing) {
            sampleBuf += m; syncSeen++
            if (sampleBuf.size >= 200) flush()
            _live.update { it.copy(syncReceived = syncSeen) }
        } else {
            db.muestras().insertAll(listOf(m))
            _live.update { it.copy(lastSample = m) }
        }
    }

    private suspend fun flush() {
        if (sampleBuf.isEmpty()) return
        db.muestras().insertAll(sampleBuf.toList()); sampleBuf.clear()
    }

    private suspend fun onSyncEnd() {
        flush()
        for (id in anchorLater) {
            val last = db.muestras().all(id).lastOrNull() ?: continue
            db.ensayos().setInicio(id, now() - last.seg * 1000)
        }
        anchorLater.clear()
        val last = currentEnsayo?.let { id -> db.muestras().all(id).lastOrNull() }
        _live.update { it.copy(syncing = false, lastSample = last ?: it.lastSample) }
        currentEnsayo?.let { refreshLiveEnsayo(it) }
        syncDone?.complete(Unit)
    }

    private fun notice(level: NoticeLevel, text: String) =
        _live.update { it.copy(notices = (listOf(Notice(now(), level, text)) + it.notices).take(30)) }

    private fun onDose(d: ChamberLine.Dose) {
        val u = if (d.op == "agua") "ml" else "g"
        notice(NoticeLevel.Info, when {
            d.motivo == "ajuste" -> "Ajuste fino: +${"%.1f".format(d.real)} g de sal según la conductividad."
            d.motivo == "lleno" && d.real < d.pedido - 0.5 -> "Se cortó el agua al llenarse el tanque (pedido ${"%.0f".format(d.pedido)} $u, real ${"%.0f".format(d.real)} $u)."
            else -> "Dosificado ${d.op}: ${"%.0f".format(d.real)} $u."
        })
    }

    private suspend fun onFin() {
        val id = currentEnsayo
        if (id != null) db.ensayos().close(id, EstadoEnsayo.COMPLETO, null, now())
        notice(NoticeLevel.Info, "El ensayo terminó.")
        _live.update { it.copy(step = null) }
        id?.let { refreshLiveEnsayo(it) }
        scope.launch { refreshStatus() }  // no se puede esperar la respuesta desde el colector de líneas
    }

    private suspend fun onAbort(a: ChamberLine.Abort) {
        val step = _live.value.step
        if (a.motivo == "reinicio") db.ensayos().abortRunning("reinicio", now())
        else currentEnsayo?.let { db.ensayos().close(it, EstadoEnsayo.ABORTADO, a.motivo, now()) }
        _live.update { it.copy(aborted = AbortInfo(a.motivo, now(), step), step = null) }
        notice(NoticeLevel.Error, "Ensayo abortado: ${a.motivo}.")
        currentEnsayo?.let { refreshLiveEnsayo(it) }
        if (a.motivo == "reinicio") _live.update { it.copy(ensayo = null) }
        scope.launch { refreshStatus() }
    }

    private fun log(s: String) = _console.update { (it + s).takeLast(300) }
}

private fun fmtNum(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(java.util.Locale("es", "UY"), v)

private fun durText(s: Long) = when {
    s % 86_400 == 0L -> "${s / 86_400} d"
    s % 3_600 == 0L -> "${s / 3_600} h"
    s >= 3_600 -> "%.1f h".format(java.util.Locale("es", "UY"), s / 3600.0)
    else -> "${s / 60} min"
}

fun assayName(conc: Double, duracionS: Long?) = "NaCl ${fmtNum(conc)} %" + (duracionS?.let { " · ${durText(it)}" } ?: "")
fun assayName(cfg: AssayConfig) = assayName(cfg.concPercent, cfg.duracionS.toLong())
