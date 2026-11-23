# Simulador de cámara y extensiones al protocolo

La app corre hoy contra `FakeChamberLink`, que habla el protocolo del
[ADR 0006](../ADR/0006-operaciones-atomicas-y-recetas-json.md) sin hardware.
Cuando exista el transporte BLE/NUS real, implementa la misma interfaz
(`ChamberLink`) y no cambia nada más.

## Qué simula

- Órdenes: `estado`, `estado,json`, `ensayo,<conc %>,<duración s>,<muestreo s>`,
  `detener`, `nivel(...)`, `calibrar(...)`, `historial,<n>`, `ayuda`.
  Ya no hay recetas JSON: la app solo pide concentración, duración (obligatoria)
  y muestreo, y la cámara hace sola la preparación.
- Fases del ensayo: `llenado` → `dosis` → `ajuste` → `ensayo`. En `ajuste` la
  cámara mezcla, mide la conductividad (compensada por temperatura) y dosifica
  correcciones hasta quedar a ±0,15 % del objetivo; si se pasa aborta con
  `conc_alta`, y si no converge en 6 correcciones con `no_converge`.
  La duración pedida empieza a contar al entrar en la fase `ensayo`.
- Eventos: `t,...`, `ev,paso,<i>,<n>,<fase>,<seg>`, `ev,dosis` (motivos `ok`,
  `lleno`, `ajuste`), `ev,fin`, `ev,abort`, `ev,sync_fin`.
- Como el ESP32, el ensayo sigue aunque no haya teléfono; guarda las muestras
  de los dos últimos ensayos y las reenvía con `historial,<n>`.
- Velocidad de tiempo ajustable (1×, 60×, 600×, 3600×) para ver ensayos de días en segundos.
- Fallas: tanque vacío y corte de energía (reporta `ev,abort,reinicio` al reconectar).
- Un ensayo rechazado devuelve `err,<código>,<mensaje>` (`ocupado`, `rango`).

## Extensiones que el ADR 0006 no define (a acordar con el firmware)

El ADR deja abiertos estos detalles; la app y el simulador asumen lo siguiente
y habría que registrarlo en un ADR nuevo si se aceptan.

1. **`estado,json`** devuelve una línea JSON:
   `{"estado":"corriendo|reposo","ensayo":3,"seg":120,"seq":9,"temp":25.1,"cond":4.9,"vol":900,"tanque":"vacio|medio|lleno","cap_ml":1200,"cal":{"agua_ml_s":20,"sal_pasos_g":100}}`.
   Con un ensayo en curso agrega `fase`, `fase_inicio` (segundo en que empezó), `conc_obj` y `duracion_s`,
   para que la app retome el progreso al reconectar.
2. **`ev,ensayo,<id>,<seq_inicial>`**: marca el comienzo de un ensayo y precede a
   sus muestras dentro de `historial,<n>`. Sin esto la app no puede asignar
   muestras a ensayos cuando sincroniza dos a la vez.
3. **`seq` global** y creciente entre ensayos (ADR 0004 lo guarda en NVS): `historial,<n>`
   pide todo lo posterior a `n` sin importar el ensayo.
4. **Campo `cond` de la muestra** = concentración estimada en % de NaCl, ya
   calibrada en el firmware (ADR 0011).
5. Tras `historial,<n>` no hay `ok`: la respuesta termina con `ev,sync_fin`.
6. **`ev,paso` lleva el segundo de inicio de la fase** como sexto campo.
7. **`ensayo,...` reemplaza a `receta*`/`inicio`** (cambia el ADR 0006; falta el ADR 0013).

## Firmware real (`Firmware/CamaraCorrosion`)

El firmware ya implementa este protocolo (UART y BLE NUS). Diferencias respecto del simulador:

- `calibrar(cap|agua|sal,<valor>)` y `calibrar(cond,<% NaCl>)` / `calibrar(cond,limpiar)`: la curva de conductividad se arma
  punto a punto con soluciones conocidas. Sin ella, `ensayo` responde `err,sin_calibrar`.
- `estado,json` suma `cal.cond_puntos` y `cal.cond_lectura` (lectura compensada, para calibrar).
- Abortos extra: `llenado` (la válvula no llegó al reed de lleno a tiempo), `sensor` (sin lectura de conductividad)
  y `sensores` (los dos reeds activos).
- Las líneas se parten en varias notificaciones si no entran en la MTU; la app debe reensamblar por `\n`.
- `ev,abort,reinicio` sale con el primer comando que llega por BLE tras el arranque, no al conectar.
