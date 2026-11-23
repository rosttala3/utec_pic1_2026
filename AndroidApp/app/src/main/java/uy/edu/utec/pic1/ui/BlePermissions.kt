package uy.edu.utec.pic1.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

private val bluetoothPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun Context.hasBluetoothPermissions() =
    bluetoothPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

private fun Context.isBluetoothEnabled() = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/** Devuelve una función que pide permisos y enciende el Bluetooth si hace falta, y recién después llama a [onReady]. */
@Composable
fun rememberBluetoothGate(onReady: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (context.isBluetoothEnabled()) onReady() }
    fun enableThenReady() { if (context.isBluetoothEnabled()) onReady() else enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { ok -> ok }) enableThenReady() }
    return { if (context.hasBluetoothPermissions()) enableThenReady() else perms.launch(bluetoothPermissions) }
}
