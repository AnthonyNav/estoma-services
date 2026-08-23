# Wash Execution — AppointmentTimeSlot calendar context resolution

**Estado:** RESOLVED_FOR_PR2_PR3  
**Fuente canónica:** `AnthonyNav/estomatologia-platform-docs` / `wash-execution-service/calendar-context-resolution.md`

## Problema

`AdministrativeClosure.scope = SERVICE` pertenece a un `calendarId`. Evaluar cierres solo por intervalo temporal puede mezclar un cierre de otro calendario con el `AppointmentTimeSlot` que se está ejecutando.

## Resolución V1

La proyección local de `AppointmentTimeSlot` usada por `wash-execution-service` incluye:

```text
appointmentTimeSlotId
calendarId
serviceDate
startsAt
endsAt
timezone
arrivalToleranceUntil
status
sourceVersion
```

`calendarId` es contexto derivado por `appointments-service` desde el schedule/calendario que originó el slot; no se convierte en ownership de Appointments ni de Execution.

Las Queries de recovery de Appointments que reconstruyen esta proyección deben incluir el mismo `calendarId`.

## Aplicabilidad de AdministrativeClosure

Un cierre afecta una asignación únicamente cuando:

```text
closure.status = SCHEDULED
AND intervals overlap
AND closure.calendarId = appointmentTimeSlot.calendarId
AND (
  closure.scope = SERVICE
  OR closure.scope = CABIN && closure.cabinId = candidate.cabinId
  OR closure.scope = TANK  && closure.tankId  = candidate.tankId
)
```

Un `AppointmentTimeSlot` sin `calendarId` no es evidencia suficiente para evaluar un cierre relevante. En ese caso el Command falla cerrado con contexto de Appointments no confiable/incompleto.

## Impacto de implementación

### PR2

- migración aditiva agrega `appointment_time_slot_projection.calendar_id` nullable para no inventar backfill;
- Application usa `calendarId` al evaluar cierres;
- filas antiguas sin contexto permanecen fail-closed cuando un cierre relevante exige correlación.

### PR3

Antes de marcar Appointments `READY`, tanto Events incrementales como recovery Queries deben poblar `calendarId` para los slots reconstruidos.

No se permite inferir `calendarId` por fecha, solapamiento o por "el calendario activo actual".
