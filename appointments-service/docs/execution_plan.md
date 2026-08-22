# Appointments Service — Execution Plan V1

**Ruta:** `appointments-service/docs/execution_plan.md`  
**Estado:** gate de implementación V1  
**Contrato local:** `appointments-service/docs/system_contract.md`

## 1. Objetivo

Implementar `appointments-service` incrementalmente con arquitectura hexagonal, dominio explícito, PostgreSQL/JdbcClient/Flyway, concurrencia race-safe, Inbox/Outbox, RabbitMQ, proyecciones locales, recovery y pruebas E2E.

Este PR documental no implementa comportamiento y no cuenta como PR de implementación.

Orden obligatorio:

```text
PR1 → Domain + Persistence + Concurrency Foundation
PR2 → Complete Application Behavior Without RabbitMQ
PR3 → Wire Contracts + RabbitMQ + Projection Recovery
PR4 → Bounded-context E2E + Failure Hardening
```

No adelantar transporte antes de asegurar dominio/persistencia.

---

## 2. Fuente de verdad y regla de drift

Antes de iniciar cada PR:

1. revisar `AnthonyNav/estomatologia-platform-docs/main`;
2. comparar Appointments + Wash Execution + Wash Administration + Academic + Authorization;
3. si aparece contradicción BLOCKER/HIGH, corregir primero documentación canónica;
4. no inventar reglas desde este repo.

El cierre canónico incluye el hardening posterior de evidencia de llegada/entrada y reutilización de autorización excepcional.

No copiar drift histórico como:

```text
*-service como logicalComponentId RabbitMQ
Cabin.configuredCapacity
AcademicPeriodRegistered
StudentAcademicProfileUpdated
StudentAcademicStatusChanged
rescheduling
PointBalance persistido
completedAt NULL como bloqueo indefinido de CleaningPeriod
```

---

## 3. Política de branches/PRs

- todos apuntan a `develop`;
- PRs secuenciales;
- cada PR parte del `develop` posterior al anterior;
- cada PR debe pasar `./mvnw clean verify`;
- pruebas automatizadas suficientes para su alcance;
- cero secretos;
- no mezclar cambios funcionales de otros servicios.

Nombres sugeridos:

```text
feat/appointments-domain-persistence
feat/appointments-application
feat/appointments-messaging-recovery
feat/appointments-e2e-hardening
```

---

# PR1 — Domain, Persistence And Concurrency Foundation

## 4. Objetivo PR1

Construir foundation técnicamente sólida sin AMQP real:

```text
dominio puro
arquitectura hexagonal
Flyway/PostgreSQL
JdbcClient/JDBC repositories
constraints/locks
Inbox/Outbox/idempotency primitives
proyecciones persistibles
evidencia persistible de Execution
pruebas concurrentes base
```

Al finalizar PR1 todavía no hay listeners RabbitMQ ni handlers funcionales completos.

---

## 5. Dependencias PR1

Producción:

```text
spring-boot-starter-jdbc
spring-boot-starter-flyway
flyway-database-postgresql
postgresql
spring-boot-starter-security
spring-boot-starter-actuator
micrometer-registry-prometheus
```

Test:

```text
spring-boot-testcontainers
testcontainers-junit-jupiter
testcontainers-postgresql
ArchUnit
```

Prohibido en PR1:

```text
spring-amqp
JPA/Hibernate
REST de negocio
```

---

## 6. Arquitectura hexagonal PR1

Estructura:

```text
com.estoma.appointments
├── domain
│   ├── model
│   ├── service
│   └── error
├── application
│   ├── port/in
│   ├── port/out
│   ├── usecase
│   └── model
├── infrastructure
│   ├── persistence
│   ├── config
│   ├── security
│   └── observability
└── contracts
```

ArchUnit obligatorio:

```text
domain !-> Spring/JDBC/AMQP/Jackson/infrastructure/contracts
application !-> infrastructure
contracts !-> business implementation
infrastructure → puede implementar application ports
```

Rechazar PR si:

- domain contiene annotations Spring/JPA;
- input adapter ejecuta SQL;
- repository concreto aparece en domain;
- wire DTO se usa como agregado;
- una clase mezcla domain + SQL + mapping + transporte.

---

## 7. Dominio PR1

Implementar:

```text
AppointmentTimeSlot
Appointment
ExceptionalAppointmentAuthorization
```

Enums/value objects mínimos:

```text
AppointmentStatus
AppointmentTimeSlotStatus
AppointmentType
PieceType
CancellationType
CancellationSubreason
```

Servicios/policies puros según convenga:

```text
AppointmentCapacityMatcher
ImmunocompromisedReservationPolicy
DailyPenaltyPolicy
ExceptionalAuthorizationConsumptionPolicy
```

Invariantes:

- state machine correcta;
- finales no reabren;
- no `INTERRUPTED`;
- instrumentCount por tipo;
- cancelación CLINIC requiere subreason;
- puntos derivados;
- IMMUNO `[slot.start, slot.end + 3h)` simétrica;
- autorización solo se restaura por `CANCELLED + CLINIC`;
- STUDENT/MISSED/ENTRY_REJECTED siguen consumiéndola.

---

## 8. Persistencia PR1

Flyway desde DB vacía debe crear como mínimo:

### Dominio

```text
appointment_time_slots
appointments
exceptional_appointment_authorizations
appointment_capacity_projections
```

### Proyecciones

```text
academic_period_projection
student_academic_profile_projection
student_course_section_projection
account_role_projection
wash_system_access_projection
service_calendar_projection
service_schedule_projection
administrative_closure_projection
cabin_projection
tank_projection
resource_priority_projection
resource_unavailability_projection
cleaning_period_projection
wash_execution_evidence_projection
wash_execution_transition_projection
projection_recovery_state
```

Los nombres físicos pueden variar si conservan la semántica.

### Procesamiento durable

```text
pending_wash_execution_events
pending_capacity_evaluation
processed_messages
command_idempotency_records
outbox_messages
```

`wash_execution_evidence_projection` debe poder guardar al menos:

```text
washExecutionId
appointmentId
arrivedAt?
arrivalExecutionVersion?
entryAuthorizedAt?
entryAuthorizedExecutionVersion?
updatedAt
```

No FKs cross-service.

---

## 9. Constraints PR1

Obligatorios/equivalentes:

```text
slot equivalente único
slot.ends_at > slot.starts_at
version > 0
instrument_count > 0
máximo una cita activa por alumno
consistencia cancellation_type/subreason
una autorización por alumno/fecha según contrato
Inbox messageId único
idempotency scope/key único
projection source identity única
pending rows claimables una sola vez
```

### Autorización excepcional reutilizable

Debe preservarse historia y a la vez impedir doble consumo.

Garantía:

```text
por exceptional_authorization_id
→ máximo 1 Appointment que actualmente consume la autorización
```

Una Appointment `CANCELLED + CLINIC` deja de participar como consumidora, pero puede conservar la referencia histórica.

Preferencia: partial unique index PostgreSQL o mecanismo equivalente probado bajo carrera.

No imponer `UNIQUE(exceptional_authorization_id)` absoluto porque impediría reutilización clínica conservando historia.

---

## 10. Repositories PR1

Ports/adapters JDBC para:

```text
AppointmentTimeSlotRepository
AppointmentRepository
ExceptionalAppointmentAuthorizationRepository
AppointmentCapacityProjectionRepository
AcademicProjection ports
AuthorizationProjection ports
WashAdministrationProjection ports
WashExecutionEvidenceRepository
WashExecutionTransitionRepository
ProcessedMessageRepository
CommandIdempotencyRepository
OutboxRepository
ProjectionRecoveryRepository
PendingWashExecutionEventRepository
PendingCapacityEvaluationRepository
```

Optimistic/conditional locking en updates existentes.

Events externos no reciben `Appointment.expectedVersion`; su protección es local por Inbox + versión causal + estado/lock.

---

## 11. Concurrencia PR1

Pruebas PostgreSQL reales:

1. dos alumnos último cupo;
2. mismo alumno dos citas activas;
3. dos claims de misma autorización;
4. reutilización de autorización tras CLINIC sin perder historia;
5. dos recálculos mismo slot;
6. Inbox concurrente;
7. optimistic version conflict;
8. pending evaluation claim;
9. evidencia Execution concurrente con MISSED foundation;
10. dos Events distintos con mismo `executionVersion` pueden coexistir/aplicarse por messageId.

Resultado requerido:

```text
no oversell
no double-active appointment
no double-consume authorization
no lost update
no event lost only because same executionVersion
```

---

## 12. Operación PR1

Objetivo:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/prometheus
```

Spring Security niega demás HTTP.

Readiness PR1:

```text
PostgreSQL reachable
Flyway/schema compatible
```

`SystemController` temporal puede retirarse.

---

## 13. Acceptance Criteria PR1

- `./mvnw clean verify` verde;
- Java 25/Spring Boot compilan;
- Flyway DB vacía Testcontainers;
- no JPA/Hibernate;
- domain framework-free;
- ArchUnit verde;
- repositories integration-tested;
- último cupo race-safe;
- una cita activa por alumno race-safe;
- autorización consumer uniqueness permite historia CLINIC;
- evidence projection existe;
- same-version distinct Events representables;
- IMMUNO policy simétrica testeada;
- primitives Inbox/Outbox/idempotency persistibles;
- sin AMQP;
- sin HTTP negocio.

---

# PR2 — Complete Application Behavior Without RabbitMQ

## 14. Objetivo PR2

Completar toda Application llamando directamente input ports, con PostgreSQL real y sin broker.

PR3 solo adaptará transporte/contratos externos.

---

## 15. Casos de uso PR2

### Cliente/actor

```text
ScheduleAppointment
CancelAppointment
GrantExceptionalAppointmentAuthorization
CancelExceptionalAppointmentAuthorization
```

### Agenda/capacidad internos

```text
CreateAppointmentTimeSlot
CancelAppointmentTimeSlot
HandleResourceCapacityEvent
RecalculateAppointmentCapacityForTimeSlot
CancelScheduledAppointmentsForCapacityLoss
MarkAppointmentAsMissed
```

### Evidencia Execution

```text
HandleWashArrivalRegistered
HandleWashEntryAuthorized
```

### Transiciones Execution

```text
HandleWashEntryRejected
HandleWashExecutionStarted
HandleWashExecutionCompleted
HandleWashExecutionCancelled
```

Todos entran por application input ports.

---

## 16. Pipeline transaccional PR2

Para Command mutante:

```text
application model validado
→ Inbox/idempotency
→ authorization/projections
→ locks
→ load
→ domain/application rules
→ mutation
→ Outbox Result/Event intents
→ commit
```

Rechazo funcional → Result REJECTED, sin retry.

Fallo técnico → rollback completo.

---

## 17. `ScheduleAppointment` PR2

Validar:

- actor y acceso;
- Academic confiable/NRC vigente;
- fecha actual permitida;
- slot ACTIVE/deadline;
- tipo/instrumentCount/pieceType;
- no active appointment;
- puntos < 3;
- límite COMPLETED;
- autorización excepcional;
- IMMUNO;
- matching/capacidad final bajo lock;
- consumo atómico de autorización;
- Appointment + Outbox intents atómicos.

Pruebas:

```text
último cupo: 2 requests → exactamente 1 éxito
mismo alumno concurrente → máximo 1 activa
IMMUNO anterior/posterior → conflicto simétrico
```

---

## 18. Cancelación/MISSED PR2

### Student cancel

```text
SCHEDULED
owns appointment
now <= cancellationDeadlineAt
→ CANCELLED + STUDENT
→ 1 punto
→ autorización consumida NO se restaura
```

### MISSED

Solo si:

```text
SCHEDULED
now > arrivalToleranceUntil
entryAuthorizedAt == null
NO valid WashArrivalRegistered
NO start/entry evidence
Execution evidence confiable
```

`MarkAppointmentAsMissed` debe bloquear/releer Appointment + evidence.

Si evidence/recovery está `REBUILDING`, `DEGRADED`, no confiable o causalmente pendiente:

```text
NO MISSED
→ defer/retry
```

Gracia técnica configurable permitida para ejecutar el worker después del deadline; no cambia el +10m funcional.

Escenario obligatorio:

```text
arrivedAt = +9m
Supervisor decide = +12m
→ no MISSED
```

---

## 19. Evidence handlers PR2

### `HandleWashArrivalRegistered`

- dedupe por sourceEventId/messageId;
- validar `arrivedAt` respecto al slot local;
- persistir evidencia;
- no cambiar Appointment.status;
- impedir posterior MISSED si llegada fue válida.

### `HandleWashEntryAuthorized`

- dedupe exacto;
- persistir `Appointment.entryAuthorizedAt = decidedAt`;
- no cambiar status por sí solo;
- funciona si Appointment sigue SCHEDULED/PENDING_REASSIGNMENT;
- si STARTED ya llegó, completa metadata sin retroceder IN_PROGRESS.

### Same-version rule

`WashEntryAuthorized` y `WashExecutionStarted` pueden tener el mismo `executionVersion`.

No se puede usar:

```text
if executionVersion <= globalSourceVersion then discard
```

para Events de tipos distintos.

Exact dedupe por messageId; evidencia y transition versioning separados o equivalente.

---

## 20. Exceptional authorization PR2

Grant/Cancel según autorización local y reglas canónicas.

Consumo/restauración:

```text
Schedule additional appointment → consume
CLINIC cancellation → deja de consumir, preserva history
STUDENT/MISSED/ENTRY_REJECTED → continúa consumiendo
```

Pruebas:

- redelivery CLINIC;
- dos workers restore;
- reusar autorización después de CLINIC;
- historia conserva primera Appointment cancelada;
- no dos consumers simultáneos;
- STUDENT/MISSED/ENTRY_REJECTED nunca habilitan reuse.

---

## 21. Capacidad/cambios administrativos PR2

`HandleResourceCapacityEvent` actualiza proyección y crea recálculos internos.

`RecalculateAppointmentCapacityForTimeSlot`:

- claim/lock race-safe;
- capacidad física + restricciones;
- matching completo;
- decide cancelaciones;
- persiste capacity projection/event intent.

`CancelScheduledAppointmentsForCapacityLoss` solo:

```text
SCHEDULED
entryAuthorizedAt == null
```

Orden:

```text
createdAt DESC
appointmentId DESC
```

Resultado:

```text
CLINIC / CAPACITY_LOSS
0 puntos
```

Si usó autorización excepcional → deja de consumirla en misma tx, preservando referencia histórica.

Nunca auto-reschedule.

Cleaning real usa `[startsAt, expectedEndsAt)` y no `completedAt NULL` indefinido.

---

## 22. Transition handlers PR2

```text
WashEntryRejected
→ SCHEDULED → ENTRY_REJECTED

WashExecutionStarted
→ SCHEDULED → IN_PROGRESS

WashExecutionCompleted
→ IN_PROGRESS → COMPLETED

WashExecutionCancelled
→ SCHEDULED|IN_PROGRESS → CANCELLED + CLINIC
```

- no expectedVersion remoto;
- Inbox;
- conditional transitions;
- versioning causal;
- Appointment.version incrementa localmente;
- CLINIC restore/reuse de authorization atómico.

---

## 23. Out-of-order PR2

Persistencia:

```text
processed_messages: DEFERRED | APPLIED
pending_wash_execution_events
pending_capacity_evaluation
```

Caso:

```text
COMPLETED antes de STARTED
→ no saltar SCHEDULED→COMPLETED
→ DEFERRED
→ STARTED
→ reprocesar COMPLETED
```

Caso cleaning causal:

```text
CleaningPeriodStarted antes de terminal Appointment
→ pending_capacity_evaluation
→ terminal aplica primero
→ matching pendiente después
```

No filas huérfanas ni doble processing.

---

## 24. Authorization + atomicidad PR2

Evaluator local:

```text
ActorContext
+ role projection
+ SystemAccess projection
+ static permission matrix
```

No llamada remota.

Transacción según aplique:

```text
Inbox
idempotency
locks
mutation
authorization consume/release semantics
Outbox Result
Outbox Event(s)
```

Forced Outbox failure → rollback total.

---

## 25. Acceptance Criteria PR2

- todos use cases happy + rejected;
- Schedule race-safe;
- points derivados;
- solo COMPLETED límite diario;
- no rescheduling;
- arrival evidence protege MISSED;
- entryAuthorizedAt se persiste aun sin recurso;
- same-version EntryAuthorized/Started no se pierden;
- authorization reuse CLINIC conserva historia;
- STUDENT/MISSED/ENTRY_REJECTED no restore;
- admin change cancela solo elegibles;
- entry-authorized/IN_PROGRESS protegidos;
- IMMUNO simétrica;
- out-of-order converge;
- atomic Inbox/idempotency/mutation/Outbox;
- sin AMQP;
- `./mvnw clean verify` verde.

---

# PR3 — Wire Contracts, RabbitMQ And Projection Recovery

## 26. Objetivo PR3

Conectar comportamiento probado a RabbitMQ y completar recovery/projection readiness.

No rediseñar dominio.

---

## 27. Topología PR3

```text
logicalComponentId = appointments

commands.appointments
queries.appointments
results.appointments
events.appointments

platform.commands
platform.queries
platform.results
platform.events
platform.dlx
```

Nunca aliases `appointments-service`.

Agregar:

- queues/bindings;
- retry/DLQ;
- manual ACK;
- confirms;
- readiness de broker/topology.

---

## 28. Wire contracts PR3

Separados de domain:

```text
Command payload DTOs
Result DTOs
produced Event DTOs
consumed Academic Events
consumed Authorization Events/Results
consumed Wash Administration Events/Results
consumed Wash Execution Events/Results
Query DTOs ofrecidos a Wash Execution
```

Seguir JSON Schema/AsyncAPI si es convención vigente del repo.

Wire validation antes de mapping:

```text
required fields
enums
UUID
timestamps
versions
ActorContext
types/nullability
```

No primitives missing → `0/false` silencioso.

---

## 29. Adapters PR3

Listener:

```text
wire validate
→ map
→ application input port
→ commit
→ ACK
```

Sin business logic.

Errores:

```text
functional → Result REJECTED + ACK
transient technical → retry
permanent invalid wire → DLQ/quarantine
```

---

## 30. Event consumers PR3

### Academic

Solo V1 actual. No bindear contratos obsoletos.

### Authorization

Role/SystemAccess monotónicos + recovery.

### Wash Administration

```text
ServiceCalendar
ServiceSchedule
AdministrativeClosure
Cabin
Tank
ResourcePriority
```

### Wash Execution

Evidencia:

```text
WashArrivalRegistered
WashEntryAuthorized
```

Transición:

```text
WashEntryRejected
WashExecutionStarted
WashExecutionCompleted
WashExecutionCancelled
```

Capacidad:

```text
OperationalResourceDisabled
OperationalResourceRestored
CleaningPeriodStarted
CleaningPeriodCompleted
```

Prueba obligatoria:

```text
WashEntryAuthorized.executionVersion = N
WashExecutionStarted.executionVersion = N
→ ambos aplican exactamente una vez
```

---

## 31. Recovery PR3

### Academic

Usar Queries/Results autoritativos vigentes para period/profile/enrollments. No inventar nombres.

### Authorization

Recovery canónico AccountRole/SystemAccess.

### Wash Administration

```text
GetWashAdministrationConfiguration
```

### Wash Execution

Recovery debe reconstruir evidencia de llegada/entrada y estado operativo necesario para decisiones seguras. Si el contrato canónico vigente no ofrece evidencia suficiente para reconstruir un caso, el PR3 debe detener ese comportamiento y corregir primero docs/contratos productores; no asumir ausencia.

### Appointments como Query owner

```text
GetAppointmentExecutionContext
ListAppointmentExecutionContextsPage
```

Requester allowlist:

```text
wash-execution
```

---

## 32. Recovery state/readiness PR3

Por familia crítica:

```text
UNINITIALIZED
REBUILDING
READY
DEGRADED
```

(o convención equivalente).

Reglas:

- empty projection → bootstrap;
- Events pueden llegar durante rebuild;
- snapshot/Event race converge por versión/identidad;
- READY solo después de reconciliar;
- fallo persistente → no confiable;
- Commands irreversibles dependientes fallan cerrado o se difieren.

En particular `MISSED` no se ejecuta con evidencia Execution no confiable.

---

## 33. Outbox Relay PR3

Obligatorio:

- multi-replica safe;
- claim/fencing/lease;
- publisher confirms;
- transient retry/backoff sin finite→FAILED automático;
- permanent invalid contract → quarantine/FAILED;
- restart safe;
- metrics.

---

## 34. QR PR3

Implementar protección tamper-resistant:

- key/secret externo;
- token alterado inválido;
- appointmentId alterado inválido;
- QR válido pero Appointment final → funcionalmente no utilizable;
- firma no autoriza entrada.

---

## 35. Testing PR3

RabbitMQ Testcontainers:

- Command→Result/Event;
- duplicate delivery;
- idempotency conflict;
- functional no retry;
- technical retry/DLQ;
- confirms/relay restart;
- stale/duplicate Events;
- arrival/entry evidence;
- same-version distinct Events;
- out-of-order transitions;
- FULL/ENTITY recovery;
- snapshot+Event race;
- Query requester allowlist;
- readiness down during rebuild.

---

## 36. Acceptance Criteria PR3

- topology solo `appointments`;
- thin adapters;
- wire/domain separados;
- RabbitMQ Testcontainers E2E;
- Result/Event via confirmed Outbox;
- retry/DLQ probado;
- evidence/transitions convergen;
- same-version distinct Events seguros;
- recovery sin Event Store;
- Query responses a Execution;
- QR secure;
- readiness real;
- `./mvnw clean verify` verde.

---

# PR4 — Bounded-context E2E And Failure Hardening

## 37. Objetivo PR4

Demostrar comportamiento real de Appointments integrado con Lavado y bajo fallos distribuidos.

No agregar reglas nuevas.

---

## 38. Entorno mínimo

```text
appointments-service
wash-administration-service
wash-execution-service
RabbitMQ
PostgreSQL aislado por servicio
```

Academic/Authorization reales cuando sea viable; si no, productores contractualmente fieles para el E2E del bounded context.

---

## 39. Escenarios funcionales obligatorios

1. semana administrativa → materialización slots;
2. NORMAL preferred;
3. NORMAL fallback JOURNEY;
4. JOURNEY fallback NORMAL;
5. IMMUNO reserva simétrica;
6. STUDENT cancel → 1 punto;
7. MISSED → 1 punto;
8. ENTRY_REJECTED → 0 punto + libera IMMUNO;
9. COMPLETED → límite diario;
10. segunda cita con autorización;
11. CLINIC cancel segunda cita → autorización reutilizable;
12. nueva cita posterior reutiliza misma autorización manteniendo historia;
13. cambio horario semanal → recálculo/cancelación;
14. reducción capacidad → últimas citas canceladas;
15. entry-authorized SCHEDULED protegido;
16. IN_PROGRESS protegido;
17. CleaningPeriod real afecta intervalo real;
18. CleaningStarted fuera de orden vs terminal;
19. COMPLETED antes de STARTED converge;
20. último cupo race;
21. MISSED vs llegada válida race;
22. llegada +9m y decisión +12m → no MISSED;
23. WashEntryAuthorized + Started misma executionVersion → ambos efectos presentes.

---

## 40. Failure/recovery obligatorios

- restart con Outbox pendiente;
- RabbitMQ down/up;
- duplicate redelivery post-restart;
- rebuild Academic;
- rebuild Authorization;
- rebuild Administration;
- rebuild Execution evidence/operation;
- Events durante FULL rebuild;
- stale snapshot/result;
- permanent invalid DLQ;
- replicas reclamando Outbox;
- replicas recalculando mismo slot;
- evidence delayed sin producir MISSED incorrecto.

---

## 41. Escenario administrativo semanal

```text
W35 TUE 08:00-17:00
→ slots
→ reservas

Admin cambia TUE 10:00-14:00
→ ServiceScheduleUpdated
→ recalc
→ slots fuera dejan de ser reservables
→ SCHEDULED elegibles CLINIC/CAPACITY_LOSS
→ no reschedule
→ authorization CLINIC reusable
→ entry-authorized/IN_PROGRESS no cancelados
```

Preview UI no es precondición; backend revalida.

---

## 42. Acceptance Criteria PR4

- todos escenarios críticos verdes;
- recovery de proyecciones/evidence converge;
- sin oversell;
- sin doble active appointment;
- sin doble consume authorization;
- historia de authorization reuse preservada;
- ningún MISSED incorrecto por delivery lag de llegada válida;
- no se pierde same-version distinct Event;
- no Result/Event perdido por broker failure;
- no HTTP/gRPC negocio;
- Admin/Appointments/Execution convergen tras redelivery/out-of-order;
- observabilidad útil;
- `./mvnw clean verify` verde.

---

# 43. Checklist global de arquitectura

```text
[ ] domain no Spring/JDBC/AMQP/Jackson
[ ] application no infrastructure adapters
[ ] input adapters solo map + call ports
[ ] output adapters implementan ports
[ ] SQL solo infrastructure.persistence
[ ] RabbitMQ solo infrastructure.messaging
[ ] wire contracts separados
[ ] ArchUnit verde
```

---

# 44. Checklist global de consistencia

```text
[ ] sin FK cross-service
[ ] Flyway DB vacía
[ ] constraints race-safe
[ ] optimistic/conditional locking
[ ] Inbox durable
[ ] idempotency durable
[ ] Outbox atómico
[ ] relay restart-safe
[ ] source versions con semántica correcta por stream/tipo
[ ] same-version distinct Events no se pierden
[ ] arrival/entry evidence durable
[ ] deferred processing durable
[ ] recovery state durable
[ ] readiness fail-closed
```

---

# 45. Checklist global de negocio

```text
[ ] no rescheduling
[ ] no point balance independiente
[ ] solo COMPLETED cuenta diario
[ ] STUDENT = 1 punto
[ ] MISSED = 1 punto
[ ] CLINIC = 0 puntos
[ ] ENTRY_REJECTED = 0 puntos
[ ] valid arrival protects MISSED
[ ] WashEntryAuthorized sets entryAuthorizedAt
[ ] CLINIC authorization restore/reuse preserves history
[ ] STUDENT/MISSED/ENTRY_REJECTED do not restore
[ ] Tank.configuredCapacity source
[ ] Cabin no capacity
[ ] NORMAL/JOURNEY matching
[ ] IMMUNO symmetric +3h
[ ] cleaning interval uses expectedEndsAt
[ ] admin change can cancel SCHEDULED
[ ] entry-authorized/IN_PROGRESS protected
[ ] out-of-order transition cannot skip state
```

---

# 46. Definition of Done V1

`appointments-service` V1 solo está implementado cuando:

```text
PR1 foundation ✅
PR2 application ✅
PR3 messaging/recovery ✅
PR4 E2E/failure hardening ✅
```

Además:

- cero contradicciones BLOCKER/HIGH conocidas;
- gates arquitectónicos automatizados;
- concurrencia/recovery automatizados, no solo manuales;
- no shortcuts síncronos;
- no aliases RabbitMQ `*-service`;
- no reglas diferidas inventadas.
