# Firmware de la cámara (ESP32)

La app pide un ensayo con **una sola orden** y la cámara hace el resto:

```
ensayo,<conc %>,<duracion s>,<muestreo s>      ej.  ensayo,5,86400,60
```

Fases: **llenado** (válvula hasta el reed de lleno) → **dosis** (90 % de la sal calculada con el sinfín) →
**ajuste** (mezcla, mide la conductividad y agrega dosis chicas hasta quedar a ±0,15 % del objetivo) →
**ensayo** (bomba encendida la duración pedida). Las muestras se guardan en LittleFS aunque no haya teléfono.
Protocolo completo: [`docs/protocolo-simulador.md`](../../docs/protocolo-simulador.md) y ADR 0006.

## Órdenes (UART 115200 o BLE Nordic UART)

| Orden | Efecto |
|---|---|
| `ensayo,<conc>,<dur s>,<muestreo s>` | Inicia el ensayo automático |
| `detener` | Aborta y deja todo apagado |
| `estado` / `estado,json` | Estado legible / en JSON (con fase, objetivo y duración si hay ensayo) |
| `nivel(agua,<ml>)`, `nivel(agua,lleno)`, `nivel(sal,<g>)`, `nivel(sal,conc,<%>)` | Dosificación manual (sin ensayo) |
| `actuar(valvula\|mezclador\|bomba,on\|off)`, `actuar(sinfin,<pasos>\|parar)`, `actuar(led_verde\|led_rojo,on\|off\|auto)`, `actuar(buzzer,<hz>,<ms>)` | Operaciones atómicas (sin ensayo): un relé se apaga solo a los 60 s, la válvula al llegar el reed de lleno, la bomba no arranca en seco; pasos negativos = reversa; `detener` apaga todo |
| `historial,<n>` | Reenvía las muestras con secuencia > n y termina con `ev,sync_fin` |
| `calibrar(agua,<ml/s>)`, `calibrar(sal,<pasos/g>)`, `calibrar(cap,<ml>)` | Constantes de calibración (en NVS) |
| `calibrar(cond,<% NaCl>)`, `calibrar(cond,limpiar)` | Punto de la curva de conductividad / borrar la curva |
| `ayuda` | Lista de órdenes |

Errores: `err,<codigo>,<mensaje>` con `ocupado`, `rango`, `sintaxis`, `sin_calibrar`, `sensores`, `calibracion`, `comando`, `lleno`, `tanque_vacio`. Evento `ev,manual_fin,<actuador>,<lleno|vacio|tiempo>` cuando un relé manual se apaga solo.
Abortos: `tanque_vacio`, `llenado`, `conc_alta`, `no_converge`, `sensor`, `detener`, `reinicio`.

## Qué falta: solo calibrar

Todo lo de abajo se mide en banco y se guarda con `calibrar(...)`; no hay que tocar el código. **Sin la curva de
conductividad el firmware rechaza `ensayo` con `err,sin_calibrar`.**

1. **Volumen** — llenar entre el reed de vacío y el de lleno y medir con jarra: `calibrar(cap,<ml>)`.
2. **Caudal de la válvula** — `nivel(agua,500)` con el depósito a altura normal, medir lo que sale: `calibrar(agua,<ml/s>)`.
   Se puede afinar mirando cuánto tarda en llegar al reed de lleno.
3. **Sinfín** — `nivel(sal,10)`, pesar la sal entregada y repetir: `calibrar(sal,<pasos/g>)`. Repetir si la sal se humedece.
4. **Conductividad** (ADR 0011) — con los electrodos sumergidos en soluciones de NaCl conocidas, a temperatura estable:
   `calibrar(cond,0)`, `calibrar(cond,2.5)`, `calibrar(cond,5)` (al menos dos puntos; con tres o más, mejor).
   Cada llamada registra la lectura actual, compensada a 25 °C con el DS18B20. La curva se interpola entre puntos y debe
   crecer con la concentración; si no, responde `err,calibracion`. `estado,json` muestra `cond_puntos` y `cond_lectura`.

## Cosas a confirmar con el hardware

- **Pines provisorios** del motor paso a paso: `PIN_MOTOR_IN1..IN4` (D19, D23, D25, D26: 28BYJ-48 con ULN2003), y los de excitación de los electrodos
  `PIN_COND_A` (D32) y `PIN_COND_B_RESIST` (D33); `PIN_COND_B` (D34) es la lectura del ADC. Todo está en `Config.h`.
- **Polaridades:** `RELE_ON`, `REED_ACTIVO`.
- **Sensor de 3 cables:** `electrodo A` (D32) y `electrodo B resist` (D33) son salidas de excitación; `electrodo B` (D34)
  es la toma directa del 2.º electrodo y va al ADC. La resistencia está entre `B resist` y `B`. Si se arma distinto,
  cambia la conversión en `leerMetricaCruda()` (Sensores.h). La curva calibrada absorbe la no linealidad.
- Velocidad del sinfín (`US_POR_PASO`, 2 ms por medio paso; si el motor pierde pasos o vibra, subirlo) y tiempo de mezcla antes de leer (`ASENTAR_MS`, 7 min) según el comportamiento real.

## Compilar

Arduino IDE o `arduino-cli`, placa **ESP32 Dev Module**, core `esp32` 3.x. Librerías: `OneWire` y `DallasTemperature`
(BLE, LittleFS y Preferences vienen con el core). Ya no usa `ESP32Servo`. Verificado hasta hoy solo con una pasada de
sintaxis del compilador xtensa; **todavía no se probó en la placa**.
