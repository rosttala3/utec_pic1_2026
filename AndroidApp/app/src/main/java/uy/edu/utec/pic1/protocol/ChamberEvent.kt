package uy.edu.utec.pic1.protocol

import org.json.JSONObject

/** Líneas que el firmware envía a la app (ADR 0006). */
sealed interface ChamberLine {
    data object Ok : ChamberLine
    data class Err(val code: String, val message: String) : ChamberLine
    /** Respuesta de varias líneas o de texto libre (`estado`, `ayuda`, `receta_ver`). */
    data class Text(val raw: String) : ChamberLine

    data class Sample(val seq: Long, val seg: Long, val temp: Double, val cond: Double, val vol: Double) : ChamberLine
    /** Evento `ev,ensayo,<id>,<seq_inicial>`: las muestras que siguen pertenecen a este ensayo. */
    data class EnsayoMark(val id: Long, val seqInicial: Long) : ChamberLine
    data object SyncEnd : ChamberLine
    data class StepStart(val index: Int, val total: Int, val op: String, val seg: Long? = null) : ChamberLine
    data class Dose(val op: String, val pedido: Double, val real: Double, val motivo: String) : ChamberLine
    data object Fin : ChamberLine
    data class Abort(val motivo: String) : ChamberLine
    /** `ev,manual_fin,<actuador>,<motivo>`: un relé de una operación manual se apagó solo (lleno, vacio o tiempo). */
    data class ManualFin(val actuador: String, val motivo: String) : ChamberLine

    /** Eventos espontáneos: no son respuesta a una orden. */
    val isEvent: Boolean get() = this is Sample || this is EnsayoMark || this is SyncEnd || this is StepStart ||
        this is Dose || this is Fin || this is Abort || this is ManualFin

    companion object {
        fun parse(line: String): ChamberLine {
            val l = line.trim()
            val p = l.split(',')
            return try {
                when {
                    l == "ok" -> Ok
                    p[0] == "err" -> Err(p.getOrElse(1) { "?" }, p.drop(2).joinToString(","))
                    p[0] == "t" && p.size >= 6 -> Sample(p[1].toLong(), p[2].toLong(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
                    p[0] == "ev" -> when (p.getOrNull(1)) {
                        "ensayo" -> EnsayoMark(p[2].toLong(), p[3].toLong())
                        "sync_fin" -> SyncEnd
                        "paso" -> StepStart(p[2].toInt(), p[3].toInt(), p[4], p.getOrNull(5)?.toLongOrNull())
                        "dosis" -> Dose(p[2], p[3].toDouble(), p[4].toDouble(), p[5])
                        "fin" -> Fin
                        "abort" -> Abort(p.getOrElse(2) { "desconocido" })
                        "manual_fin" -> ManualFin(p[2], p.getOrElse(3) { "" })
                        else -> Text(l)
                    }
                    else -> Text(l)
                }
            } catch (_: Exception) {
                Text(l)
            }
        }
    }
}

enum class Tank { Vacio, Medio, Lleno }

/** Respuesta de `estado,json`. Los campos son una propuesta de la app; ver docs/protocolo-simulador.md. */
data class ChamberStatus(
    val running: Boolean,
    val ensayoId: Long,
    val segundos: Long,
    val seq: Long,
    val temp: Double,
    val cond: Double,
    val vol: Double,
    val tank: Tank,
    val capMl: Int,
    val calAgua: Double,
    val calSal: Double,
    /** Solo con un ensayo en curso: fase actual, segundo en que empezó, objetivo y duración configurados. */
    val fase: String? = null,
    val faseInicioS: Long = 0,
    val concObjetivo: Double? = null,
    val duracionS: Long? = null,
    /** Actuadores encendidos ahora (`valvula`, `mezclador`, `bomba`, `sinfin`, `led_verde`, `led_rojo`) y reeds de nivel. */
    val actuadores: Map<String, Boolean> = emptyMap(),
    /** LED que la app fuerza a mano; si no, el LED se enciende solo según la concentración y `actuadores` solo dice cómo está ahora. */
    val forzados: Set<String> = emptySet(),
    val reedLleno: Boolean = false,
    val reedVacio: Boolean = false,
) {
    companion object {
        fun parse(json: String): ChamberStatus? = try {
            val o = JSONObject(json)
            val cal = o.optJSONObject("cal")
            ChamberStatus(
                running = o.getString("estado") == "corriendo",
                ensayoId = o.optLong("ensayo", 0),
                segundos = o.optLong("seg", 0),
                seq = o.optLong("seq", 0),
                temp = o.optDouble("temp", Double.NaN),
                cond = o.optDouble("cond", Double.NaN),
                vol = o.optDouble("vol", Double.NaN),
                tank = when (o.optString("tanque")) { "vacio" -> Tank.Vacio; "lleno" -> Tank.Lleno; else -> Tank.Medio },
                capMl = o.optInt("cap_ml", AssayConfig.DEFAULT_CAP_ML),
                calAgua = cal?.optDouble("agua_ml_s", 20.0) ?: 20.0,
                calSal = cal?.optDouble("sal_pasos_g", 100.0) ?: 100.0,
                fase = o.optString("fase", "").ifEmpty { null },
                faseInicioS = o.optLong("fase_inicio", 0),
                concObjetivo = if (o.has("conc_obj")) o.getDouble("conc_obj") else null,
                duracionS = if (o.has("duracion_s")) o.getLong("duracion_s") else null,
                actuadores = o.optJSONObject("act")?.let { a -> a.keys().asSequence().associateWith { a.optInt(it) == 1 } } ?: emptyMap(),
                forzados = o.optJSONObject("forz")?.let { f -> f.keys().asSequence().filter { f.optInt(it) == 1 }.toSet() } ?: emptySet(),
                reedLleno = o.optInt("reed_lleno") == 1,
                reedVacio = o.optInt("reed_vacio") == 1,
            )
        } catch (_: Exception) { null }
    }
}
