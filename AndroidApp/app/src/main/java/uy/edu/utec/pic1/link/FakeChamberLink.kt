package uy.edu.utec.pic1.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import uy.edu.utec.pic1.protocol.AssayConfig
import uy.edu.utec.pic1.protocol.Concentration
import uy.edu.utec.pic1.protocol.Fase
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Cámara simulada que habla el protocolo de líneas del ADR 0006, para probar la app sin hardware.
 *
 * Como el ESP32 real, la simulación sigue corriendo aunque no haya teléfono conectado: guarda las
 * muestras de los dos últimos ensayos y las reenvía con `historial,<n>`. El tiempo se puede acelerar
 * con [speed] (1 = tiempo real).
 */
class FakeChamberLink(
    private val scope: CoroutineScope,
    private val random: Random = Random(7),
    private val tickMs: Long = 200,
    private val replyDelayMs: Long = 60,
) : ChamberLink {

    override val name = "Cámara simulada"

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 8192)
    override val lines: SharedFlow<String> = _lines.asSharedFlow()

    /** Factor de aceleración del tiempo simulado. */
    val speed = MutableStateFlow(60)

    // ---- Estado del "dispositivo" ----------------------------------------------------------
    private val capMl = AssayConfig.DEFAULT_CAP_ML.toDouble()
    private var calAgua = 20.0          // ml/s de la válvula
    private var calSal = 100.0          // pasos del sinfín por gramo
    private val sPorG = 0.5             // segundos de sinfín por gramo
    /** El sinfín entrega algo menos de lo pedido (lazo abierto): por eso hay que ajustar con la conductividad. */
    private val entrega = 0.92

    private var tankMl = 0.0
    private var saltG = 0.0             // sal dosificada
    private var dissolvedG = 0.0        // sal disuelta (la conductividad llega con retraso)

    private enum class Sub(val fase: Fase) { Llenar(Fase.Llenado), Dosificar(Fase.Dosis), Asentar(Fase.Ajuste), Corregir(Fase.Ajuste), Correr(Fase.Ensayo) }
    private class Log(val id: Long, val seqInicial: Long) { val samples = ArrayList<DoubleArray>() }

    private var running = false
    private var cfg = AssayConfig()
    private var sub = Sub.Llenar
    private var fase: Fase? = null
    private var faseInicio = 0.0
    private var subDur = 0.0
    private var subElapsed = 0.0
    private var pedido = 0.0
    private var fisico = 0.0            // lo que de verdad cae al tanque
    private var correcciones = 0
    private var tSeg = 0.0
    private var muestreo = 60.0
    private var nextSampleAt = 60.0
    private var ensayoId = 0L
    private var seq = 0L
    private val logs = ArrayDeque<Log>()
    private var pendingRebootNotice = false
    private var temp = 24.0

    // Operaciones atómicas (`actuar(...)`): en tiempo real, sin acelerar.
    private val actOn = mutableMapOf("valvula" to false, "mezclador" to false, "bomba" to false, "led_verde" to false, "led_rojo" to false)
    private val actSince = mutableMapOf<String, Double>()
    private var sinfinBusy = false

    init {
        scope.launch {
            while (true) {
                delay(tickMs)
                if (running) advance(speed.value * tickMs / 1000.0) else manualTick(tickMs / 1000.0)
            }
        }
    }

    private fun manualTick(dt: Double) {
        for (k in listOf("valvula", "mezclador", "bomba")) {
            if (actOn[k] != true) continue
            val t = (actSince[k] ?: 0.0) + dt
            actSince[k] = t
            if (k == "valvula") { tankMl = min(capMl, tankMl + calAgua * dt); dissolvedG = dissolvedG }
            val motivo = when {
                k == "valvula" && tankMl >= capMl - 1e-6 -> "lleno"
                k == "bomba" && tankMl < 20 -> "vacio"
                t >= 60 -> "tiempo"
                else -> null
            }
            if (motivo != null) { actOn[k] = false; emit("ev,manual_fin,$k,$motivo") }
        }
    }

    private fun actuar(a: List<String>) {
        if (running) return emit("err,ocupado,hay un ensayo o una dosificacion en curso")
        val name = a.getOrNull(0) ?: ""
        val v = a.getOrNull(1) ?: return emit("err,sintaxis,uso: actuar(<actuador>,<valor>)")
        when (name) {
            "valvula", "mezclador", "bomba" -> {
                if (v != "on" && v != "off") return emit("err,sintaxis,valor: on u off")
                if (v == "on" && name == "valvula" && tankMl >= capMl - 20) return emit("err,lleno,el tanque ya esta lleno")
                if (v == "on" && name == "bomba" && tankMl < 20) return emit("err,tanque_vacio,la bomba no puede trabajar en seco")
                actOn[name] = v == "on"; actSince[name] = 0.0; emit("ok")
            }
            "sinfin" -> {
                if (v == "parar") return emit("ok")
                val x = v.toDoubleOrNull()
                if (x == null || x == 0.0 || kotlin.math.abs(x) > 20000) return emit("err,rango,pasos entre 1 y 20000 (negativo = reversa)")
                if (sinfinBusy) return emit("err,ocupado,el sinfin ya esta girando")
                emit("ok")
                sinfinBusy = true
                scope.launch {
                    delay((kotlin.math.abs(x) / 400.0 * 1000).toLong())
                    if (x > 0) { val g = x / calSal * entrega; saltG += g }
                    sinfinBusy = false
                }
            }
            "led_verde", "led_rojo" -> when (v) {
                "on" -> { actOn[name] = true; emit("ok") }
                "off", "auto" -> { actOn[name] = false; emit("ok") }
                else -> emit("err,sintaxis,valor: on, off o auto")
            }
            "buzzer" -> if ((v.toDoubleOrNull() ?: 0.0) in 100.0..8000.0) emit("ok") else emit("err,rango,frecuencia entre 100 y 8000 Hz")
            else -> emit("err,comando,actuador desconocido")
        }
    }

    // ---- ChamberLink -----------------------------------------------------------------------
    override suspend fun connect() {
        if (_state.value is LinkState.Connected) return
        _state.value = LinkState.Connecting
        delay(800)
        _state.value = LinkState.Connected
        if (pendingRebootNotice) {
            pendingRebootNotice = false
            emit("ev,abort,reinicio")
        }
    }

    override fun disconnect() {
        _state.value = LinkState.Disconnected
    }

    override suspend fun send(line: String) {
        check(_state.value is LinkState.Connected) { "Sin conexión" }
        scope.launch {
            delay(replyDelayMs)
            handle(line.trim())
        }
    }

    // ---- Fallas simuladas desde la pantalla de diagnóstico ---------------------------------
    fun injectAbort(motivo: String = "tanque_vacio") {
        if (running) abort(motivo)
    }

    /** Corte de energía: el ensayo se pierde y al reconectar se informa `ev,abort,reinicio`. */
    fun simulatePowerCut() {
        if (running) running = false
        pendingRebootNotice = true
        _state.value = LinkState.Disconnected
    }

    // ---- Intérprete de órdenes -------------------------------------------------------------
    private fun emit(line: String) {
        if (_state.value is LinkState.Connected) _lines.tryEmit(line)
    }

    private val rxOp = Regex("""^(\w+)\((.*)\)$""")

    private fun handle(cmd: String) {
        val head = cmd.substringBefore(',')
        when {
            cmd == "estado" -> {
                emit("Estado: ${if (running) "corriendo" else "reposo"}")
                emit("Temperatura: ${fmt(temp)} C · Volumen: ${fmt(tankMl)} ml")
                emit("ok")
            }
            cmd == "estado,json" -> emit(statusJson())
            cmd == "ayuda" -> {
                emit("estado | estado,json | ensayo,<conc %>,<duracion s>,<muestreo s> | detener | nivel(...) | calibrar(...) | historial,<n>")
                emit("ok")
            }
            head == "ensayo" -> startAssay(cmd.split(',').drop(1))
            cmd == "detener" -> { if (running) abort("detener"); actOn.keys.forEach { actOn[it] = false }; emit("ok") }
            head == "historial" -> sendHistory(cmd.substringAfter(',', "").toLongOrNull() ?: -1)
            else -> rxOp.matchEntire(cmd)?.let { m -> handleCall(m.groupValues[1], m.groupValues[2].split(',').map { it.trim() }) }
                ?: emit("err,comando,desconocido")
        }
    }

    private fun handleCall(op: String, a: List<String>) {
        when (op) {
            "nivel" -> manualLevel(a)
            "actuar" -> actuar(a)
            "calibrar" -> {
                val v = a.getOrNull(1)?.toDoubleOrNull()
                when {
                    v == null || v <= 0 -> emit("err,rango,valor de calibracion invalido")
                    a[0] == "agua" -> { calAgua = v; emit("ok") }
                    a[0] == "sal" -> { calSal = v; emit("ok") }
                    else -> emit("err,comando,magnitud desconocida")
                }
            }
            else -> emit("err,comando,desconocido")
        }
    }

    private fun manualLevel(a: List<String>) {
        if (running) return emit("err,ocupado,hay un ensayo en curso")
        when {
            a.getOrNull(0) == "agua" && a.getOrNull(1) == "lleno" -> doseWater(capMl - tankMl, "lleno")
            a.getOrNull(0) == "agua" -> a.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0 }?.let { doseWater(it, "ml") }
                ?: emit("err,rango,ml invalido")
            a.getOrNull(0) == "sal" && a.getOrNull(1) == "conc" -> {
                val c = a.getOrNull(2)?.toDoubleOrNull()
                if (c == null || c <= 0 || c >= 30) emit("err,rango,concentracion invalida")
                else doseSalt(Concentration.gramsFor(c, tankMl))
            }
            a.getOrNull(0) == "sal" -> a.getOrNull(1)?.toDoubleOrNull()?.takeIf { it in 0.5..AssayConfig.MAX_G.toDouble() }?.let { doseSalt(it) }
                ?: emit("err,rango,gramos invalido")
            else -> emit("err,comando,nivel desconocido")
        }
    }

    private fun doseWater(pedido: Double, kind: String) {
        val real = min(pedido, capMl - tankMl)
        tankMl += real
        emit("ok")
        emit("ev,dosis,agua,${fmt(pedido)},${fmt(real)},${if (real < pedido - 1e-6 || kind == "lleno") "lleno" else "ok"}")
    }

    private fun doseSalt(g: Double) {
        saltG += g
        emit("ok")
        emit("ev,dosis,sal,${fmt(g)},${fmt(g)},ok")
    }

    private fun sendHistory(after: Long) {
        for (log in logs) {
            val pend = log.samples.filter { it[0].toLong() > after }
            if (pend.isEmpty()) continue
            emit("ev,ensayo,${log.id},${log.seqInicial}")
            pend.forEach { emit(sampleLine(it)) }
        }
        emit("ev,sync_fin")
    }

    // ---- Ensayo automático -----------------------------------------------------------------
    private fun startAssay(a: List<String>) {
        if (running) return emit("err,ocupado,hay un ensayo en curso")
        val c = AssayConfig(
            a.getOrNull(0)?.toDoubleOrNull() ?: Double.NaN,
            a.getOrNull(1)?.toIntOrNull() ?: -1,
            a.getOrNull(2)?.toIntOrNull() ?: -1,
        )
        c.validate().firstOrNull()?.let { return emit("err,rango,${it.replace(',', ';')}") }
        cfg = c
        muestreo = c.muestreoS.toDouble()
        ensayoId++
        tSeg = 0.0; nextSampleAt = muestreo; correcciones = 0; fase = null
        logs.addLast(Log(ensayoId, seq + 1)); while (logs.size > 2) logs.removeFirst()
        running = true
        emit("ok")
        emit("ev,ensayo,$ensayoId,${seq + 1}")
        begin(Sub.Llenar)
    }

    /** Lectura de conductividad ya compensada por temperatura, en % de NaCl. */
    private fun measure() = Concentration.percent(dissolvedG, tankMl) + random.nextDouble(-0.03, 0.03)

    private fun begin(next: Sub) {
        sub = next
        if (fase != next.fase) {
            fase = next.fase; faseInicio = tSeg
            emit("ev,paso,${next.fase.index},${Fase.TOTAL},${next.fase.wire},${tSeg.toLong()}")
        }
        subElapsed = 0.0
        when (next) {
            Sub.Llenar -> { pedido = capMl - tankMl; fisico = pedido; subDur = fisico / calAgua }
            Sub.Dosificar -> {
                pedido = max(0.0, DOSIS_INICIAL * Concentration.gramsFor(cfg.concPercent, capMl) - saltG)
                fisico = pedido * entrega; subDur = pedido * sPorG
            }
            Sub.Asentar -> subDur = ASENTAR_S
            Sub.Corregir -> {
                val medida = Concentration.percent(dissolvedG, tankMl)
                pedido = max(0.0, Concentration.gramsFor(cfg.concPercent, tankMl) - Concentration.gramsFor(medida, tankMl))
                fisico = pedido * entrega; subDur = pedido * sPorG
            }
            Sub.Correr -> subDur = cfg.duracionS.toDouble()
        }
    }

    private fun endSub() {
        when (sub) {
            Sub.Llenar -> { emit("ev,dosis,agua,${fmt(pedido)},${fmt(pedido)},lleno"); begin(Sub.Dosificar) }
            Sub.Dosificar -> { emit("ev,dosis,sal,${fmt(pedido)},${fmt(pedido)},ok"); begin(Sub.Asentar) }
            Sub.Corregir -> { emit("ev,dosis,sal,${fmt(pedido)},${fmt(pedido)},ajuste"); begin(Sub.Asentar) }
            Sub.Asentar -> {
                val dif = cfg.concPercent - measure()
                when {
                    abs(dif) <= TOLERANCIA -> begin(Sub.Correr)
                    dif < 0 -> abort("conc_alta")
                    correcciones >= MAX_CORRECCIONES -> abort("no_converge")
                    else -> { correcciones++; begin(Sub.Corregir) }
                }
            }
            Sub.Correr -> finish()
        }
    }

    private fun advance(dt: Double) {
        var left = dt
        while (left > 1e-9 && running) {
            val d = max(0.0, minOf(left, subDur - subElapsed, nextSampleAt - tSeg))
            applyEffects(d)
            subElapsed += d; tSeg += d; left -= d
            if (tSeg >= nextSampleAt - 1e-9) { recordSample(); nextSampleAt += muestreo }
            if (subElapsed >= subDur - 1e-9) endSub()
        }
    }

    private fun applyEffects(d: Double) {
        if (d <= 0) return
        when (sub) {
            Sub.Llenar -> if (subDur > 0) tankMl += fisico * d / subDur
            Sub.Dosificar, Sub.Corregir -> if (subDur > 0) saltG += fisico * d / subDur
            else -> Unit
        }
        dissolvedG += (saltG - dissolvedG) * (1 - exp(-d / DISOLUCION_S))
        temp = 24.0 + 4.0 * (1 - exp(-tSeg / 7200.0))
    }

    private fun recordSample() {
        val cond = if (tankMl > 1) Concentration.percent(dissolvedG, tankMl) * (1 + random.nextDouble(-0.02, 0.02)) else 0.0
        val row = doubleArrayOf((++seq).toDouble(), tSeg.toLong().toDouble(), temp + random.nextDouble(-0.1, 0.1), cond, tankMl)
        logs.last().samples += row
        emit(sampleLine(row))
    }

    private fun finish() { running = false; emit("ev,fin") }

    private fun abort(motivo: String) { running = false; emit("ev,abort,$motivo") }

    private fun sampleLine(r: DoubleArray) =
        "t,${r[0].toLong()},${r[1].toLong()},${fmt(r[2])},${fmt(r[3], 2)},${fmt(r[4])}"

    private fun fmt(v: Double, dec: Int = 1) = String.format(Locale.ROOT, "%.${dec}f", v)

    private fun statusJson() = JSONObject().apply {
        put("estado", if (running) "corriendo" else "reposo")
        put("ensayo", ensayoId)
        put("seg", tSeg.toLong())
        put("seq", seq)
        put("temp", temp)
        put("cond", if (tankMl > 1) Concentration.percent(dissolvedG, tankMl) else 0.0)
        put("vol", tankMl)
        put("tanque", when { tankMl < 20 -> "vacio"; tankMl > capMl - 20 -> "lleno"; else -> "medio" })
        put("cap_ml", capMl.toInt())
        if (running) {
            put("fase", fase?.wire ?: "")
            put("fase_inicio", faseInicio.toLong())
            put("conc_obj", cfg.concPercent)
            put("duracion_s", cfg.duracionS)
        }
        put("reed_lleno", if (tankMl > capMl - 20) 1 else 0)
        put("reed_vacio", if (tankMl < 20) 1 else 0)
        put("forz", JSONObject().put("led_verde", if (actOn["led_verde"] == true) 1 else 0).put("led_rojo", if (actOn["led_rojo"] == true) 1 else 0))
        put("act", JSONObject().apply {
            actOn.forEach { (k, v) -> put(k, if (v) 1 else 0) }
            put("sinfin", if (sinfinBusy) 1 else 0)
        })
        put("cal", JSONObject().put("agua_ml_s", calAgua).put("sal_pasos_g", calSal))
    }.toString()

    private companion object {
        const val DOSIS_INICIAL = 0.9       // se dosifica el 90 % calculado y el resto se afina midiendo
        const val ASENTAR_S = 420.0         // mezcla antes de leer la conductividad
        const val DISOLUCION_S = 120.0
        const val TOLERANCIA = 0.15         // ± % de NaCl
        const val MAX_CORRECCIONES = 6
    }
}
