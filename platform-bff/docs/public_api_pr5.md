# Public HTTP API — PR5 (estado parcial tras auditoría)

Este documento describe únicamente la superficie implementada actualmente. La fuente canónica sigue
siendo `estomatologia-platform-docs/main` y `platform-bff/docs/system_contract.md`.

Todas las escrituras de negocio requieren JWT autenticado con estado normal y el header
`Idempotency-Key`. Cada request encola exactamente un Command owner allowlisted y responde:

```text
202 Accepted
operationId
status
pollPath
submittedAt
```

La señal interna de reutilización de idempotencia no se expone como campo HTTP adicional.

Cuando el BFF agrega al wire datos server-side (por ejemplo ids de agregado o timestamps
operacionales), el fingerprint de idempotencia se calcula sobre la intención HTTP estable; el
payload enriquecido se conserva únicamente como payload owner. Por tanto, repetir la misma
`Idempotency-Key` con la misma intención reutiliza el mismo `operationId` aunque el servidor pudiera
generar nuevos valores efímeros durante el segundo intento.

Errores inmediatos de validación, autorización y projection-readiness usan
`application/problem+json` con `type`, `title`, `status`, `code`, `detail` y `traceId`.

## Mailbox

```text
GET  /api/v1/mailbox/messages
GET  /api/v1/mailbox/summary
POST /api/v1/mailbox/visits
```

Las lecturas quedan scoped a la Cuenta autenticada. Los mensajes tombstone/expirados por Event no
son visibles. El conteo de nuevos usa `createdAt > lastVisitedAt`.

`POST /visits` recibe únicamente `systemCode`. `accountId` se deriva del JWT y `visitedAt` se genera
con el reloj del BFF para cumplir el requisito owner de una fecha confiable. La escritura mapea a
`RegisterInboxVisit` sin inventar aggregate metadata para esta operación. El timestamp server-side
queda fuera del fingerprint de idempotencia.

## Wash

```text
POST /api/v1/wash/appointments
POST /api/v1/wash/appointments/{appointmentId}/cancel
POST /api/v1/wash/exceptional-authorizations
POST /api/v1/wash/exceptional-authorizations/{authorizationId}/cancel

POST /api/v1/wash/executions/arrivals
POST /api/v1/wash/executions/{washExecutionId}/entry-decision
POST /api/v1/wash/executions/{washExecutionId}/reassignment
POST /api/v1/wash/executions/{washExecutionId}/exit
POST /api/v1/wash/executions/{washExecutionId}/complete
POST /api/v1/wash/executions/{washExecutionId}/clinic-cancel
POST /api/v1/wash/operational-resources/disable
POST /api/v1/wash/operational-resources/restore

GET  /api/v1/wash/supervision/pending-reassignments
```

### ScheduleAppointment

El body público incluye `regulationAccepted=true`. El BFF lo transporta al owner y Appointments lo
vuelve a validar; el BFF no sustituye esa regla de dominio.

`appointmentId` se genera server-side y `studentAccountId` se deriva del JWT. Ambos viajan al owner
sin formar parte mutable del fingerprint de la intención HTTP.

### RegisterWashArrival

El cliente envía únicamente `appointmentId`. `washExecutionId` y `arrivedAt` se derivan en el borde;
el cliente no controla el timestamp operacional de llegada.

### Supervisor

Las operaciones de alumno usan la Cuenta autenticada y ownership local cuando puede probarse desde
proyecciones. Las operaciones de supervisor requieren `SUPERVISOR_LAVADO` y acceso activo a
`LAVADO_ULTRASONICO`; los owners vuelven a validar su autorización funcional.

La vista de pendientes se compone localmente con:

```text
student display name / enrollment
appointment type / instrumentCount / pieceType
course-section reference
appointment time slot
appointment status
wash-execution status
active resource assignment
submitted exit materials, cuando existen
```

Los materiales enviados por `WashExitSubmitted` se materializan con fencing propio, independiente
del `executionVersion` actual, para tolerar entrega tardía del Event.

Para una ejecución `PENDING_REASSIGNMENT`:

```text
qrUsageContext = NONE
```

`qrUsageContext` es siempre un dato compuesto; no se persiste ni crea otra entidad/identificador QR.

## Availability

```text
GET /api/v1/wash/appointments/availability
```

queda habilitado por el contrato owner-derived V1 de Appointments. Recibe `appointmentType`,
`instrumentCount`, `pieceType` y `courseSectionId`; `studentAccountId` y `serviceDate` se derivan en
servidor. Un snapshot fresco se responde con `200`. Si falta o venció, el BFF registra un refresh
durable y responde `503` para que el cliente reintente; no mantiene abierta la request esperando
RabbitMQ.

No se calcula `capacity - demand`, no se reutilizan Queries de recovery y no se hace HTTP/gRPC al
owner. La respuesta omite metadata interna de mensajería y expone únicamente la decisión y los slots
derivados por Appointments.

## Gaps que permanecen fuera de esta corrección

El commit directo que introdujo esta superficie no completa todavía todo el Definition of Done de
PR5:

- `/api/v1/admin/**` continúa sin superficie HTTP cerrada/implementada en este módulo;
- falta cerrar una representación OpenAPI completa que coincida exactamente con lo expuesto;
- las proyecciones sin recovery owner explícito continúan arrancando `BOOTSTRAPPING`; esta auditoría
  no inventa una transición a `READY` porque hacerlo sin snapshot/bootstrap autoritativo podría
  presentar estado parcial como confiable.

Estos puntos requieren continuación explícita de PR5 o una resolución documental previa; no se
resuelven ocultando lógica owner dentro del BFF.
