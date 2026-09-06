# Wash Execution Service — System Contract V1

**Ruta:** `wash-execution-service/docs/system_contract.md`
**Estado:** contrato local de implementación V1
**Logical Component ID:** `wash-execution`

## 1. Propósito

Este documento traduce la documentación canónica de plataforma a un contrato directamente implementable para `wash-execution-service`.

No sustituye la documentación canónica. Si existe drift, prevalece `AnthonyNav/estomatologia-platform-docs`, en particular:

- `01-domain/lavado-business.md`;
- `03-services/domains/lavado-ultrasonico/wash-execution-service/README.md`;
- `03-services/domains/lavado-ultrasonico/wash-execution-service/data-model.md`;
- `03-services/domains/lavado-ultrasonico/wash-execution-service/commands.md`;
- `03-services/domains/lavado-ultrasonico/wash-execution-service/events.md`;
- `03-services/domains/lavado-ultrasonico/wash-execution-service/interfaces.md`;
- `03-services/domains/lavado-ultrasonico/wash-execution-service/authorization-projection.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/wash-execution-integration.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/queries.md`.

Este contrato existe junto al código para fijar qué debe implementar el servicio y evitar reinterpretar la documentación canónica durante cada PR.

---

## 2. Ownership

`wash-execution-service` es propietario de:

```text
WashExecution
EntryValidation
ResourceAssignment
ResourceUnavailability
CleaningPeriod
```

También persiste infraestructura técnica local:

```text
Outbox
Consumer Inbox / processed messages
appointment_projection
appointment_time_slot_projection
cabin_projection
tank_projection
resource_priority_projection
administrative_closure_projection
role_projection
system_access_projection
projection_recovery_state
```

No es propietario de:

```text
Appointment
AppointmentTimeSlot
agenda reservable
cancelación automática de citas SCHEDULED por pérdida de capacidad
Cabin / Tank como catálogo administrativo
calendarios / horarios / cierres administrativos
identidad / roles / accesos
QR
comunicaciones
puntos
```

---

## 3. Identidad lógica y mensajería

Identidad contractual RabbitMQ:

```text
logicalComponentId = wash-execution
command routing key = wash-execution
command queue = commands.wash-execution
result queue cuando Execution es requester = results.wash-execution
```

`wash-execution-service` es el nombre del servicio/deployment, no la identidad lógica de routing.

No usar:

```text
commands.wash-execution-service
results.wash-execution-service
```

Flujo de Commands:

```text
Client
→ BFF
→ platform.commands
→ commands.wash-execution
→ wash-execution-service
→ Outbox
→ platform.results / platform.events
```

Reglas obligatorias:

- Outbox para mensajes durables derivados de una transacción local;
- Consumer Inbox por `messageId` para deduplicar delivery;
- `idempotencyKey` para intención lógica de Commands;
- `operationId` para seguimiento iniciado por cliente;
- `sourceVersion` / versión de agregado para monotonicidad de proyecciones;
- no asumir orden global de RabbitMQ;
- mensajes causales fuera de orden se difieren/reconcilian de forma durable;
- RabbitMQ no se trata como Event Store.

---

## 4. Ciclo de vida de `WashExecution`

Estados V1:

```text
PENDING_ENTRY
ENTRY_REJECTED
PENDING_REASSIGNMENT
IN_PROGRESS
EXIT_SUBMITTED
COMPLETED
CANCELLED
```

Transiciones:

```text
PENDING_ENTRY
├── ENTRY_REJECTED
├── IN_PROGRESS
└── PENDING_REASSIGNMENT

PENDING_REASSIGNMENT
├── IN_PROGRESS
└── CANCELLED

IN_PROGRESS
├── EXIT_SUBMITTED
└── PENDING_REASSIGNMENT

EXIT_SUBMITTED
└── COMPLETED
```

No existe transición directa:

```text
IN_PROGRESS → CANCELLED
```

Una ejecución iniciada que pierde su recurso pasa primero por `PENDING_REASSIGNMENT`; solo desde ahí puede terminar en `CANCELLED` por causa de la clínica.

Estados finales:

```text
ENTRY_REJECTED
COMPLETED
CANCELLED
```

---

## 5. Registro de llegada

`RegisterWashArrival` crea una ejecución únicamente cuando:

- la `Appointment` existe;
- pertenece al alumno esperado;
- está `SCHEDULED`;
- no existe otra `WashExecution` para la cita;
- existe el `AppointmentTimeSlot` correspondiente;
- la llegada cae dentro de la ventana permitida.

Ventana V1:

```text
arrivalAllowedFrom     = appointmentTimeSlot.startsAt - 15 minutos
arrivalToleranceUntil  = appointmentTimeSlot.startsAt + 10 minutos

válido si:
arrivalAllowedFrom <= arrivedAt <= arrivalToleranceUntil
```

`arrivalAllowedFrom` se deriva; no requiere campo de dominio propio.

Execution valida esta regla con proyecciones locales, no mediante llamada HTTP síncrona a Appointments.

---

## 6. Validación de entrada

V1 no modela checklist de EPP/materiales.

`EntryValidation` conserva:

```text
entryValidationId
washExecutionId
identityConfirmed
requirementsSatisfied
decision
rejectionReason?
decidedByAccountId
decidedAt
```

Reglas:

```text
AUTHORIZED
→ identityConfirmed = true
→ requirementsSatisfied = true
→ rejectionReason vacío

REJECTED
→ rejectionReason obligatorio
```

El método físico exacto mediante el cual el Supervisor valida identidad no forma parte del modelo V1 de Execution; el servicio conserva el resultado global.

---

## 7. Asignación y capacidad

### 7.1 Capacidad configurada

La capacidad configurable pertenece a `Tank`:

```text
Tank.configuredCapacity
```

Significado:

```text
máximo de citas/alumnos simultáneos del mismo AppointmentTimeSlot
que esa tina puede admitir
```

`Cabin` no tiene capacidad configurada propia.

Una capacidad de cabina, si una vista necesita mostrarla, es derivada:

```text
CabinCapacity = SUM(Tank.configuredCapacity)
```

sobre las tinas utilizables que correspondan al cálculo.

Cada Appointment/WashExecution consume una unidad, independientemente de `instrumentCount`.

### 7.2 Capacidad por bloque

La capacidad lógica usada para asignación está acotada por:

```text
Tank + AppointmentTimeSlot
```

Por lo tanto, para un target slot:

```text
activeAssignments(tank, slot) < tank.configuredCapacity
```

Una ejecución del bloque anterior que continúe `IN_PROGRESS` después de `slot.endsAt` no consume por ese solo hecho capacidad lógica del bloque siguiente.

Ejemplo:

```text
slot A = 10:00-11:00
slot B = 11:00-12:00

Execution de A sigue IN_PROGRESS a 11:08
→ no resta cupo a B
```

La demora física se resuelve operativamente por los Supervisores.

Restricciones explícitas como `ResourceUnavailability`, `AdministrativeClosure` o `CleaningPeriod` sí pueden afectar bloques posteriores según sus propios intervalos.

### 7.3 Compatibilidad fija

```text
NORMAL
1. Cabin NORMAL → preferida
2. Cabin JOURNEY → alternativa

JOURNEY
1. Cabin JOURNEY → preferida
2. Cabin NORMAL → alternativa

IMMUNOCOMPROMISED
1. Cabin IMMUNOCOMPROMISED → exclusiva
```

`NORMAL` y `JOURNEY` nunca usan capacidad `IMMUNOCOMPROMISED`.

### 7.4 Orden dentro de candidatos válidos

Primero completar una tina compatible parcialmente utilizada dentro del mismo bloque antes de abrir una vacía.

Desempate técnico/determinista, sin semántica de negocio:

```text
1. menor remainingCapacity > 0
2. cabinCode ASC
3. tankCode ASC
4. cabinId ASC
5. tankId ASC
```

La propuesta nunca reserva por sí sola. Debe revalidarse dentro de la transacción que confirma la asignación.

---

## 8. Autorización + asignación automática

`DecideWashEntry(AUTHORIZED)` ejecuta en la misma transacción local:

```text
1. crear EntryValidation AUTHORIZED
2. calcular candidato
3. revalidar candidato
4a. candidato válido:
    crear ResourceAssignment INITIAL
    WashExecution → IN_PROGRESS
    registrar startedAt
    Outbox:
      WashEntryAuthorized
      WashResourceAssigned
      WashExecutionStarted
4b. sin candidato válido:
    WashExecution → PENDING_REASSIGNMENT
    Outbox:
      WashEntryAuthorized
      WashExecutionPendingReassignment
```

No existe estado persistido intermedio `ENTRY_AUTHORIZED`.

La asignación inicial no requiere confirmación adicional del Supervisor.

---

## 9. `PENDING_REASSIGNMENT`

Cubre exactamente:

```text
A. entrada autorizada sin recurso inicial válido
B. ejecución IN_PROGRESS que pierde su recurso
```

Orden estable:

```text
authorizedAt ASC
washExecutionId ASC
```

`ResolvePendingReassignment` permite:

- aceptar sugerencia calculada;
- seleccionar manualmente una combinación válida.

Antes de persistir, revalidar:

- existencia y estado administrativo;
- pertenencia `Tank → Cabin`;
- compatibilidad;
- capacidad del target slot;
- indisponibilidad operativa;
- limpieza aplicable;
- cierre administrativo aplicable.

Si no existe alternativa, `CancelWashExecutionByClinic` puede operar únicamente desde `PENDING_REASSIGNMENT`.

---

## 10. Inhabilitación y rehabilitación

`DisableOperationalResource` puede afectar una cabina o una tina.

Causas V1:

```text
FAILURE
MANUAL_DISABLE
```

Una única indisponibilidad puede afectar varias ejecuciones si varias asignaciones utilizan el recurso.

La relación se conserva en:

```text
resource_unavailability_affected_executions
```

Cada ejecución `IN_PROGRESS` afectada pasa a `PENDING_REASSIGNMENT`.

La asignación previa no se elimina ni se libera inmediatamente; se conserva hasta resolver o cancelar.

Cualquier Supervisor autorizado puede rehabilitar un recurso. No existe jerarquía basada en quién lo inhabilitó.

Rehabilitar no garantiza disponibilidad automática.

---

## 11. Ejecución que supera el bloque

No existe timeout funcional ni comportamiento automático al terminar un `AppointmentTimeSlot`.

No hacer por timeout:

```text
CompleteWashExecution
CancelWashExecutionByClinic
release forzado
PENDING_REASSIGNMENT
overdue como regla de negocio
bloqueo del siguiente slot
```

La duración del bloque representa la reserva lógica de agenda. Cualquier demora física adicional se resuelve en sitio.

---

## 12. Salida y finalización

`SubmitWashExit` recibe:

```text
packageCount
greenPaperCassette8Count
greenPaperCassette10Count
witnessTapePortionCount
```

Reglas:

- enteros;
- cada valor `>= 0`;
- al menos uno `> 0`;
- una sola entrega del Alumno;
- solo desde `IN_PROGRESS`.

Resultado:

```text
IN_PROGRESS → EXIT_SUBMITTED
```

`CompleteWashExecution`:

- solo desde `EXIT_SUBMITTED`;
- permite al Supervisor confirmar o corregir los cuatro valores finales;
- libera la asignación vigente;
- cambia `WashExecution → COMPLETED`;
- registra `WashExecutionCompleted` en Outbox;
- crea `CleaningPeriod` cuando corresponde.

---

## 13. Limpieza `IMMUNOCOMPROMISED`

### 13.1 Creación

Cada `ResourceAssignment` `IMMUNOCOMPROMISED` realmente utilizada y posteriormente liberada genera como máximo un `CleaningPeriod` para la cabina usada.

Triggers V1:

```text
REASSIGNMENT
NORMAL_COMPLETION
CLINIC_CANCELLATION
```

No se crea limpieza si nunca hubo uso físico de una asignación.

Tiempo:

```text
startsAt       = ResourceAssignment.releasedAt
expectedEndsAt = startsAt + 3 horas
```

### 13.2 Finalización automática

V1 finaliza automáticamente:

```text
cuando now >= expectedEndsAt
y completedAt IS NULL
→ completedAt = expectedEndsAt
→ completedByAccountId = NULL
→ version++
→ Outbox: CleaningPeriodCompleted
```

La operación debe ser idempotente y segura con múltiples workers.

No existe en V1:

```text
finalización manual
finalización anticipada
actor humano requerido para terminar limpieza
```

El intervalo efectivo es:

```text
[startsAt, expectedEndsAt)
```

Una latencia técnica del worker o del evento `CleaningPeriodCompleted` no extiende el bloqueo de negocio más allá de `expectedEndsAt`.

---

## 14. Reserva preventiva `IMMUNOCOMPROMISED`

Esta regla pertenece a `appointments-service`, no a Execution.

Cuando se registra una cita `IMMUNOCOMPROMISED`, Appointments asume preventivamente que ocurrirá:

```text
usageWindow           = [slot.startsAt, slot.endsAt)
plannedCleaning       = [slot.endsAt, slot.endsAt + 3h)
plannedReservedWindow = [slot.startsAt, slot.endsAt + 3h)
```

Una nueva cita IMMUNO es válida solo si su propia `plannedReservedWindow` no solapa la de las citas IMMUNO activas relevantes.

La regla es simétrica y bloquea:

- horarios posteriores dentro de la limpieza prevista;
- horarios anteriores cuya propia limpieza prevista alcanzaría la cita existente.

Ejemplo para cita 14:00-15:00, con bloques de una hora:

```text
11:00-12:00 → conflicto
12:00-13:00 → conflicto
13:00-14:00 → conflicto
14:00-15:00 → cita existente
15:00-16:00 → conflicto
16:00-17:00 → conflicto
17:00-18:00 → conflicto
18:00-19:00 → potencialmente válido
```

La reserva preventiva no crea un `CleaningPeriod` en Execution.

Si la cita termina sin uso físico:

```text
CANCELLED
MISSED
ENTRY_REJECTED
```

Appointments elimina esa restricción y recalcula disponibilidad.

Cuando existe uso real, `CleaningPeriodStarted`/`Completed` representan la realidad física y permiten reconciliar el intervalo efectivo.

---

## 15. Proyecciones locales requeridas

### 15.1 `appointment_projection`

Owner: `appointments-service`.

Campos mínimos:

```text
appointmentId
studentAccountId
appointmentTimeSlotId
courseSectionId
appointmentType
instrumentCount
pieceType
appointmentStatus
entryAuthorizedAt?
sourceVersion
updatedAt
```

### 15.2 `appointment_time_slot_projection`

Owner: `appointments-service`.

Campos mínimos:

```text
appointmentTimeSlotId
serviceDate
startsAt
endsAt
timezone
arrivalToleranceUntil
status
sourceVersion
updatedAt
```

La llegada anticipada se deriva de `startsAt - 15 minutos`.

### 15.3 `cabin_projection`

Owner: `wash-administration-service`.

Campos mínimos:

```text
cabinId
code
name?
cabinType
status
sourceVersion
updatedAt
```

No contiene `configuredCapacity`.

### 15.4 `tank_projection`

```text
tankId
cabinId
code
name?
configuredCapacity
status
sourceVersion
updatedAt
```

### 15.5 Otras proyecciones de Administration

```text
resource_priority_projection
administrative_closure_projection
```

### 15.6 Authorization

```text
role_projection
system_access_projection
```

Las operaciones que dependen de una proyección obligatoria no deben asumir datos faltantes como válidos.

---

## 16. Recovery

### Appointments

Execution necesita recovery autoritativo mediante:

```text
GetAppointmentExecutionContext
ListAppointmentExecutionContextsPage
```

### Wash Administration

```text
GetWashAdministrationConfiguration
```

### Authorization

Seguir el mecanismo definido por el contrato canónico de `authorization-projection`.

Flujo de rebuild esperado:

```text
1. bindings/listeners activos
2. projection readiness = REBUILDING
3. obtener snapshot/páginas autoritativas
4. reconciliar por sourceVersion
5. aplicar Events concurrentes de forma monotónica
6. repetir reconciliación si es necesario
7. projection readiness = READY
```

Mientras la proyección requerida no sea confiable, fail closed para Commands dependientes.

---

## 17. Persistencia e invariantes

Tablas de dominio:

```text
wash_executions
entry_validations
resource_assignments
resource_unavailabilities
resource_unavailability_affected_executions
cleaning_periods
```

Constraints mínimos:

```text
UNIQUE wash_executions(appointment_id)
UNIQUE entry_validations(wash_execution_id)
UNIQUE parcial resource_assignments(wash_execution_id) WHERE released_at IS NULL
UNIQUE cleaning_periods(resource_assignment_id)
CHECK valores de salida >= 0 cuando presentes
CHECK exactamente uno de cabin_id/tank_id en resource_unavailabilities
```

`ResourceAssignment` debe conservar `appointment_time_slot_id` para proteger la capacidad por bloque.

La invariante:

```text
count(Tank + AppointmentTimeSlot) <= Tank.configuredCapacity
```

no se resuelve con un `COUNT` aislado; requiere locking/revalidación transaccional.

---

## 18. Concurrencia

Casos obligatorios de prueba:

```text
dos ejecuciones por último cupo del mismo Tank/slot
assign vs DisableOperationalResource
doble ResolvePendingReassignment
doble CompleteWashExecution
doble CancelWashExecutionByClinic
una inhabilitación que afecta varias ejecuciones
dos workers completando el mismo CleaningPeriod
```

Usar orden determinista de locks para minimizar deadlocks.

Estrategia aceptable:

```text
SELECT ... FOR UPDATE
```

o equivalente, siempre que proteja las invariantes.

---

## 19. Commands públicos V1

```text
RegisterWashArrival
DecideWashEntry
ResolvePendingReassignment
DisableOperationalResource
RestoreOperationalResource
SubmitWashExit
CompleteWashExecution
CancelWashExecutionByClinic
```

El wire owner canónico de `ResolvePendingReassignment` es:

```text
washExecutionId
selectedCabinId
selectedTankId
assignedByAccountId
assignedAt
expectedVersion
```

Durante la transición, el consumer conserva compatibilidad con la forma legacy ya publicada por
el BFF (`cabinId`, `tankId`, `resolvedByAccountId`, `resolvedAt`). Debe llegar una familia completa
y exclusiva; mezclar o enviar parcialmente aliases canónicos y legacy es wire inválido. Ambas
formas se normalizan al mismo Command interno antes de calcular el fingerprint, de modo que un
retry con igual `producer`, operación, `idempotencyKey` e intención conserva la misma respuesta y
no repite efectos. El BFF cambia a los nombres canónicos únicamente después de desplegar este
consumer compatible; la rama legacy no se retira mientras puedan quedar Commands antiguos en cola.

Operaciones internas:

```text
AssignResourcesAfterEntryAuthorization
ProposeResourceAssignment
CompleteExpiredCleaningPeriods
```

No exponer como Commands V1:

```text
ConfirmInitialResourceAssignment
StartWashExecution
ConfirmResourceReassignment
InterruptWashExecution
PauseTankUsageRestriction
CompleteCleaningPeriod manual
```

---

## 20. Events producidos

```text
WashArrivalRegistered
WashEntryAuthorized
WashEntryRejected
WashResourceAssigned
WashResourceReassigned
WashExecutionPendingReassignment
OperationalResourceDisabled
OperationalResourceRestored
WashExecutionStarted
WashExitSubmitted
WashExecutionCompleted
WashExecutionCancelled
CleaningPeriodStarted
CleaningPeriodCompleted
```

No crear Events redundantes para ocupación singular, disponibilidad de tina o limpieza prevista.

---

## 21. Endpoints técnicos

Objetivo V1:

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/prometheus
```

No introducir endpoints HTTP internos de negocio para saltarse RabbitMQ.

Los endpoints scaffold actuales `/health` y `/version` pueden retirarse durante el refinamiento cuando la observabilidad Actuator quede establecida.

---

## 22. Escenarios mínimos de aceptación

1. happy path `NORMAL`;
2. `JOURNEY` con preferida y fallback;
3. `IMMUNOCOMPROMISED` exclusiva;
4. llegada exactamente a `startsAt - 15m`;
5. llegada exactamente a `startsAt + 10m`;
6. rechazo fuera de ambas fronteras;
7. autorización sin recurso → `PENDING_REASSIGNMENT`;
8. resolución de pendiente → `IN_PROGRESS`;
9. pendiente sin alternativa → cancelación clínica;
10. falla de tina con varias ejecuciones afectadas;
11. reasignación conservando historial;
12. salida + corrección del Supervisor + `COMPLETED`;
13. limpieza IMMUNO automática exactamente a 3h efectivas;
14. reserva preventiva IMMUNO bloquea ventanas anteriores y posteriores;
15. cancelar/no-show/rechazar IMMUNO sin uso libera la reserva preventiva;
16. ejecución que rebasa slot.end no resta capacidad al siguiente slot por ese solo hecho;
17. carrera por último cupo del mismo Tank/slot;
18. carrera assign vs disable;
19. mensajes duplicados e idempotencia;
20. Events fuera de orden / versiones stale;
21. rebuild de Appointments, Administration y Authorization.

---

## 23. Decisiones V1 cerradas

```text
CleaningPeriod termina automáticamente a las 3h.
No hay finalización manual ni anticipada de limpieza.
Llegada permitida desde 15 min antes hasta 10 min después.
No hay checklist persistido de EPP/requisitos.
Execution que rebasa su bloque no afecta por sí sola el siguiente slot.
Appointments reserva preventivamente uso + 3h para IMMUNOCOMPROMISED.
Si la cita IMMUNO no ocurre sin uso físico, se libera la reserva preventiva.
La capacidad configurada pertenece a Tank, no a Cabin.
La capacidad operativa de asignación es Tank + AppointmentTimeSlot.
La cancelación clínica de Execution pasa por PENDING_REASSIGNMENT.
La identidad lógica RabbitMQ es wash-execution.
```

---

## 24. Pendientes no bloqueantes

No impiden comenzar V1:

- mecanismo físico exacto de validación de identidad;
- UX exacta de búsqueda manual;
- umbral futuro entre `instrumentCount` y materiales de salida;
- comunicaciones adicionales;
- códigos adicionales de `cancellationSubreason`;
- reportes/alertas futuras;
- historial de correcciones distinto del valor final confirmado.

No crear entidades, estados o comportamiento para estos puntos hasta que exista una decisión canónica explícita.
