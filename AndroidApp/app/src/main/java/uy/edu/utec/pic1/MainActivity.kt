package uy.edu.utec.pic1

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                BlinkScreen()
            }
        }
    }
}

private val bluetoothPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun Context.hasBluetoothPermissions() = bluetoothPermissions.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private fun Context.isBluetoothEnabled(): Boolean =
    getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

@Composable
fun BlinkScreen(viewModel: BlinkViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (context.isBluetoothEnabled()) viewModel.connect()
    }

    // Antes de conectar: permisos -> Bluetooth encendido -> conectar
    fun connectWhenReady() {
        if (context.isBluetoothEnabled()) {
            viewModel.connect()
        } else {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) connectWhenReady()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("PIC1 – Control ESP32", style = MaterialTheme.typography.headlineSmall)

            StatusRow(state.status)

            when (state.status) {
                ConnectionStatus.Connected,
                ConnectionStatus.Scanning,
                ConnectionStatus.Connecting -> OutlinedButton(
                    onClick = viewModel::disconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Desconectar") }

                else -> Button(
                    onClick = {
                        if (context.hasBluetoothPermissions()) connectWhenReady()
                        else requestPermissions.launch(bluetoothPermissions)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Conectar a ${BleConstants.DEVICE_NAME}") }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Parpadeo del LED", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (state.blinking) {
                                true -> "Encendido"
                                false -> "Apagado"
                                null -> "Sin conexión"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = state.blinking == true,
                        onCheckedChange = viewModel::setBlinking,
                        enabled = state.status == ConnectionStatus.Connected && state.blinking != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(status: ConnectionStatus) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (status == ConnectionStatus.Scanning || status == ConnectionStatus.Connecting) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
        }
        Text(
            text = when (status) {
                ConnectionStatus.Disconnected -> "Desconectado"
                ConnectionStatus.Scanning -> "Buscando ${BleConstants.DEVICE_NAME}…"
                ConnectionStatus.Connecting -> "Conectando…"
                ConnectionStatus.Connected -> "Conectado"
                is ConnectionStatus.Error -> "Error: ${status.message}"
            },
            color = if (status is ConnectionStatus.Error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
