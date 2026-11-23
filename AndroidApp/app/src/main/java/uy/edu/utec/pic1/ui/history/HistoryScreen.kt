package uy.edu.utec.pic1.ui.history

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import uy.edu.utec.pic1.App
import uy.edu.utec.pic1.data.Ensayo
import uy.edu.utec.pic1.data.EstadoEnsayo
import uy.edu.utec.pic1.ui.LineChart
import uy.edu.utec.pic1.ui.SectionTitle
import uy.edu.utec.pic1.ui.StatTile
import uy.edu.utec.pic1.ui.fmt
import uy.edu.utec.pic1.ui.formatDateTime
import uy.edu.utec.pic1.ui.formatDuration
import uy.edu.utec.pic1.ui.formatTime
import uy.edu.utec.pic1.ui.theme.Celeste
import uy.edu.utec.pic1.ui.theme.Metal
import uy.edu.utec.pic1.ui.theme.Rust
import uy.edu.utec.pic1.ui.theme.StatusErr
import uy.edu.utec.pic1.ui.theme.StatusOk

private fun estadoLabel(e: Ensayo) = when (e.estado) {
    EstadoEnsayo.EN_CURSO -> "En curso"
    EstadoEnsayo.COMPLETO -> "Completo"
    else -> "Abortado" + (e.motivo?.let { " · ${it.replace('_', ' ')}" } ?: "")
}

@Composable
fun HistoryScreen(app: App, onOpen: (Long) -> Unit, vm: HistoryListViewModel = viewModel { HistoryListViewModel(app) }) {
    val list by vm.ensayos.collectAsStateWithLifecycle()
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Todavía no hay ensayos.\nCuando termines uno aparece acá.", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(e.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(e.nombre, style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatDateTime(e.inicioMs) + (e.concentracion?.let { " · ${fmt(it)} % NaCl" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        estadoLabel(e), style = MaterialTheme.typography.labelLarge,
                        color = when (e.estado) { EstadoEnsayo.COMPLETO -> StatusOk; EstadoEnsayo.ABORTADO -> StatusErr; else -> Celeste },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(app: App, id: Long, onBack: () -> Unit, vm: HistoryDetailViewModel = viewModel(key = "hist-$id") { HistoryDetailViewModel(app, id) }) {
    val e by vm.ensayo.collectAsStateWithLifecycle()
    val rows by vm.muestras.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val gaps = remember(rows) { findGaps(rows) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(e?.nombre ?: "Ensayo") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Borrar") } },
        )
    }) { pad ->
        val ens = e
        if (ens == null) { Box(Modifier.padding(pad)) {}; return@Scaffold }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                formatDateTime(ens.inicioMs) + " · " + estadoLabel(ens),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ens.concentracion?.let { Text("Concentración objetivo: ${fmt(it)} % NaCl") }
            if (ens.calAgua != null && ens.calSal != null)
                Text("Calibración vigente: ${fmt(ens.calAgua)} ml/s · ${fmt(ens.calSal, 0)} pasos/g", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Muestras", rows.size.toString(), Modifier.weight(1f))
                StatTile("Duración", formatDuration(rows.lastOrNull()?.seg ?: 0), Modifier.weight(1f))
            }
            if (rows.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Temp. media", "${fmt(rows.map { it.temp }.average())} °C", Modifier.weight(1f))
                StatTile("Conc. final", "${fmt(rows.last().cond, 2)} %", Modifier.weight(1f))
            }

            LineChart("Temperatura", "°C", rows.map { it.seg.toDouble() to it.temp }, Rust)
            LineChart("Concentración", "%", rows.map { it.seg.toDouble() to it.cond }, Celeste, decimals = 2)
            LineChart("Volumen estimado", "ml", rows.map { it.seg.toDouble() to it.vol }, Metal, decimals = 0)

            if (gaps.isNotEmpty()) {
                SectionTitle("Huecos")
                gaps.forEach {
                    Text(
                        "Faltan ≈ ${formatDuration(it.missingSeg)} después de ${formatTime(ens.inicioMs + it.afterSeg * 1000)} (corte de energía de la cámara o muestras sin sincronizar).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        val f = vm.exportCsv(context)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Exportar CSV"))
                    }
                },
                enabled = rows.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
            ) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Exportar CSV") }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("¿Borrar el ensayo?") },
        text = { Text("Se borran sus muestras de este teléfono. No se puede deshacer.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
    )
}
