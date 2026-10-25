# 0004. El historial de ensayos se guarda en la app móvil

- **Estado:** Aceptado
- **Fecha:** 2026-10-25
- **Decisores:** Grupo 17

## Contexto

La propuesta pide que el sistema "almacene y envíe datos periódicos mediante
UART cada minuto" con el formato `Tiempo,Temperatura,Conductividad`, y que
exista un registro histórico de los ensayos. El ESP32 tiene flash limitada y
no tiene reloj de tiempo real; la app Android ya existe como cliente BLE.

## Decisión

La fuente de verdad del historial es una base de datos local en la app
Android (Room sobre SQLite). El firmware emite la telemetría periódica por
UART y por BLE; la app la recibe, le asigna fecha y hora reales y la persiste
asociada a un ensayo.

El ESP32 guarda las muestras de los dos últimos ensayos en LittleFS, de solo
lectura, mientras no hay teléfono conectado; cualquier teléfono puede
sincronizarlas, incluso uno distinto del que inició el ensayo. El mecanismo de sincronización está en
[0006](0006-operaciones-atomicas-y-recetas-json.md).

Alternativas consideradas:

- **Solo en el ESP32 (LittleFS o NVS) como fuente de verdad:** sin reloj real
  las marcas de tiempo serían relativas al arranque y consultar el historial
  requeriría volcarlo. Se usa solo como búfer, no como fuente principal.
- **Backend en la nube:** fuera de alcance para PIC1 y requiere Wi-Fi en el
  laboratorio.

## Consecuencias

- La app necesita una capa de persistencia (Room), entidades `Ensayo` y
  `Muestra`, y una pantalla de historial con exportación a CSV.
- Si el teléfono se desconecta durante un ensayo no se pierden muestras: el
  firmware las conserva y la app las sincroniza al reconectar, o desde otro teléfono. Solo se
  pierden hasta 10 minutos de muestras si se corta la energía del ESP32.
- La clave de cada muestra en Room es (ensayo, secuencia), lo que permite
  deduplicar reenvíos y detectar huecos.
- La hora absoluta de cada muestra la calcula la app a partir de los segundos
  desde el inicio del ensayo.
- El requisito de UART se sigue cumpliendo: la misma telemetría sale por
  serial para quien conecte una PC.
