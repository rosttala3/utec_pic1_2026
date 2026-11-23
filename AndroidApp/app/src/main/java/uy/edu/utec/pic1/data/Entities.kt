package uy.edu.utec.pic1.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Un ensayo de la cámara. El id lo asigna el firmware (ADR 0004). */
@Entity(tableName = "ensayo")
data class Ensayo(
    @PrimaryKey val id: Long,
    val nombre: String,
    /** Concentración objetivo en % de NaCl. */
    val concentracion: Double?,
    val duracionS: Long?,
    val muestreoS: Int?,
    val calAgua: Double?,
    val calSal: Double?,
    val inicioMs: Long,
    val estado: String,            // en_curso | completo | abortado
    val motivo: String? = null,
    val finMs: Long? = null,
)

object EstadoEnsayo {
    const val EN_CURSO = "en_curso"
    const val COMPLETO = "completo"
    const val ABORTADO = "abortado"
}

/** Muestra periódica; clave (ensayo, secuencia) para deduplicar reenvíos y detectar huecos. */
@Entity(tableName = "muestra", primaryKeys = ["ensayoId", "seq"])
data class Muestra(
    val ensayoId: Long,
    val seq: Long,
    /** Segundos desde el inicio del ensayo; la hora absoluta se calcula con [Ensayo.inicioMs]. */
    val seg: Long,
    val temp: Double,
    val cond: Double,
    val vol: Double,
)
