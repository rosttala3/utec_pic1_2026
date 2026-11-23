package uy.edu.utec.pic1.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.edu.utec.pic1.App
import uy.edu.utec.pic1.data.Ensayo
import uy.edu.utec.pic1.data.Muestra
import java.io.File
import java.util.Locale

class HistoryListViewModel(app: App) : ViewModel() {
    val ensayos: StateFlow<List<Ensayo>> = app.db.ensayos().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class Gap(val afterSeg: Long, val missingSeg: Long)

class HistoryDetailViewModel(private val app: App, private val id: Long) : ViewModel() {
    val ensayo: StateFlow<Ensayo?> = app.db.ensayos().observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val muestras: StateFlow<List<Muestra>> = app.db.muestras().observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            app.db.muestras().deleteFor(id); app.db.ensayos().delete(id); onDone()
        }
    }

    /** Escribe el CSV en el caché y devuelve el archivo (formato de la propuesta + hora absoluta y volumen). */
    suspend fun exportCsv(context: Context): File = withContext(Dispatchers.IO) {
        val e = app.db.ensayos().get(id)!!
        val rows = app.db.muestras().all(id)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "ensayo_${id}.csv")
        f.bufferedWriter().use { w ->
            w.appendLine("Tiempo,Temperatura,Conductividad,Volumen,Hora")
            rows.forEach {
                val hora = java.time.Instant.ofEpochMilli(e.inicioMs + it.seg * 1000).toString()
                w.appendLine(String.format(Locale.ROOT, "%d,%.1f,%.2f,%.1f,%s", it.seg, it.temp, it.cond, it.vol, hora))
            }
        }
        f
    }
}

/** Huecos: saltos de tiempo mayores a 2,5 veces el intervalo típico de muestreo. */
fun findGaps(rows: List<Muestra>): List<Gap> {
    if (rows.size < 3) return emptyList()
    val diffs = rows.zipWithNext { a, b -> b.seg - a.seg }.sorted()
    val typical = diffs[diffs.size / 2].coerceAtLeast(1)
    return rows.zipWithNext().mapNotNull { (a, b) ->
        if (b.seg - a.seg > typical * 5 / 2) Gap(a.seg, b.seg - a.seg - typical) else null
    }
}
