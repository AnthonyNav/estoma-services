# Platform BFF — Appointment Availability Gate Resolution

**Estado:** resuelto de forma coordinada con el owner; ambos cambios son interdependientes

**Ruta HTTP:** `GET /api/v1/wash/appointments/availability`

## Por qué el gate puede abrirse

El gate original prohibía que el BFF inventara una Query o reconstruyera el matching. Appointments
ahora publica el contrato canónico consumible por el BFF en:

```text
appointments-service/docs/asyncapi.yaml
appointments-service/docs/system_contract.md
```

Ese contrato define `GetAppointmentAvailability` v1 sobre `platform.queries` con routing key
`appointments`, y `GetAppointmentAvailabilityResult` v1 sobre `platform.results` con routing key
`bff`. Appointments conserva ownership de elegibilidad, matching, capacidad, puntos y autorización
excepcional.

La implementación owner coordinada debe fusionarse desde
`appointments/feat/bff-availability-contract`. Sus blockers V1, en orden determinista, son:

```text
ACTIVE_APPOINTMENT_EXISTS
STUDENT_BLOCKED
DAILY_COMPLETED_LIMIT
EXCEPTIONAL_AUTHORIZATION_REQUIRED
ACADEMIC_ENROLLMENT_INVALID
STUDENT_ACCESS_DENIED
```

Un alumno elegible sin slots conserva `canSchedule = true` con `availableTimeSlots = []`; consultar
disponibilidad no reserva capacidad.

La prohibición histórica de "inventar GetAppointmentAvailability" queda satisfecha porque la Query
ya no es una inferencia local: nombre, versión, actor, payload, routing, Result y freshness están
publicados por el owner. La prohibición de duplicar reglas sigue vigente.

## Ejecución BFF

```text
GET con contexto exacto
  ├─ snapshot fresco → 200 con DTO HTTP
  └─ miss/stale → refresh durable + 503
                       └─ relay confirmado publica Query
                          └─ Result confiable materializa snapshot
                             └─ siguiente GET fresco → 200
```

No existe HTTP/gRPC entre servicios ni espera síncrona por RabbitMQ. El refresh tiene single-flight
por contexto y fencing por `query_message_id`, `correlation_id` y eco exacto de `requestContext`.
Results desconocidos, malformados, de otro actor/producer o fuera del refresh vigente no mutan el
read model.

La frescura owner es inclusiva:

```text
snapshotGeneratedAt <= now <= freshUntil
freshUntil = min(snapshotGeneratedAt + 30s, bookingDeadlineAt más cercano retornado)
```

El BFF nunca amplía esa ventana y filtra defensivamente slots cuyo deadline ya pasó.

## Dependencia de integración

El cambio owner que publica y atiende Query/Result V1 debe desplegarse junto con, o antes que, este
adapter. Ninguno de los dos PR debe considerarse una entrega funcional independiente: desplegar sólo
el BFF deja misses en `503`; desplegar sólo Appointments no expone la ruta al cliente.
