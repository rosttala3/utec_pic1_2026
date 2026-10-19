package uy.edu.utec.pic1

import com.juul.kable.characteristicOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Deben coincidir con Firmware/TestBluetooth/TestBluetooth.ino
@OptIn(ExperimentalUuidApi::class)
object BleConstants {
    const val DEVICE_NAME = "PIC1-ESP32"

    val SERVICE_UUID = Uuid.parse("821aef38-c20d-4e30-92a7-90cdfa14b52b")

    /** 1 byte: 0 = parpadeo apagado, 1 = parpadeo encendido. Lectura / escritura / notificación. */
    val BLINK_CHARACTERISTIC = characteristicOf(
        service = SERVICE_UUID,
        characteristic = Uuid.parse("2cf676d5-bece-4323-b1ba-5502e0bd2870"),
    )
}
