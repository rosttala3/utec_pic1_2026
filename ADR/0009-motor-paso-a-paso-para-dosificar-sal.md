# 0009. Motor paso a paso para dosificar sal

- **Estado:** Aceptado
- **Fecha:** 2026-10-25
- **Decisores:** Grupo 17

## Contexto

La sal se dosifica con un tornillo sinfín (en inglés *auger* o *screw
feeder*). Con un servo (`PIN_SERVO_SAL`, GPIO 19) solo se controla un ángulo
de apertura, no cuántas vueltas dio el tornillo, así que la cantidad
entregada dependía del tiempo y era poco repetible.

## Decisión

Accionar el sinfín con un motor paso a paso. La cantidad de sal se expresa
en pasos (o vueltas) del motor, que se calibran una vez en gramos por
vuelta con una balanza. No lleva relé: el driver del motor se comanda
directo desde GPIO.

## Consecuencias

- Firmware: `nivel(sal, g)` y el paso `sal` de una receta piden gramos y el
  firmware los convierte a pasos con una constante de calibración guardada en
  NVS ([0006](0006-operaciones-atomicas-y-recetas-json.md)). También acepta
  una concentración objetivo, que el firmware convierte a gramos. Hacen falta pines STEP, DIR y ENABLE (o cuatro pines si
  es ULN2003) y una rutina no bloqueante de generación de pasos, o la
  librería `AccelStepper`.
- Hay que medir y documentar la calibración gramos/vuelta. La sal húmeda se
  apelmaza y cambia la entrega; conviene repetir la calibración.
- Alimentación y driver según [0008](0008-alimentacion-12v-y-accionamiento-de-cargas.md).
