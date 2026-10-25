# 0011. Material y excitación de los electrodos de conductividad

- **Estado:** Aceptado el material (grafito). La excitación pulsada y la calibración se validan en banco.
- **Fecha:** 2026-10-25
- **Decisores:** Grupo 17

## Contexto

El sensor de conductividad todavía no está construido. Se propuso usar
alambre de acero de cerco rural porque "no se oxida". Ese alambre es acero
galvanizado: una capa de zinc sobre acero al carbono. El zinc no se oxida en
el aire, pero en solución de NaCl al 5 % y con corriente continua se disuelve
en semanas (es justamente un ánodo de sacrificio), contamina la solución con
iones de zinc y cambia la conductividad que se intenta medir. Debajo queda
acero al carbono, que se oxida.

Además, cualquier electrodo alimentado con corriente continua se polariza
y electroliza la solución: la lectura deriva con el tiempo aunque la
concentración no cambie.

## Decisión (borrador)

1. **Material: grafito, minas de portaminas de 1,0 mm.** El grafito es
   inerte en salmuera y no aporta iones. Cada electrodo se arma con una o
   varias minas en paralelo, a distancia fija en un soporte impreso en 3D,
   para que la geometría no cambie. Son baratas y se reemplazan entre series
   de ensayos.

   Limitaciones que se aceptan al elegirlas:
   - **Frágiles y con poca superficie.** Una mina sola da una resistencia
     alta y una lectura más ruidosa; por eso conviene 2 o 3 minas por
     electrodo, y elegir la resistencia del divisor según la lectura real.
   - **Aglutinante de arcilla.** Las minas de portaminas son grafito con
     arcilla, y en salmuera pueden aflojarse con las semanas. Se inspeccionan
     entre ensayos. Si se degradan, el respaldo es una barra de carbón de
     pila de zinc-carbono, más robusta, o acero inoxidable 316.
   - **Conexión eléctrica.** El estaño no moja el grafito. Se conecta con
     pinza o terminal por presión, y la unión mina-cable queda fuera de la
     solución o sellada con epoxi o silicona, porque el cobre se corroe en
     salmuera y alteraría la medición.
2. **Excitación pulsada y alternada en vez de continua:** los electrodos van
   en un divisor resistivo alimentado desde dos GPIO. Para medir, el
   firmware pone un GPIO en alto y el otro en bajo, lee el ADC a los pocos
   microsegundos, invierte la polaridad y lee de nuevo; entre muestras deja
   ambos GPIO en bajo. Así la corriente neta es cero, no hay electrólisis
   apreciable y los electrodos duran.
3. **Calibración:** tabla ADC a concentración con al menos tres soluciones
   conocidas (0 %, 2,5 %, 5 % en masa) a temperatura anotada. La
   conductividad sube cerca de 2 % por °C, así que la lectura del DS18B20
   se usa para compensar o al menos se registra junto a cada muestra.

Alternativa: un módulo TDS comercial (Gravity TDS o similar) ya trae
excitación alterna y sonda; cuesta poco pero sale del alcance "sistema
experimental de electrodos" que pide la propuesta. Queda como respaldo si
los electrodos caseros no dan lecturas estables.

## Consecuencias

- El firmware deja de hacer `analogRead` sobre un divisor fijo
  (`PIN_COND`, GPIO 34) y pasa a una rutina de medición con dos GPIO de
  excitación más el ADC. Los umbrales `UMBRAL_SAL_ON/OFF` se recalibran.
- Hay que reservar dos GPIO de salida para la excitación.
- Los electrodos se enjuagan con agua limpia al terminar cada ensayo.
