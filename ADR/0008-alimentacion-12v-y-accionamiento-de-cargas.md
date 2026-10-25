# 0008. Alimentación única de 12 V y accionamiento de cargas

- **Estado:** Propuesto
- **Fecha:** 2026-10-25
- **Decisores:** Grupo 17

## Contexto

Con los cambios de hardware el sistema tiene cargas a tres tensiones:

| Carga | Tensión | Corriente aprox. | Accionamiento |
|-------|---------|------------------|---------------|
| Electroválvula | 12 V | 0,3 a 0,5 A | Relé 1 |
| Motor paso a paso (sinfín de sal) | 12 V (NEMA 17) o 5 V (28BYJ-48) | hasta 1,2 A / 0,3 A | Driver propio, sin relé |
| Motor DC mezclador | 5 V o 12 V según el motor | 0,2 a 0,5 A | Relé 2 |
| Bomba de agua 5 V | 5 V | 0,1 a 0,3 A | Relé 3 |
| Módulos relé (bobinas) | 5 V | 70 mA cada uno | GPIO |
| ESP32 + sensores | 5 V (LDO a 3,3 V) | 0,25 A pico con BLE | — |

La PCB está en diseño y todavía no define de dónde salen los 12 V.

## Decisión (borrador)

Una única entrada de 12 V DC por jack, desde un adaptador de pared de 12 V
y al menos 3 A. En la placa, un regulador reductor (*buck*) tipo LM2596 o
MP1584 baja a 5 V para ESP32, relés, bomba y mezclador. El ESP32 se alimenta
por su pin VIN/5V y su regulador interno da los 3,3 V de los sensores.

Alternativas descartadas:

- **Dos fuentes (5 V USB + 12 V aparte):** dos cables, masas que hay que unir
  igual, más fácil de conectar mal.
- **Elevador (boost) de 5 V a 12 V:** la válvula y un NEMA 17 superan lo que
  un boost chico entrega con margen.

## Consecuencias

- Masa común obligatoria entre 12 V, 5 V y 3,3 V.
- Diodo de rueda libre en válvula y motor mezclador. El driver del paso a
  paso ya protege su motor.
- El paso a paso se maneja con driver (A4988/DRV8825 para NEMA 17, ULN2003
  para 28BYJ-48), con pin ENABLE para cortar la corriente de retención
  cuando no dosifica, así no calienta.
- Fusible en la entrada de 12 V y condensador de desacople junto al buck.
- Los tres relés quedan asignados: válvula, mezclador y bomba. No queda
  relé libre; el buzzer y los LED van directo a GPIO.
