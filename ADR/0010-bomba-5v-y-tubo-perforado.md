# 0010. Bomba de 5 V y tubo perforado en lugar de boquilla aspersora

- **Estado:** Aceptado
- **Fecha:** 2026-10-25
- **Decisores:** Grupo 17

## Contexto

[0002](0002-niebla-por-aspersion-a-presion.md) eligió aspersión a presión
con una bomba presurizadora de 220 V AC. Un colega prestó una bomba
sumergible de 5 V tipo Arduino. Esa bomba entrega caudal pero muy poca
presión (altura de bombeo de menos de un metro), insuficiente para atomizar
en una boquilla.

## Decisión

Usar la bomba de 5 V prestada, accionada por relé, y reemplazar la boquilla
por un tubo cerrado en su extremo y perforado con agujas. La solución sale
en chorros finos y goteo sobre las probetas en vez de niebla.

Se mantiene el principio de [0002](0002-niebla-por-aspersion-a-presion.md):
la solución fluye sobre probetas estáticas, sin inmersión.

## Consecuencias

- Se elimina el circuito de 220 V del esquema de Entrega 2, lo que quita el
  mayor riesgo de seguridad eléctrica del proyecto.
- La exposición ya no es niebla uniforme sino riego por goteo. Es una
  desviación adicional de ASTM B117 que hay que declarar en el informe,
  junto con la ausencia de calefacción.
- Las agujas se tapan con sal cristalizada: prever enjuague con agua limpia
  al terminar un ensayo o un paso de limpieza en la receta
  ([0006](0006-operaciones-atomicas-y-recetas-json.md)).
- La bomba sumergible va dentro del tanque de solución; el reed de vacío
  sigue siendo el enclavamiento que evita que gire en seco.
- Caudal conocido y constante: habilita medir el volumen consumido por
  tiempo de bomba ([0012](0012-medicion-de-volumen-por-tiempo-y-reeds.md)).
