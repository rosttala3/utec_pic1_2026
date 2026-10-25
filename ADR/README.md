# Registro de decisiones de arquitectura (ADR)

Acá se documentan las decisiones técnicas importantes del proyecto, el contexto
en que se tomaron y sus consecuencias. Un ADR (*Architecture Decision Record*)
es un archivo corto, inmutable una vez aceptado: si una decisión cambia, se
escribe un ADR nuevo que reemplaza al anterior.

## Cómo agregar una decisión

1. Copiar `template.md` a `NNNN-titulo-corto.md` con el siguiente número libre.
2. Completar contexto, decisión y consecuencias. Una página alcanza.
3. Estado inicial `Propuesto`. Pasa a `Aceptado` cuando el equipo lo acuerda.
4. Agregar la fila al índice de abajo.

Estados: `Propuesto` · `Aceptado` · `Reemplazado por NNNN` · `Rechazado`.

## Índice

| Nº | Título | Estado |
|----|--------|--------|
| [0001](0001-esp32-como-microcontrolador.md) | ESP32 como microcontrolador en vez de Arduino Nano | Aceptado |
| [0002](0002-niebla-por-aspersion-a-presion.md) | Generación de niebla por aspersión a presión | Aceptado, ajustado por 0010 |
| [0003](0003-comando-inicio-solo-con-duracion.md) | El comando `inicio` recibe solo la duración, sin temperatura objetivo | Aceptado |
| [0004](0004-historial-de-ensayos-en-la-app-movil.md) | El historial de ensayos se guarda en la app móvil | Aceptado |
| [0005](0005-firmware-ble-separado-como-prueba.md) | El firmware BLE se mantuvo separado del de la cámara como prueba de concepto | Aceptado |
| [0006](0006-operaciones-atomicas-y-recetas-json.md) | Operaciones atómicas en firmware, recetas JSON y protocolo NUS | Aceptado |
| [0007](0007-electrovalvula-12v-para-paso-de-agua.md) | Electroválvula de 12 V para el paso de agua | Aceptado |
| [0008](0008-alimentacion-12v-y-accionamiento-de-cargas.md) | Alimentación única de 12 V y accionamiento de cargas | Propuesto |
| [0009](0009-motor-paso-a-paso-para-dosificar-sal.md) | Motor paso a paso para dosificar sal | Aceptado |
| [0010](0010-bomba-5v-y-tubo-perforado.md) | Bomba de 5 V y tubo perforado en lugar de boquilla aspersora | Aceptado |
| [0011](0011-electrodos-del-sensor-de-conductividad.md) | Material y excitación de los electrodos de conductividad | Aceptado (material), validar en banco |
| [0012](0012-medicion-de-volumen-por-tiempo-y-reeds.md) | Medición de volumen por tiempo de bomba, con reeds como enclavamiento | Propuesto |
