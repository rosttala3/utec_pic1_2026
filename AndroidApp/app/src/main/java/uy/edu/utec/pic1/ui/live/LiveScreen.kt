package uy.edu.utec.pic1.ui.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import uy.edu.utec.pic1.App
import uy.edu.utec.pic1.R
import uy.edu.utec.pic1.link.LinkMode
import uy.edu.utec.pic1.link.LinkState
import uy.edu.utec.pic1.ui.rememberBluetoothGate
import uy.edu.utec.pic1.protocol.AssayConfig
import uy.edu.utec.pic1.protocol.Concentration
import uy.edu.utec.pic1.protocol.Fase
import uy.edu.utec.pic1.protocol.Tank
import uy.edu.utec.pic1.session.LiveState
import uy.edu.utec.pic1.session.NoticeLevel
import uy.edu.utec.pic1.ui.LineChart
import uy.edu.utec.pic1.ui.SectionTitle
import uy.edu.utec.pic1.ui.StatTile
import uy.edu.utec.pic1.ui.fmt
import uy.edu.utec.pic1.ui.formatDuration
import uy.edu.utec.pic1.ui.formatTime
import uy.edu.utec.pic1.ui.theme.Celeste
import uy.edu.utec.pic1.ui.theme.Rust
import uy.edu.utec.pic1.ui.theme.StatusErr
import uy.edu.utec.pic1.ui.theme.StatusWarn

@Composable
fun LiveScreen(app: App, snackbar: SnackbarHostState, vm: LiveViewModel = androidx.lifecycle.viewmodel.compose.viewModel { LiveViewModel(app) }) {
    val live by vm.live.collectAsStateWithLifecycle()
    val samples by vm.samples.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val connectBle = rememberBluetoothGate(onReady = vm::connect)

    fun report(block: suspend () -> Result<Unit>) = scope.launch { block().onFailure { snackbar.showSnackbar(it.message ?: "Error") } }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (live.link !is LinkState.Connected) {
            ConnectCard(live, mode, speed, onMode = vm::selectMode, onConnect = { if (mode == LinkMode.Real) connectBle() else vm.connect() }, onSpeed = vm::setSpeed)
            return@Column
        }

        if (live.syncing) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Sincronizando con la cámara… ${live.syncReceived} muestras")
            }
        }

        live.aborted?.let { a -> AbortCard(a.motivo, a.step?.let { "${Fase.label(it.op)} (${it.index} de ${it.total})" }, a.atMs, vm::dismissAbort) }

        if (live.running) {
            RunningCard(live, samples, speed, onSpeed = vm::setSpeed, onStop = { report { vm.stop() } })
        } else {
            IdleCard(live, onStart = { report { vm.startAssay(it) } })
        }

        if (live.notices.isNotEmpty()) {
            SectionTitle("Eventos")
            live.notices.take(4).forEach { n ->
                Text(
                    "${formatTime(n.atMs)}  ${n.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (n.level) {
                        NoticeLevel.Error -> StatusErr
                        NoticeLevel.Warn -> StatusWarn
                        NoticeLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectCard(live: LiveState, mode: LinkMode, speed: Int, onMode: (LinkMode) -> Unit, onConnect: () -> Unit, onSpeed: (Int) -> Unit) {
    val connecting = live.link is LinkState.Connecting
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.logo_utecorroe), null, Modifier.size(140.dp).alpha(if (connecting) .6f else 1f))
        Text("Cámara de corrosión", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (mode == LinkMode.Real) "Encendé la cámara y acercate: la app la busca como PIC1-ESP32 por Bluetooth."
            else "Estás usando una cámara simulada, sin hardware.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == LinkMode.Real, onClick = { onMode(LinkMode.Real) }, label = { Text("Cámara real") }, enabled = !connecting)
            FilterChip(selected = mode == LinkMode.Simulada, onClick = { onMode(LinkMode.Simulada) }, label = { Text("Simulada") }, enabled = !connecting)
        }
        (live.link as? LinkState.Error)?.let { Text(it.message, color = StatusErr) }
        Button(onClick = onConnect, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
            if (connecting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
            Text(if (connecting) (if (mode == LinkMode.Real) "Buscando la cámara…" else "Conectando…") else if (mode == LinkMode.Real) "Conectar a la cámara" else "Conectar a la cámara simulada")
        }
        if (mode == LinkMode.Simulada) SpeedChips(speed, onSpeed)
    }
}

/** Aceleración del tiempo del simulador. */
@Composable
fun SpeedChips(speed: Int, onSpeed: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Velocidad del simulador", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 60, 600, 3600).forEach {
                FilterChip(selected = speed == it, onClick = { onSpeed(it) }, label = { Text("${it}×") })
            }
        }
    }
}

@Composable
private fun AbortCard(motivo: String, paso: String?, atMs: Long, onDismiss: () -> Unit) {
    val reinicio = motivo == "reinicio"
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(if (reinicio) "La cámara se reinició" else "Ensayo abortado", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                when (motivo) {
                    "reinicio" -> "Se cortó la energía. Por seguridad la cámara no retoma sola: empezá un ensayo nuevo."
                    "tanque_vacio" -> "El tanque quedó vacío y la bomba se apagó."
                    "llenado" -> "El tanque no se llenó a tiempo. Revisá el depósito de agua y la electroválvula."
                    "sensor" -> "No hay lectura de conductividad. Revisá los electrodos."
                    "sensores" -> "Los dos sensores de nivel están activos a la vez. Revisá los reeds."
                    "conc_alta" -> "La concentración medida pasó el objetivo y no se puede quitar sal. Vaciá el tanque y empezá de nuevo."
                    "no_converge" -> "No se logró llegar a la concentración pedida. Revisá que haya sal y que el sensor esté limpio."
                    "detener" -> "Lo detuviste a las ${formatTime(atMs)}."
                    else -> "Motivo: $motivo."
                } + (paso?.let { " Iba en: $it." } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) { Text("Entendido") }
        }
    }
}

@Composable
private fun MetricGrid(live: LiveState, target: Double? = null) {
    val s = live.lastSample
    val st = live.status
    val temp = s?.temp ?: st?.temp ?: Double.NaN
    val cond = s?.cond ?: st?.cond ?: Double.NaN
    val vol = s?.vol ?: st?.vol ?: Double.NaN
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Temperatura", if (temp.isNaN()) "—" else "${fmt(temp)} °C", Modifier.weight(1f))
            StatTile(if (target != null) "Concentración (obj. ${fmt(target)} %)" else "Concentración", if (cond.isNaN()) "—" else "${fmt(cond)} %", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Volumen est.", if (vol.isNaN()) "—" else "${fmt(vol, 0)} ml", Modifier.weight(1f))
            StatTile("Tanque", when (st?.tank) { Tank.Vacio -> "Vacío"; Tank.Lleno -> "Lleno"; Tank.Medio -> "Medio"; null -> "—" }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RunningCard(live: LiveState, samples: List<uy.edu.utec.pic1.data.Muestra>, speed: Int, onSpeed: (Int) -> Unit, onStop: () -> Unit) {
    val e = live.ensayo
    val elapsed = live.lastSample?.seg ?: 0L
    var confirm by remember { mutableStateOf(false) }
    val step = live.step
    val dur = e?.duracionS
    val inRun = step?.op == Fase.Ensayo.wire && dur != null && dur > 0

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(e?.nombre ?: "Ensayo en curso", style = MaterialTheme.typography.titleLarge)
        step?.let { Text("${Fase.label(it.op)} · paso ${it.index} de ${it.total}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (inRun) {
            val done = (elapsed - (step?.segInicio ?: 0L)).coerceAtLeast(0)
            LinearProgressIndicator(progress = { (done.toFloat() / dur!!).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(8.dp), color = Rust)
            Text("${formatDuration(done)} transcurridos · faltan ≈ ${formatDuration(dur - done)}", style = MaterialTheme.typography.bodySmall)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), color = Rust)
            Text(
                if (step?.op == Fase.Ajuste.wire) "Afinando con la conductividad: la cámara mide, dosifica y vuelve a medir."
                else "Preparando la muestra. La duración del ensayo empieza a contar cuando esté lista.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        MetricGrid(live, e?.concentracion)
        LineChart("Temperatura", "°C", samples.map { it.seg.toDouble() to it.temp }, Rust)
        LineChart("Concentración", "%", samples.map { it.seg.toDouble() to it.cond }, Celeste, decimals = 2)
        SpeedChips(speed, onSpeed)
        OutlinedButton(
            onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Detener ensayo") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("¿Detener el ensayo?") },
        text = { Text("La cámara apaga todo y el ensayo queda en el historial como incompleto.") },
        confirmButton = { TextButton(onClick = { confirm = false; onStop() }) { Text("Detener") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Seguir") } },
    )
}

private fun parseNum(t: String) = t.trim().replace(',', '.').toDoubleOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleCard(live: LiveState, onStart: (AssayConfig) -> Unit) {
    var conc by remember { mutableStateOf("5") }
    var horas by remember { mutableStateOf("24") }
    var muestreo by remember { mutableStateOf("60") }
    var avanzado by remember { mutableStateOf(false) }

    val cfg = AssayConfig(parseNum(conc) ?: Double.NaN, ((parseNum(horas) ?: 0.0) * 3600).roundToInt(), muestreo.toIntOrNull() ?: 0)
    val problems = cfg.validate()
    val cap = live.status?.capMl ?: AssayConfig.DEFAULT_CAP_ML

    Text("Cámara lista", style = MaterialTheme.typography.titleLarge)
    live.ensayo?.takeIf { it.estado != "en_curso" }?.let {
        Text("Último ensayo: ${it.nombre} (${it.estado.replace('_', ' ')})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    MetricGrid(live)

    SectionTitle("Nuevo ensayo")
    Text(
        "Elegí la concentración y cuánto dura. La cámara llena el tanque, agrega la sal y la ajusta midiendo la conductividad hasta llegar al valor pedido.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        conc, { conc = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, label = { Text("Concentración de sal (% NaCl)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("3,5" to "3,5 % · agua de mar", "5" to "5 % · ASTM B117").forEach { (v, l) ->
            FilterChip(selected = parseNum(conc) == parseNum(v), onClick = { conc = v }, label = { Text(l) })
        }
    }
    OutlinedTextField(
        horas, { horas = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) }, label = { Text("Duración del ensayo (horas)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("1" to "1 h", "6" to "6 h", "24" to "24 h", "72" to "72 h", "168" to "7 d").forEach { (v, l) ->
            FilterChip(selected = parseNum(horas) == parseNum(v), onClick = { horas = v }, label = { Text(l) })
        }
    }
    TextButton(onClick = { avanzado = !avanzado }) { Text(if (avanzado) "Ocultar opciones avanzadas" else "Opciones avanzadas") }
    if (avanzado) OutlinedTextField(
        muestreo, { muestreo = it.filter(Char::isDigit).take(4) }, label = { Text("Una muestra cada (s)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
    )

    if (problems.isEmpty()) Text(
        "Hacen falta ≈ ${fmt(Concentration.gramsFor(cfg.concPercent, cap.toDouble()), 0)} g de sal para $cap ml de agua.",
        style = MaterialTheme.typography.bodySmall,
    ) else Text(problems.first(), style = MaterialTheme.typography.bodySmall, color = StatusErr)

    Button(onClick = { onStart(cfg) }, enabled = problems.isEmpty(), modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Iniciar ensayo")
    }
}
