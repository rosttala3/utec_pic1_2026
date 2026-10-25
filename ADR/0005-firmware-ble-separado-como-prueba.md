# 0005. El firmware BLE se mantuvo separado del de la cámara como prueba de concepto

- **Estado:** Aceptado
- **Fecha:** 2026-10-13
- **Decisores:** Grupo 17

## Contexto

Antes de comprometer la arquitectura de comunicación había que verificar que
un teléfono Android puede descubrir el ESP32, conectarse por BLE, escribir y
recibir notificaciones, y que la librería Kable funciona con el core de
Arduino para ESP32.

## Decisión

Se escribió `Firmware/TestBluetooth/TestBluetooth.ino` como sketch
independiente que expone un servicio GATT con una característica de un byte
(parpadeo del LED), y `AndroidApp/` como cliente mínimo. No se tocó
`Firmware/CamaraCorrosion/CamaraCorrosion.ino`.

## Consecuencias

- La prueba validó el stack completo: permisos de Android 12+, escaneo por
  UUID de servicio, lectura, escritura con respuesta y notificaciones.
- Los UUID propios y el nombre `PIC1-ESP32` de la prueba se reemplazan por los
  de Nordic UART Service al integrar, ver
  [0006](0006-operaciones-atomicas-y-recetas-json.md).
- La integración del BLE en el firmware de la cámara está pendiente y se
  define en [0006](0006-operaciones-atomicas-y-recetas-json.md). El sketch de
  prueba se conserva como referencia hasta que la integración funcione.
