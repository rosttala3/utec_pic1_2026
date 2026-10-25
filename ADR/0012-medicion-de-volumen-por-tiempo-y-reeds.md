# 0012. Medición de volumen por tiempo de bomba, con reeds como enclavamiento

- **Estado:** Propuesto
- **Fecha:** 2026-10-25
- **Decisores:** Grupo 17

## Contexto

Los reed switches funcionan pero solo indican dos puntos: tanque lleno (para
cerrar la electroválvula) y tanque vacío (para no encender la bomba en seco).
No dan el nivel intermedio ni cuánta agua se consumió. Se propuso medir
una vez el volumen entre lleno y vacío y trabajar siempre con "cargas
completas" para contar el agua usada.

## Decisión (borrador)

Combinar dos métodos:

1. **Estimación continua por tiempo de bomba.** La bomba de 5 V tiene caudal
   aproximadamente constante. Se calibra una vez (segundos de bomba para
   llenar una jarra graduada) y el firmware acumula `segundos de bomba ×
   caudal` como volumen consumido del ensayo. Lo mismo para la válvula:
   segundos de apertura × caudal por gravedad, calibrado con el depósito de
   agua a altura normal.
2. **Reeds como límites y como recalibración.** Lleno y vacío siguen siendo
   enclavamientos duros en firmware. Además, cada vez que el tanque pasa de
   lleno a vacío, el volumen acumulado por tiempo se compara con el volumen
   lleno-vacío medido y sirve para corregir el caudal calibrado.

El esquema de "cargas completas" queda como caso particular: una receta que
siempre llena hasta el reed y riega hasta el reed de vacío.

Alternativas consideradas:

- **Sensor ultrasónico (HC-SR04) sobre el tanque:** da nivel continuo, pero
  la niebla salina y la condensación lo degradan y el eco en un tanque chico
  es ruidoso.
- **Sensor de nivel capacitivo o de presión diferencial:** mejor precisión,
  más costo y calibración; no justificado en PIC1.

## Consecuencias

- El volumen entre vacío y lleno es la base del cálculo de sal por
  concentración ([0006](0006-operaciones-atomicas-y-recetas-json.md)), por lo
  que es la primera calibración a medir en banco.
- El firmware no valida el total de una receta contra la capacidad del tanque.
  La capacidad medida (lleno a vacío) la usa la app para advertir en el editor
  de recetas, y el reed de lleno frena cualquier exceso.
- La telemetría periódica suma un campo de volumen consumido estimado.
- El firmware guarda los caudales calibrados como constantes configurables
  (idealmente en NVS, modificables por comando).
- El caudal de la bomba baja si las agujas se tapan
  ([0010](0010-bomba-5v-y-tubo-perforado.md)); la recalibración con los
  reeds detecta esa deriva.
- El depósito de agua limpia no tiene sensor: la app o el usuario llevan
  la cuenta a partir del volumen dosificado.
