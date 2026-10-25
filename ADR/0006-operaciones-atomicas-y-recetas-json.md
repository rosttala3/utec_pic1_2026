# 0006. Operaciones atómicas en firmware, recetas JSON y protocolo NUS

- **Estado:** Aceptado. Queda abierta la calibración de dosificación de sal.
- **Fecha:** 2026-10-25
- **Decisores:** Grupo 17

## Contexto

El firmware actual implementa un ensayo fijo: `inicio(segundos)` enciende la
bomba por el tiempo indicado y la apaga. Queremos ensayos personalizables
(ciclos de aspersión y reposo, dosificación intermedia, avisos) sin
reescribir firmware para cada variante, controlados desde la app Android por
BLE y manteniendo UART como exige la propuesta.

Un ensayo puede durar días, más de lo que un teléfono permanece conectado, así
que no se puede depender de una conexión BLE permanente.

## Decisión

1. **El firmware expone operaciones atómicas**, cada una con sus propias
   validaciones de seguridad, independientes de quién las invoque:
   `agua(ml)` o `agua(lleno)` abre la electroválvula ([0007](0007-electrovalvula-12v-para-paso-de-agua.md)),
   `sal(g)` o `sal(conc)` mueve el sinfín ([0009](0009-motor-paso-a-paso-para-dosificar-sal.md)),
   `mezclar(ms)`, `bomba(segundos)` ([0010](0010-bomba-5v-y-tubo-perforado.md)),
   `esperar(segundos)`, `aviso(ms)`. Los enclavamientos (tanque vacío apaga la
   bomba, tanque lleno cierra la válvula) viven en el firmware y una receta no
   los puede desactivar.
2. **La app arma una receta JSON** con los pasos y sus parámetros, la sube
   completa al ESP32, y **el firmware la ejecuta solo**. La receta se guarda
   en NVS y sobrevive a desconexiones BLE. La app solo observa y controla.
3. **Un único parser de líneas de texto atiende UART y BLE.** Cada transporte
   entrega líneas terminadas en `\n` y recibe las respuestas por el mismo
   canal. La lógica no sabe de dónde vienen.
4. **BLE usa Nordic UART Service (NUS)**: un puerto serie sobre dos
   características, con los UUID estándar:

   | Elemento | UUID | Dirección |
   |----------|------|-----------|
   | Servicio | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | |
   | RX | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | app escribe, firmware recibe |
   | TX | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | firmware notifica, app recibe |

   Se usan los UUID estándar para poder depurar con nRF Connect o nRF Toolbox
   sin la app. El servicio se publicita para que la app filtre el escaneo.

Se descartó un GATT propio con una característica por función: obliga a
mantener dos formatos (texto por UART, binario o estructurado por BLE) y no se
puede probar desde el monitor serie.

## Protocolo de líneas

Texto UTF-8, una orden por línea, minúsculas. La respuesta a cada orden es
una línea `ok` o `err,<codigo>,<mensaje>`. Los eventos espontáneos del
firmware llevan prefijo para que la app los distinga de las respuestas.

| Orden | Efecto |
|-------|--------|
| `estado` | Estado completo en varias líneas legibles (como hoy) |
| `estado,json` | El mismo estado en una línea JSON para la app |
| `inicio` | Ejecuta la receta guardada |
| `inicio(segundos)` | Atajo heredado: bomba encendida esa duración ([0003](0003-comando-inicio-solo-con-duracion.md)) |
| `detener` | Aborta receta o paso en curso y deja todo apagado |
| `nivel(agua,ml)` / `nivel(agua,lleno)` / `nivel(sal,g)` / `nivel(sal,conc,<%>)` | Dosificación manual, en unidades físicas o por concentración |
| `calibrar(agua,ml_por_s)` / `calibrar(sal,pasos_por_g)` | Fija la constante de calibración y la guarda en NVS |
| `receta,<bytes>` | Abre la carga de una receta de ese tamaño |
| `receta_dato,<fragmento>` | Fragmento del JSON, todo lo que sigue a la primera coma |
| `receta_fin,<crc32 hex>` | Cierra la carga, valida CRC y esquema, guarda en NVS |
| `receta_ver` | Devuelve la receta guardada, en fragmentos |
| `historial,<n>` | Reenvía las muestras con secuencia mayor que `n` y termina con `ev,sync_fin`. Es de solo lectura |
| `ayuda` | Lista de órdenes |

Eventos del firmware hacia la app:

| Línea | Significado |
|-------|-------------|
| `t,<seq>,<seg>,<temp>,<cond>,<vol>` | Muestra periódica; `seg` son segundos desde el inicio del ensayo ([0004](0004-historial-de-ensayos-en-la-app-movil.md)) |
| `ev,sync_fin` | Terminó el envío de las muestras pendientes |
| `ev,paso,<i>,<n>,<op>` | Empezó el paso `i` de `n` |
| `ev,dosis,<op>,<pedido>,<real>,<motivo>` | Terminó una dosificación. `real` es lo dosificado de verdad; `motivo` es `ok` o `lleno` si el reed cortó el agua antes |
| `ev,fin` | La receta terminó |
| `ev,abort,<motivo>` | Abortó (tanque vacío, `detener`, fallo) |

Cada línea debe caber en la MTU negociada menos 3 bytes. La app pide MTU
alta al conectar y parte los fragmentos de `receta_dato` en consecuencia.
Por BLE se envían con escritura con respuesta; por UART no hace falta.

## Esquema de la receta

JSON minificado, versionado, máximo 4 KB. Los ciclos permiten ensayos de
días sin una receta gigante; se admite un nivel de anidado.

```json
{
  "v": 1,
  "nombre": "Acero vs aluminio, 48 h",
  "muestreo_s": 60,
  "pasos": [
    {"op": "agua", "hasta": "lleno"},
    {"op": "sal", "conc": 5},
    {"op": "mezclar", "ms": 3000},
    {"op": "ciclo", "n": 48, "pasos": [
      {"op": "bomba", "s": 600},
      {"op": "esperar", "s": 3000}
    ]},
    {"op": "aviso", "ms": 3000}
  ]
}
```

El firmware rechaza con `err` una receta con `v` desconocida, operación
desconocida, parámetro fuera de rango o más de 4 KB. Los límites por
operación son un máximo por dosis (ml y g). No se valida el total de la
receta contra la capacidad del tanque: un ensayo puede consumir solución y
volver a dosificar, y un exceso lo frena el reed de lleno.

## Concentración objetivo

El parámetro natural de un ensayo es la concentración (ASTM B117 pide 5 % de
NaCl en masa), no los gramos y ml. La receta la expresa con dos pasos:

- `{"op":"agua","hasta":"lleno"}` llena hasta el reed de lleno. El volumen
  queda exactamente igual al medido entre vacío y lleno
  ([0012](0012-medicion-de-volumen-por-tiempo-y-reeds.md)), sin depender del
  caudal de la válvula.
- `{"op":"sal","conc":5}` calcula los gramos a partir de ese volumen y los
  dosifica:

```
g_sal = conc / (100 - conc) × ml_agua      (agua ≈ 1 g/ml)
5 %  →  5,26 g de sal por cada 100 ml de agua
```

El cálculo está en el firmware y no en la app porque la receta corre sin
teléfono y porque solo el firmware sabe cuánta agua entró de verdad. Los
pasos `agua` con `ml` y `sal` con `g` siguen existiendo como control manual.

- **Se asume tanque vacío al empezar.** Si ya hay solución, el firmware no
  sabe cuánta sal contiene y el resultado queda mal. En ese caso el sensor
  de conductividad es la verificación. Si un paso `sal` con `conc` se ejecuta
  sin que antes haya habido un `agua` hasta lleno, el firmware lo rechaza
  con `err`.
- **No hay lazo cerrado con el sensor.** Dosificar sal hasta que la
  conductividad marque la concentración se descarta por ahora: los
  electrodos son caseros y aproximados
  ([0011](0011-electrodos-del-sensor-de-conductividad.md)), la lectura
  depende de la temperatura y la sal tarda en disolverse, por lo que el lazo
  puede pasarse y no se puede quitar sal. El sensor solo verifica y enciende
  el LED rojo si la lectura no coincide. Se reevalúa cuando los electrodos
  estén calibrados.
- **Sal:** NaCl común sin yodo ni antiaglomerante, porque esos aditivos
  alteran la solución y la conductividad.
- **En la app** el usuario ingresa la concentración, la app muestra los
  gramos y ml resultantes y el historial guarda la concentración (por
  ejemplo "5 % NaCl") como dato del ensayo.

## Unidades y calibración

Las recetas hablan en unidades físicas (ml, g, segundos de riego). El
firmware convierte con constantes de calibración guardadas en NVS: caudal de
la válvula en ml por segundo y pasos del motor por gramo de sal
([0009](0009-motor-paso-a-paso-para-dosificar-sal.md)). Así una receta sirve
en cualquier cámara y solo se recalibra el equipo.

- Las constantes se cambian con `calibrar(...)` y salen en `estado,json`.
- La app registra la calibración vigente junto a cada ensayo, para que los
  resultados sean comparables.
- Los pasos del motor son enteros, y el caudal de la válvula por gravedad baja
  al vaciarse el depósito, por lo que lo pedido es aproximado. El firmware
  informa lo real con `ev,dosis`.
- Si el reed de lleno corta el agua antes de llegar a los ml pedidos, el paso
  termina bien con motivo `lleno`; no es un error. Con pasos manuales en g y
  ml la sal ya dosificada puede dejar la solución más concentrada de lo
  previsto, por eso la app advierte en el editor cuando los valores no dan
  la concentración buscada. Con `conc` el problema no existe porque la sal se
  calcula sobre el volumen real. El sensor de conductividad
  ([0011](0011-electrodos-del-sensor-de-conductividad.md)) verifica en ambos
  casos.

## Consecuencias

- Firmware: separar en módulos (sensores, actuadores, enclavamientos,
  intérprete de receta, parser de líneas, transportes UART y BLE). Agregar
  `ArduinoJson` y NVS. Un paso en curso es una máquina de estados no
  bloqueante, como la dosificación actual.
- Si el firmware se reinicia en mitad de un ensayo (corte de energía), **no
  retoma solo**: queda en reposo y reporta `ev,abort,reinicio` al conectar.
  Retomar una bomba o una válvula sin nadie presente se considera inseguro.
- App: editor de recetas, carga por BLE con verificación de CRC, pantalla de
  progreso a partir de los eventos, historial
  ([0004](0004-historial-de-ensayos-en-la-app-movil.md)).
- `BleConstants.kt` y `TestBluetooth.ino` usan hoy UUID propios; al integrar
  pasan a los de NUS y el sketch de prueba se retira
  ([0005](0005-firmware-ble-separado-como-prueba.md)).
- Es un cambio de sintaxis respecto de la propuesta (`inicio,30,24`), ya
  aceptado en [0003](0003-comando-inicio-solo-con-duracion.md).

## Telemetría sin teléfono

Toda muestra se guarda en el ESP32 con un número de secuencia creciente, haya
o no un cliente conectado. El historial es de solo lectura: cualquier teléfono
pide lo que le falta y nada se borra por haberse enviado.

- **Flujo:** al conectar, la app envía `historial,<n>` con la última
  secuencia que ya tiene. El firmware manda las faltantes y cierra con
  `ev,sync_fin`. La app deduplica por secuencia, así que repetir el pedido no
  daña nada.
- **Cambio de teléfono:** si el teléfono muere a mitad de un ensayo de varios
  días, otro se conecta y sincroniza todo desde cero. No hay confirmaciones
  que dejen datos en un estado intermedio.
- **Almacenamiento:** un archivo LittleFS por ensayo, con registros de tamaño
  fijo de unos 20 bytes. Las muestras se acumulan en RAM y se escriben juntas
  cada 10 muestras (10 minutos a una por minuto). Ante un corte de energía se
  pierden como máximo esas 10 minutos de muestras, pérdida aceptada. Escribir
  por lotes también limita el desgaste de la flash.
- **Capacidad:** el ensayo dura como máximo 7 días, unas 10.000 muestras y
  unos 200 KB, que entran en la partición por defecto sin necesidad de
  buffer circular.
- **Retención:** se conservan los dos últimos ensayos. Al iniciar un tercero
  se borra el más viejo, para no perder datos sin sincronizar si alguien
  inicia un ensayo por UART con el teléfono ausente.
- **Tiempo:** el ESP32 no conserva hora tras un corte, por eso cada muestra
  lleva segundos desde el inicio del ensayo. La app calcula la hora
  absoluta anclándose en la muestra más reciente recibida en vivo. La
  secuencia y el identificador del ensayo se guardan en NVS, y las muestras
  anteriores a un reinicio quedan con la hora aproximada.
- **Una conexión BLE a la vez:** las respuestas a órdenes salen por la misma
  característica TX y no identifican a quién van, y dos teléfonos podrían
  enviar `detener` e `inicio` a la vez. El firmware rechaza una segunda
  conexión mientras haya una activa. Es compatible con cambiar de teléfono,
  pero no con usar dos a la vez.

## Puntos abiertos

- **Valores de calibración.** Hay que medir en banco el volumen entre vacío y
  lleno, los ml por segundo de la válvula y los pasos por gramo del sinfín, ver
  [0009](0009-motor-paso-a-paso-para-dosificar-sal.md).
- **Una receta o varias.** Hoy solo una guardada. Si hace falta una
  biblioteca, la guarda la app y sube la que corresponde.
