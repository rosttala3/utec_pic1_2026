package uy.edu.utec.pic1.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uy.edu.utec.pic1.App
import uy.edu.utec.pic1.link.LinkMode
import uy.edu.utec.pic1.link.LinkState
import uy.edu.utec.pic1.ui.SectionTitle
import uy.edu.utec.pic1.ui.fmt
import uy.edu.utec.pic1.ui.live.SpeedChips

@Composable
fun SettingsScreen(app: App, snackbar: SnackbarHostState, onDiagnostics: () -> Unit) {
    val live by app.session.live.collectAsStateWithLifecycle()
    val speed by app.fakeLink.speed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val mode by app.link.mode.collectAsStateWithLifecycle()
    val connected = live.link is LinkState.Connected
    val idle = connected && !live.running
    val st = live.status

    var agua by remember(st?.calAgua) { mutableStateOf(st?.calAgua?.let { fmt(it).replace(',', '.') } ?: "") }
    var sal by remember(st?.calSal) { mutableStateOf(st?.calSal?.let { fmt(it).replace(',', '.') } ?: "") }

    fun run(block: suspend () -> Result<Unit>, ok: String? = null) = scope.launch {
        block().onSuccess { ok?.let { snackbar.showSnackbar(it) } }.onFailure { snackbar.showSnackbar(it.message ?: "Error") }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Calibración")
        if (!connected) Text("Conectate a la cámara para ver y cambiar la calibración.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumRow("Caudal de la válvula (ml/s)", agua, { agua = it }, "Guardar", idle) {
            agua.toDoubleOrNull()?.let { v -> run({ app.session.calibrate("agua", v) }, "Calibración guardada") }
        }
        NumRow("Pasos del sinfín por gramo", sal, { sal = it }, "Guardar", idle) {
            sal.toDoubleOrNull()?.let { v -> run({ app.session.calibrate("sal", v) }, "Calibración guardada") }
        }
        Text(
            "Se guarda en la cámara y se registra con cada ensayo. Los valores reales se miden en banco (ADR 0006, 0009).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        SectionTitle("Conexión")
        Text("Enlace: ${app.session.link.name}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { app.session.disconnect() }, enabled = connected) { Text("Desconectar") }

        if (mode == LinkMode.Simulada) {
            HorizontalDivider()
            SectionTitle("Simulador de cámara")
            SpeedChips(speed) { app.fakeLink.speed.value = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.fakeLink.injectAbort("tanque_vacio") }, enabled = live.running) { Text("Tanque vacío") }
                OutlinedButton(onClick = { app.fakeLink.simulatePowerCut() }, enabled = connected) { Text("Cortar energía") }
            }
            Text(
                "Simula fallas para probar los avisos. Al cortar la energía la cámara se desconecta; al reconectar informa el reinicio.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        SectionTitle("Avanzado")
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Diagnóstico") }
        Text("UTECorroe 0.1 · PIC1 2026 · Grupo 17", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NumRow(label: String, value: String, onChange: (String) -> Unit, action: String, enabled: Boolean, onAction: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value, { onChange(it.filter { c -> c.isDigit() || c == '.' }.take(8)) }, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onAction, enabled = enabled) { Text(action) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(app: App, onBack: () -> Unit, onBleTest: () -> Unit) {
    val console by app.session.console.collectAsStateWithLifecycle()
    val live by app.session.live.collectAsStateWithLifecycle()
    var raw by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(console.size) { if (console.isNotEmpty()) listState.scrollToItem(console.lastIndex) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Diagnóstico") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enlace: ${app.session.link.name} · ${live.link::class.simpleName}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(raw, { raw = it }, label = { Text("Orden (ej. ayuda)") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { scope.launch { app.session.sendRaw(raw.trim()); raw = "" } }, enabled = raw.isNotBlank() && live.link is LinkState.Connected) { Text("Enviar") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                items(console) { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
            OutlinedButton(onClick = onBleTest, modifier = Modifier.fillMaxWidth()) { Text("Prueba BLE real (LED, ADR 0005)") }
        }
    }
}
