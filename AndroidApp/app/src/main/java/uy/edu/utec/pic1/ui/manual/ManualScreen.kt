package uy.edu.utec.pic1.ui.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.edu.utec.pic1.App
import uy.edu.utec.pic1.link.LinkState
import uy.edu.utec.pic1.ui.SectionTitle
import uy.edu.utec.pic1.ui.StatTile
import uy.edu.utec.pic1.ui.fmt

/** Operaciones atómicas: accionar cada parte de la cámara por separado (probar cableado, calibrar, vaciar, limpiar). */
@Composable
fun ManualScreen(app: App, snackbar: SnackbarHostState) {
    val live by app.session.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val connected = live.link is LinkState.Connected
    val enabled = connected && !live.running
    val st = live.status
    val act = st?.actuadores ?: emptyMap()

    var pasos by remember { mutableStateOf("4096") }
    var ml by remember { mutableStateOf("100") }
    var g by remember { mutableStateOf("10") }
    var conc by remember { mutableStateOf("5") }
    var hz by remember { mutableStateOf("1000") }

    // Los relés se apagan solos (tiempo, reed): se consulta el estado mientras la pantalla está abierta.
    LaunchedEffect(connected) {
        while (connected) { app.session.refreshStatus(); delay(1_500) }
    }

    fun order(line: String) = scope.launch {
        app.session.manual(line).onFailure { snackbar.showSnackbar(it.message ?: "Error") }
    }
    fun toggle(name: String, on: Boolean) = order("actuar($name,${if (on) "on" else "off"})")

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            !connected -> Text("Conectate a la cámara para accionar sus partes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            live.running -> Text("Hay un ensayo en curso: las operaciones manuales están bloqueadas.", color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Nivel", when { st == null -> "—"; st.reedLleno && st.reedVacio -> "ERROR"; st.reedLleno -> "lleno"; st.reedVacio -> "vacío"; else -> "medio" }, Modifier.weight(1f))
            StatTile("Volumen", st?.let { "${fmt(it.vol, 0)} ml" } ?: "—", Modifier.weight(1f))
            StatTile("Temp.", st?.let { "${fmt(it.temp)} °C" } ?: "—", Modifier.weight(1f))
        }

        Button(
            onClick = { order("detener") }, enabled = enabled && act.values.any { it },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth(),
        ) { Text("Parar todo") }

        HorizontalDivider()
        SectionTitle("Relés")
        SwitchRow("Electroválvula", "Entra agua. Se cierra sola al llegar al reed de lleno o a los 60 s.", act["valvula"] == true, enabled) { toggle("valvula", it) }
        SwitchRow("Mezclador", "Motor de la mezcla. Se apaga solo a los 60 s.", act["mezclador"] == true, enabled) { toggle("mezclador", it) }
        SwitchRow("Bomba de aspersión", "No arranca con el tanque vacío. Se apaga sola a los 60 s.", act["bomba"] == true, enabled) { toggle("bomba", it) }

        HorizontalDivider()
        SectionTitle("Sinfín de sal")
        NumRow("Pasos", pasos, { pasos = it }, enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { order("actuar(sinfin,${pasos.ifBlank { "0" }})") }, enabled = enabled) { Text("Adelante") }
            OutlinedButton(onClick = { order("actuar(sinfin,-${pasos.ifBlank { "0" }})") }, enabled = enabled) { Text("Reversa") }
            OutlinedButton(onClick = { order("actuar(sinfin,parar)") }, enabled = connected) { Text("Parar") }
            if (act["sinfin"] == true) Text("girando…", color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Útil para destrabar o medir cuántos pasos entrega por gramo (500 pasos/s; 4096 ≈ una vuelta del eje). Máximo 20000 por orden.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        SectionTitle("Indicadores")
        LedRow("LED verde", "led_verde", act, st?.forzados.orEmpty(), enabled) { order("actuar(led_verde,${if (it) "on" else "auto"})") }
        LedRow("LED rojo", "led_rojo", act, st?.forzados.orEmpty(), enabled) { order("actuar(led_rojo,${if (it) "on" else "auto"})") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumRow("Buzzer (Hz)", hz, { hz = it }, enabled, Modifier.weight(1f))
            OutlinedButton(onClick = { order("actuar(buzzer,${hz.ifBlank { "0" }},300)") }, enabled = enabled) { Text("Sonar") }
        }

        HorizontalDivider()
        SectionTitle("Dosificación")
        OutlinedButton(onClick = { order("nivel(agua,lleno)") }, enabled = enabled) { Text("Llenar tanque") }
        DoseRow("Agua (ml)", ml, { ml = it }, enabled) { order("nivel(agua,$ml)") }
        DoseRow("Sal (g)", g, { g = it }, enabled) { order("nivel(sal,$g)") }
        DoseRow("Sal por concentración (%)", conc, { conc = it }, enabled) { order("nivel(sal,conc,$conc)") }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

/** El interruptor indica si la app fuerza el LED; en automático el LED puede estar encendido sin que el interruptor lo esté. */
@Composable
private fun LedRow(title: String, key: String, act: Map<String, Boolean>, forzados: Set<String>, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val forzado = key in forzados
    val ahora = if (act[key] == true) "encendido" else "apagado"
    val hint = if (forzado) "Forzado encendido. Al apagar el interruptor vuelve al modo automático." else "Automático: ahora $ahora. Activalo para forzarlo encendido."
    SwitchRow(title, hint, forzado, enabled, onChange)
}

@Composable
private fun NumRow(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value, { onChange(it.filter { c -> c.isDigit() || c == '.' }.take(8)) }, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

@Composable
private fun DoseRow(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, onAction: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NumRow(label, value, onChange, enabled, Modifier.weight(1f))
        OutlinedButton(onClick = onAction, enabled = enabled) { Text("Dosificar") }
    }
}
