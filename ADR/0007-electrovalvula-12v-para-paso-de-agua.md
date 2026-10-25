# 0007. Electroválvula de 12 V para el paso de agua

- **Estado:** Aceptado
- **Fecha:** 2026-10-25
- **Decisores:** Grupo 17

## Contexto

El diseño de Entrega 2 usaba un servo (`PIN_SERVO_AGUA`, GPIO 18) para abrir
una compuerta entre el depósito de agua y el de solución. Una compuerta
accionada por servo no sella bien y gotea; la válvula solenoide es el
componente estándar para abrir y cerrar un paso de líquido.

## Decisión

Usar una electroválvula solenoide de 12 V DC normalmente cerrada, accionada
por uno de los tres módulos relé de 5 V. El servo de agua se elimina.

## Consecuencias

- Hace falta un riel de 12 V en la placa. Cómo alimentarlo se define en
  [0008](0008-alimentacion-12v-y-accionamiento-de-cargas.md).
- La válvula es una carga inductiva: va un diodo de rueda libre (1N4007)
  en antiparalelo con la bobina, del lado de la carga, para proteger los
  contactos del relé.
- La válvula es on/off, no regula caudal. La cantidad de agua dosificada se
  controla por tiempo de apertura y por el reed de tanque lleno, ver
  [0012](0012-medicion-de-volumen-por-tiempo-y-reeds.md).
- Firmware: `PIN_SERVO_AGUA` pasa a ser `PIN_RELE_VALVULA` (salida digital
  al relé); el estado `S_DOSIFICANDO` para agua abre el relé en vez de
  escribir el servo. La librería `ESP32Servo` deja de usarse si también se
  retira el servo de sal ([0009](0009-motor-paso-a-paso-para-dosificar-sal.md)).
