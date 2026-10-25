# 0002. Generación de niebla por aspersión a presión

- **Estado:** Aceptado. La bomba y la boquilla cambiaron en [0010](0010-bomba-5v-y-tubo-perforado.md); el principio de aspersión sobre probetas estáticas se mantiene.
- **Fecha:** 2026-09-27 (documentado en Entrega 2, tabla 1)
- **Decisores:** Grupo 17

## Contexto

Hay que generar un ambiente corrosivo sobre hasta cuatro probetas dentro de
una cámara de 30 × 20 × 25 cm como máximo. La norma de referencia ASTM B117
pide niebla salina (solución NaCl al 5 %) que se deposite sobre las probetas,
inclinadas entre 15° y 30°, sin chorro directo ni inmersión.

## Decisión

Presurizar la solución con una bomba y forzarla por una boquilla aspersora
para formar niebla dentro de la cámara (solución 1 de la matriz ponderada,
puntaje 4,30).

Alternativas descartadas:

- **Nebulización ultrasónica** (2,85): los discos piezoeléctricos económicos se
  degradan rápido con la salinidad.
- **Inmersión cíclica** (2,90): incumple ASTM B117 y agrega mecanismos móviles.

## Consecuencias

- Hace falta una bomba que levante presión suficiente para atomizar. El
  esquema de Entrega 2 mostraba una bomba presurizadora de 220 V AC comandada
  por relé; se descartó por seguridad y falta de disponibilidad, ver
  [0010](0010-bomba-5v-y-tubo-perforado.md).
- La cámara necesita separación entre zona húmeda y electrónica, y drenaje o
  recirculación de la solución.
- El firmware trata la bomba como un actuador on/off durante todo el ensayo
  (`PIN_RELE_BOMBA`), y lo apaga si el tanque queda vacío.
