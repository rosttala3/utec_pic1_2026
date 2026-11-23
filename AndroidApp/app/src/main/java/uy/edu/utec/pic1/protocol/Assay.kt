package uy.edu.utec.pic1.protocol

import java.util.Locale

/** Fases fijas de un ensayo; el firmware las recorre solo (ver docs/protocolo-simulador.md). */
enum class Fase(val wire: String, val index: Int, val label: String) {
    Llenado("llenado", 1, "Llenando el tanque"),
    Dosis("dosis", 2, "Dosificando la sal"),
    Ajuste("ajuste", 3, "Ajustando la concentración"),
    Ensayo("ensayo", 4, "Ensayo en curso");

    companion object {
        const val TOTAL = 4
        fun of(wire: String?): Fase? = entries.firstOrNull { it.wire == wire }
        fun label(wire: String): String = of(wire)?.label ?: wire
    }
}

/** Lo único que se configura: concentración de NaCl, duración (obligatoria) y cada cuánto se muestrea. */
data class AssayConfig(
    val concPercent: Double = 5.0,
    val duracionS: Int = 24 * 3600,
    val muestreoS: Int = 60,
) {
    fun validate(): List<String> = buildList {
        if (concPercent !in MIN_CONC..MAX_CONC) add("La concentración debe estar entre $MIN_CONC y $MAX_CONC % de NaCl.")
        if (duracionS !in MIN_DURACION_S..MAX_DURACION_S) add("La duración debe estar entre 1 min y 7 días.")
        if (muestreoS !in 5..3600) add("El muestreo debe estar entre 5 y 3600 s.")
    }

    /** Orden que se envía a la cámara: `ensayo,<conc %>,<duración s>,<muestreo s>`. */
    fun command(): String = "ensayo,${String.format(Locale.ROOT, "%.2f", concPercent)},$duracionS,$muestreoS"

    companion object {
        const val DEFAULT_CAP_ML = 1200
        const val MIN_CONC = 0.1
        const val MAX_CONC = 25.0          // la solubilidad del NaCl en agua ronda el 26 %
        const val MIN_DURACION_S = 60
        const val MAX_DURACION_S = 7 * 86_400
        const val MAX_G = 500
    }
}

object Concentration {
    /** g_sal = conc / (100 − conc) × ml_agua  (agua ≈ 1 g/ml). */
    fun gramsFor(concPercent: Double, aguaMl: Double): Double = concPercent / (100 - concPercent) * aguaMl
    fun percent(salG: Double, aguaMl: Double): Double = if (salG + aguaMl <= 0) 0.0 else salG / (salG + aguaMl) * 100
}
