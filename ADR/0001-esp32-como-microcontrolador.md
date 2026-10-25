# 0001. ESP32 como microcontrolador en vez de Arduino Nano

- **Estado:** Aceptado
- **Fecha:** 2026-09-27 (documentado en Entrega 2)
- **Decisores:** Grupo 17

## Contexto

La propuesta docente pide un microcontrolador "basado en Arduino" (Nano, Uno u
otra plataforma compatible) programado desde Arduino IDE, con interacción por
UART. El equipo ya contaba con un ESP32 DevKit V1 de 30 pines.

## Decisión

Usar un ESP32 DevKit V1 programado con el core de Arduino para ESP32. Cumple el
requisito de compatibilidad Arduino y agrega Wi-Fi y Bluetooth nativos, más
RAM y flash, sin costo adicional porque ya se tenía.

Se descartó el Arduino Nano/Uno por no tener radio, lo que impediría una app
móvil sin hardware extra, y por su menor memoria para almacenar historial.

## Consecuencias

- Habilita la app Android por BLE ([0005](0005-firmware-ble-separado-como-prueba.md),
  [0006](0006-operaciones-atomicas-y-recetas-json.md)).
- El ESP32 trabaja a 3,3 V: relés, servos y buzzer se alimentan de 5 V y se
  comandan desde los GPIO. Los módulos relé deben aceptar nivel lógico 3,3 V.
- Hay pines con función de arranque (GPIO 0, 2, 12, 15). GPIO 12 (LED verde)
  no debe quedar en alto al arrancar; con el LED a GND por 220 Ω está bien.
  GPIO 2 y 15 (reed switches con pull-up) no afectan el arranque normal.
- El ADC del ESP32 es no lineal en los extremos; la lectura de conductividad
  (GPIO 34, solo entrada) se usa como valor relativo calibrado, no absoluto.
- Se usan las librerías `OneWire`, `DallasTemperature` y `ESP32Servo`
  (la `Servo` estándar de Arduino no funciona en ESP32).
