# 0003. El comando `inicio` recibe solo la duración, sin temperatura objetivo

- **Estado:** Aceptado
- **Fecha:** 2026-10 (validado con el docente)
- **Decisores:** Grupo 17, docente de la unidad

## Contexto

La propuesta docente especifica el comando `inicio,temperatura,tiempo`
(ejemplo `inicio,30,24`) para fijar temperatura objetivo y duración en horas.
Por las delimitaciones del proyecto la cámara no tiene elemento calefactor:
mide la temperatura interna con un DS18B20 pero no puede controlarla.

Fijar una temperatura objetivo que el sistema no puede alcanzar sería
engañoso para el usuario y para el registro histórico.

## Decisión

El comando de inicio recibe únicamente la duración del ensayo. La forma
actual del firmware es `inicio(segundos)`; en el informe de Entrega 2 figura
como `inicio(duración)`. La temperatura se registra como variable observada,
no como consigna.

El cambio fue consultado y aceptado por el docente.

## Consecuencias

- El firmware valida solo la duración (mayor a 0, tope de seguridad de 7 días).
- Si en una etapa futura se agrega calefacción, se escribe un ADR nuevo que
  reemplace a este y se extiende el comando con la consigna de temperatura.
- Queda pendiente definir la unidad definitiva (segundos vs horas) y si la
  sintaxis final usa paréntesis o comas; esto afecta a la app móvil y al
  parser, ver [0006](0006-operaciones-atomicas-y-recetas-json.md).
