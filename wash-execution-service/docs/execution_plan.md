# Wash Execution Service — Execution Plan V1

**Ruta:** `wash-execution-service/docs/execution_plan.md`
**Estado:** gate de implementación V1
**Contrato local:** [`system_contract.md`](system_contract.md)

## 1. Objetivo

Implementar `wash-execution-service` sin mezclar de una sola vez dominio, persistencia, mensajería y recovery.

La estrategia replica el nivel de disciplina ya aplicado en `wash-administration-service`: dominio explícito, arquitectura hexagonal, PostgreSQL/JdbcClient/Flyway, Outbox/Inbox, RabbitMQ, proyecciones locales, recovery y pruebas de concurrencia.

La documentación canónica vive en `AnthonyNav/estomatologia-platform-docs`; este plan solo define el orden de implementación dentro de este repositorio.

---

## 2. Gate previo

Antes de escribir comportamiento de producción deben considerarse cerradas las reglas de [`system_contract.md`](system_contract.md), en particular:

- llegada `-15m / +10m`;
- validación simple sin checklist;
- capacidad configurada solo en `Tank`;
- capacidad de asignación por `Tank + AppointmentTimeSlot`;
- una ejecución retrasada no bloquea por sí sola el siguiente slot;
- limpieza `IMMUNOCOMPROMISED` automática a 3h;
- reserva preventiva IMMUNO hacia atrás y hacia adelante;
- recovery de Appointments mediante Queries;
- logical component ID `wash-execution`.

---

## 3. PR 1 — Dominio, persistencia y concurrencia

### Dominio

Implementar sin RabbitMQ:

```text
WashExecution
EntryValidation
ResourceAssignment
ResourceUnavailability
CleaningPeriod
```

Value Objects/enums mínimos:

```text
WashExecutionStatus
EntryDecision
AppointmentType
CabinType
AssignmentType
ReleaseReason
ResourceType
UnavailabilityCauseType
CleaningTrigger
CancellationType
```

### Persistencia

Usar:

```text
PostgreSQL
Spring JdbcClient/JDBC
Flyway
```

No introducir JPA si el resto del bounded context mantiene persistencia explícita JDBC.

Tablas de dominio + infraestructura:

```text
wash_executions
entry_validations
resource_assignments
resource_unavailabilities
resource_unavailability_affected_executions
cleaning_periods

appointment_projection
appointment_time_slot_projection
cabin_projection
tank_projection
resource_priority_projection
administrative_closure_projection
role_projection
system_access_projection
projection_recovery_state

outbox_messages
processed_messages
```

### Constraints obligatorios

Como mínimo:

```text
UNIQUE wash_executions(appointment_id)
UNIQUE entry_validations(wash_execution_id)
UNIQUE parcial de ResourceAssignment vigente por WashExecution
UNIQUE cleaning_periods(resource_assignment_id)
```

La capacidad `Tank + AppointmentTimeSlot` debe protegerse transaccionalmente; no basta con un `COUNT` sin lock/revalidación.

### Concurrencia

Definir orden estable de locks para recursos candidatos.

Cubrir:

- último cupo del mismo Tank/slot;
- asignación vs inhabilitación;
- dos resoluciones de la misma pendiente;
- doble completar/cancelar;
- una falla que afecta varias ejecuciones;
- doble worker completando el mismo CleaningPeriod.

### Arquitectura

Estructura objetivo equivalente al patrón del bounded context:

```text
application/
contracts/
domain/
infrastructure/
```

Separar puertos de entrada/salida y mantener dominio sin dependencias de Spring.

### Calidad

Agregar:

```text
ArchUnit
Testcontainers PostgreSQL
pruebas de migración
pruebas de dominio
pruebas de integración transaccional
```

### Definition of Done PR1

- modelo de dominio compila y prueba invariantes;
- Flyway levanta DB desde cero;
- repositorios JDBC funcionan con PostgreSQL real de Testcontainers;
- constraints impiden duplicados fundamentales;
- último cupo del Tank/slot es seguro bajo concurrencia;
- assign vs disable no deja estados contradictorios;
- arquitectura hexagonal protegida por tests;
- `./mvnw clean verify` verde.

---

## 4. PR 2 — Casos de uso completos sin transporte AMQP

Implementar handlers/application services para Commands públicos:

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

Comportamiento interno:

```text
AssignResourcesAfterEntryAuthorization
ProposeResourceAssignment
CompleteExpiredCleaningPeriods
```

### Requisitos de cada Command

- autorización local;
- validación de estado;
- `idempotencyKey` donde aplique;
- versionado/optimistic concurrency del agregado;
- persistencia atómica;
- intents de Outbox para RESULT + EVENT;
- errores funcionales estables;
- no depender de RabbitMQ para probar el comportamiento de application/domain.

### Casos clave

#### Arrival

```text
-15m <= arrivedAt <= +10m
→ PENDING_ENTRY
```

#### Entry rejected

```text
PENDING_ENTRY
→ ENTRY_REJECTED
```

#### Entry authorized con recurso

```text
PENDING_ENTRY
→ asignación automática
→ IN_PROGRESS
```

#### Entry authorized sin recurso

```text
PENDING_ENTRY
→ PENDING_REASSIGNMENT
```

#### Reasignación

```text
PENDING_REASSIGNMENT
→ alternativa válida
→ IN_PROGRESS
```

#### Cancelación clínica

```text
PENDING_REASSIGNMENT
→ CANCELLED
```

#### Exit/completion

```text
IN_PROGRESS
→ EXIT_SUBMITTED
→ COMPLETED
```

#### Cleaning

```text
assignment IMMUNO liberada
→ CleaningPeriod
→ expectedEndsAt = startsAt + 3h
→ finalización automática
```

### Definition of Done PR2

- ocho Commands cubiertos por tests;
- state machine completa;
- allocator determinista;
- `PENDING_REASSIGNMENT` cubre ambos orígenes;
- no existe cancelación directa `IN_PROGRESS → CANCELLED`;
- salida permite corrección final del Supervisor;
- cleaning auto-completion idempotente;
- una ejecución que rebasa su slot no altera automáticamente el siguiente;
- Outbox intents se generan atómicamente aunque todavía no exista AMQP real;
- `./mvnw clean verify` verde.

---

## 5. PR 3 — Mensajería, proyecciones y recovery

### RabbitMQ

Agregar:

```text
commands.wash-execution
results.wash-execution
bindings de platform.events
bindings de platform.queries/results para recovery
retry queues
DLQ
manual ACK
publisher confirms en Outbox Relay
```

Nunca usar `wash-execution-service` como routing key lógico.

### Eventos consumidos — Appointments

Como mínimo los hechos canónicos que permitan mantener:

```text
Appointment
AppointmentTimeSlot
```

Incluyendo el equivalente vigente de:

```text
AppointmentScheduled
AppointmentCancelled
AppointmentMissed
AppointmentTimeSlotCreated
AppointmentTimeSlotCancelled
```

No inventar nombres nuevos si el productor ya tiene un evento canónico distinto.

### Eventos consumidos — Wash Administration

Mantener proyecciones de:

```text
Cabin
Tank
ResourcePriority
AdministrativeClosure
```

### Eventos consumidos — Authorization

```text
AccountRoleAssigned / AccountRoleChanged
SystemAccessGranted
SystemAccessSuspended
SystemAccessRevoked
SystemAccessRestored
```

### Recovery

Implementar:

```text
Appointments
→ GetAppointmentExecutionContext
→ ListAppointmentExecutionContextsPage

Wash Administration
→ GetWashAdministrationConfiguration

Authorization
→ mecanismo definido por el contrato canónico de authorization-projection
```

Readiness debe fallar cerrado si una proyección obligatoria está `REBUILDING`, `DEGRADED` o no confiable.

### Outbox Relay

Debe soportar:

- reintento independiente del transaction handler;
- publisher confirms;
- no marcar publicado antes de confirmación;
- recovery tras reinicio;
- métricas de pending/age/failures.

### Consumer Inbox

Debe soportar:

- deduplicación por `messageId`;
- stale `sourceVersion`;
- eventos fuera de orden;
- defer/reconcile causal cuando corresponda;
- retry/DLQ para fallos técnicos.

### Testing

Agregar Testcontainers RabbitMQ y pruebas de:

- delivery duplicada;
- stale sourceVersion;
- out-of-order causal;
- retry/DLQ;
- Outbox relay;
- rebuild de proyección con Events concurrentes;
- caída/reinicio del consumer;
- recovery de Appointments;
- recovery de Wash Administration;
- recovery de Authorization.

### Definition of Done PR3

- Command → Result/Event funciona de extremo a extremo con RabbitMQ;
- Outbox/Inbox sobreviven retry y restart;
- proyecciones convergen por versión;
- rebuild no depende de RabbitMQ como Event Store;
- readiness refleja estado real de proyecciones;
- `./mvnw clean verify` verde.

---

## 6. PR 4 — Integración E2E del bounded context

Validar al menos:

```text
appointments-service
wash-administration-service
wash-execution-service
RabbitMQ
PostgreSQL por servicio
```

Escenarios:

1. registrar cita NORMAL → llegada → autorización → asignación → salida → completion;
2. JOURNEY preferred/fallback;
3. IMMUNO + reserva preventiva + CleaningPeriod real + finalización automática;
4. cancelar IMMUNO sin uso → recuperar disponibilidad preventiva;
5. MISSED IMMUNO sin uso → recuperar disponibilidad preventiva;
6. ENTRY_REJECTED IMMUNO sin uso → recuperar disponibilidad preventiva;
7. entrada autorizada sin recurso → pending → asignación;
8. falla durante ejecución → pending → reasignación;
9. falla sin alternativa → cancelación clínica;
10. pérdida de capacidad de citas futuras reflejada por Appointments;
11. ejecución que cruza `slot.endsAt` sin restar capacidad al siguiente slot por ese solo hecho;
12. cleaning real con `releasedAt` distinto del fin previsto del slot;
13. carrera por último cupo;
14. assign vs disable;
15. recovery de cada proyección.

### Reserva preventiva IMMUNO

Debe comprobarse explícitamente el conflicto simétrico.

Ejemplo, cita 14:00-15:00:

```text
11:00-12:00 → no disponible
12:00-13:00 → no disponible
13:00-14:00 → no disponible
14:00-15:00 → reservada
15:00-16:00 → no disponible
16:00-17:00 → no disponible
17:00-18:00 → no disponible
18:00-19:00 → potencialmente disponible
```

### Limpieza real

Ejemplo:

```text
previsto:
10:00-11:00 uso
11:00-14:00 limpieza

real:
releasedAt = 10:35
cleaning = 10:35-13:35
```

Appointments debe poder reconciliar capacidad con el intervalo real.

---

## 7. Observabilidad y operación

Objetivo de endpoints técnicos:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/prometheus
```

Los endpoints scaffold `/health` y `/version` pueden retirarse al consolidar Actuator.

Métricas mínimas recomendadas:

```text
command latency/result
outbox pending/age
consumer retry/dlq
projection readiness/recovery
pending reassignment count
active resource unavailability count
active cleaning periods count
automatic cleaning completion failures
```

No crear endpoints HTTP internos de negocio para saltarse RabbitMQ.

---

## 8. Definition of Done V1

`wash-execution-service` V1 está listo cuando:

- pasa `./mvnw clean verify`;
- arquitectura hexagonal está protegida por ArchUnit;
- migraciones levantan desde DB vacía;
- Commands son idempotentes;
- Outbox/Inbox funcionan bajo retry;
- no hay sobrecapacidad concurrente del mismo Tank/slot;
- no existe doble CleaningPeriod por assignment;
- limpieza se completa automáticamente con tiempo efectivo `expectedEndsAt`;
- Appointments puede reconstruirse sin Event Store;
- Wash Administration puede reconstruirse;
- Authorization puede reconstruirse;
- los escenarios mínimos del `system_contract.md` tienen prueba automatizada o E2E;
- no quedan contradicciones conocidas que cambien comportamiento V1.

---

## 9. Orden de trabajo recomendado

```text
PR1
Domain + Persistence + Concurrency
        ↓
PR2
Application Use Cases
        ↓
PR3
RabbitMQ + Projections + Recovery
        ↓
PR4
Bounded-context E2E
```

No adelantar mensajería si las invariantes de dominio/persistencia aún no están protegidas.

No implementar comportamiento no confirmado únicamente porque exista un pendiente futuro en la documentación canónica.
