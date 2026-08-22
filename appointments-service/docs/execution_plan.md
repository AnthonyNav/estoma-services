# Appointments Service — Execution Plan V1

**Ruta:** `appointments-service/docs/execution_plan.md`  
**Estado:** gate de implementación V1  
**Contrato local:** `appointments-service/docs/system_contract.md`

## 1. Objetivo

Implementar `appointments-service` de forma incremental, manteniendo arquitectura hexagonal, dominio explícito, persistencia PostgreSQL/JdbcClient/Flyway, idempotencia durable, Transactional Outbox, Consumer Inbox, RabbitMQ, proyecciones locales, recovery y pruebas de concurrencia.

Este PR documental no implementa comportamiento de producción y no cuenta como uno de los PRs de implementación.

La secuencia recomendada es:

```text
PR1 → Domain + Persistence + Concurrency Foundation
PR2 → Complete Application Behavior Without RabbitMQ
PR3 → Wire Contracts + RabbitMQ + Projection Recovery
PR4 → Bounded-context E2E + Failure Hardening
```

No adelantar mensajería mientras las invariantes de dominio/persistencia no estén protegidas.

---

## 2. Política de branches y PRs

- todos los PRs apuntan a `develop`;
- no hacer commits directos a `develop`;
- PR2 parte del `develop` posterior al merge de PR1;
- PR3 parte del `develop` posterior al merge de PR2;
- PR4 parte del `develop` posterior al merge de PR3;
- cada PR debe compilar y pasar `./mvnw clean verify` por sí mismo;
- cada PR incluye pruebas suficientes para su alcance;
- no introducir secretos;
- no modificar reglas canónicas desde este repo;
- si aparece contradicción BLOCKER/HIGH, se corrige primero `estomatologia-platform-docs`;
- no mezclar cambios funcionales de Wash Administration o Wash Execution en PRs de Appointments.

Nombres sugeridos:

```text
PR1: feat/appointments-domain-persistence
PR2: feat/appointments-application
PR3: feat/appointments-messaging-recovery
PR4: feat/appointments-e2e-hardening
```

---

# PR1 — Domain, Persistence And Concurrency Foundation

## 3. Propósito

Construir una foundation sólida antes de escribir handlers completos o RabbitMQ:

- dominio puro;
- arquitectura hexagonal;
- PostgreSQL/Flyway;
- repositories JDBC;
- constraints;
- optimistic/conditional locking;
- primitives de Inbox/Outbox/idempotencia;
- proyecciones persistibles;
- pruebas concurrentes fundamentales.

Al terminar PR1 todavía no existe transporte AMQP real ni handlers completos de negocio.

---

## 4. Dependencias PR1

Agregar como mínimo:

Producción:

```text
spring-boot-starter-jdbc
spring-boot-starter-flyway
flyway-database-postgresql
postgresql
spring-boot-starter-security
```

Mantener:

```text
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

No agregar AMQP en PR1.

No agregar JPA/Hibernate.

---

## 5. Arquitectura hexagonal PR1

Crear la estructura objetivo:

```text
com.estoma.appointments
├── domain
├── application
├── infrastructure
└── contracts
```

Detalle esperado:

```text
domain/
├── model/
├── service/
└── error/

application/
├── port/in/
├── port/out/
├── usecase/
└── model/

infrastructure/
├── persistence/
├── config/
├── security/
└── observability/

contracts/
└── package-info.java o vacío en PR1
```

ArchUnit obligatorio:

```text
domain !-> Spring/JDBC/AMQP/Jackson/infrastructure/contracts
application !-> infrastructure
contracts !-> domain implementation
infrastructure puede depender de application/domain
```

Criterio de rechazo del PR:

- un listener/controller llama SQL directamente;
- un repository concreto se usa desde domain;
- entidades de dominio tienen annotations Spring/JPA;
- DTOs externos se convierten en modelo de dominio compartido.

---

## 6. Dominio PR1

Implementar:

```text
AppointmentTimeSlot
Appointment
ExceptionalAppointmentAuthorization
```

Value objects/enums mínimos:

```text
AppointmentStatus
AppointmentTimeSlotStatus
AppointmentType
PieceType
CancellationType
CancellationSubreason
ServiceDate / temporal value objects cuando aporten invariantes
```

Servicios de dominio puros cuando la lógica no pertenezca a una sola entidad:

```text
AppointmentCapacityMatcher
ImmunocompromisedReservationPolicy
DailyPenaltyPolicy
```

No forzar exactamente esos nombres si la separación semántica equivalente queda clara.

Invariantes puras obligatorias:

- estados/transiciones válidas;
- estados finales no reabren cita;
- `instrumentCount` por tipo;
- `pieceType` válido;
- cancelación clínica requiere subreason;
- cancelación student no se transforma en clinic;
- no `INTERRUPTED`;
- restauración excepcional solo por clinic según contrato;
- ventana IMMUNO derivada correctamente;
- puntos derivados correctamente.

---

## 7. Persistencia PR1

Flyway debe crear desde DB vacía al menos:

Funcional:

```text
appointment_time_slots
appointments
exceptional_appointment_authorizations
appointment_capacity_projections
```

Proyecciones:

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
wash_execution_state_projection
pending_capacity_evaluation
projection_recovery_state
```

Infraestructura:

```text
processed_messages
command_idempotency_records
outbox_messages
```

No FKs cross-service.

No copiar campos obsoletos del `data-model.md` histórico cuando contradigan el `v1-contract-gate.md`.

Ejemplos explícitos:

```text
Cabin projection
→ NO configured_capacity propio

Academic
→ NO AcademicPeriodRegistered
→ NO StudentAcademicProfileUpdated
→ NO StudentAcademicStatusChanged
```

---

## 8. Constraints PR1

Como mínimo:

```text
UNIQUE slot equivalente por origen/fecha/intervalo
CHECK slot.ends_at > slot.starts_at
CHECK version > 0
CHECK appointments.instrument_count > 0
constraint/partial unique para una cita activa por alumno
consistencia cancellation_type / cancellation_subreason
unicidad lógica de ExceptionalAppointmentAuthorization por alumno/fecha según contrato
UNIQUE processed_messages(message_id)
UNIQUE idempotency scope/key
UNIQUE projection source identity
```

La garantía de una cita activa por alumno debe ser race-safe en PostgreSQL; no se acepta únicamente un pre-check Java.

---

## 9. Repositories PR1

Ports + adapters JDBC para:

```text
AppointmentTimeSlotRepository
AppointmentRepository
ExceptionalAppointmentAuthorizationRepository
AppointmentCapacityProjectionRepository

AcademicProjectionRepository/ports necesarios
AuthorizationProjectionRepository/ports necesarios
WashAdministrationProjectionRepository/ports necesarios
WashExecutionProjectionRepository/ports necesarios

ProcessedMessageRepository
CommandIdempotencyRepository
OutboxRepository
ProjectionRecoveryRepository
PendingCapacityEvaluationRepository
```

Usar `JdbcClient`/Spring JDBC.

Optimistic locking o conditional updates:

```text
UPDATE ...
WHERE id = :id
  AND version = :expectedVersion
```

Para handlers derivados de Events, usar estado/versión causal local y locks/conditional updates; no inventar `expectedVersion` enviado por otro servicio.

---

## 10. Concurrencia PR1

Antes de considerar PR1 terminado deben existir pruebas reales contra PostgreSQL para:

1. dos alumnos intentando el último cupo;
2. mismo alumno intentando dos citas activas concurrentes;
3. dos claims/modificaciones de la misma autorización excepcional;
4. dos recálculos del mismo `AppointmentTimeSlot`;
5. uniqueness de Inbox bajo delivery concurrente;
6. optimistic version conflict;
7. claim concurrente de `pending_capacity_evaluation`.

La estrategia de lock debe documentarse en código/test.

Criterio:

```text
no oversell
no double-active appointment
no double-consume authorization
no lost update
```

---

## 11. HTTP/operación PR1

El `SystemController` puede retirarse cuando Actuator cubra la operación.

Objetivo:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/prometheus
```

Spring Security niega cualquier ruta HTTP no explícitamente operativa.

Readiness PR1:

```text
PostgreSQL
Flyway/schema
```

No REST de negocio.

---

## 12. Acceptance Criteria PR1

PR1 solo se aprueba si:

- `./mvnw clean verify` pasa;
- Java 25/Spring Boot actual compilan;
- Flyway levanta DB vacía en Testcontainers;
- no existe JPA/Hibernate;
- domain no depende de framework;
- ArchUnit protege las fronteras;
- repositories JDBC tienen integration tests;
- último cupo es seguro bajo carrera;
- misma persona no obtiene dos citas activas concurrentes;
- constraints e optimistic locking están probados;
- `Cabin` projection no posee capacidad configurada propia;
- IMMUNO policy pura tiene tests simétricos;
- primitives de Inbox/Outbox/idempotencia persisten correctamente;
- no existe RabbitMQ ni listeners reales;
- no existen endpoints HTTP de negocio.

---

# PR2 — Complete Application Behavior Without RabbitMQ

## 13. Propósito

Completar comportamiento de aplicación llamando directamente a input ports/use cases, manteniendo el servicio broker-agnostic.

Al finalizar PR2, toda regla funcional de Appointments debe poder probarse contra PostgreSQL real sin RabbitMQ.

PR3 solo adaptará wire contracts y transporte a comportamiento ya probado.

---

## 14. Casos de uso PR2

### Iniciados por actor/cliente

```text
ScheduleAppointment
CancelAppointment
GrantExceptionalAppointmentAuthorization
CancelExceptionalAppointmentAuthorization
```

### Internos de agenda/capacidad

```text
CreateAppointmentTimeSlot
CancelAppointmentTimeSlot
HandleResourceCapacityEvent
RecalculateAppointmentCapacityForTimeSlot
CancelScheduledAppointmentsForCapacityLoss
MarkAppointmentAsMissed
```

### Handlers internos de Wash Execution

```text
HandleWashEntryRejected
HandleWashExecutionStarted
HandleWashExecutionCompleted
HandleWashExecutionCancelled
```

Todos deben entrar por application input ports, nunca directamente a repositories desde adapters.

---

## 15. Pipeline de Command PR2

Para Commands mutantes iniciados por cliente:

```text
validate envelope-independent application model
→ Inbox/idempotency orchestration
→ validate local projections/authorization
→ acquire locks
→ load state
→ evaluate domain/application rules
→ mutate
→ persist audit/result/event Outbox intents
→ commit
```

Una única transacción local cubre los efectos que deben ser atómicos.

Rechazo funcional:

```text
→ Result REJECTED
→ no retry técnico
```

Fallo técnico:

```text
→ rollback
→ PR3 decidirá retry/DLQ del delivery
```

---

## 16. `ScheduleAppointment` PR2

Debe probar:

- actor autorizado;
- acceso a Lavado activo;
- proyección académica confiable;
- NRC/inscripción vigente;
- fecha de servicio válida;
- slot `ACTIVE`;
- dentro de booking deadline;
- tipo/instrumentos/pieceType válidos;
- no cita activa concurrente;
- puntos < 3;
- límite diario de `COMPLETED`;
- autorización excepcional cuando aplique;
- IMMUNO preventive reservation;
- capacidad final revalidada bajo lock;
- consumo atómico de autorización excepcional;
- Appointment + Outbox intents atómicos.

Escenarios obligatorios:

```text
último cupo: 2 requests concurrentes → exactamente 1 éxito
mismo alumno: 2 requests concurrentes → máximo 1 activa
IMMUNO simétrica → slots anteriores/posteriores bloqueados correctamente
```

---

## 17. `CancelAppointment` PR2

Debe validar:

```text
SCHEDULED
student owns appointment
now <= cancellationDeadlineAt
```

Resultado:

```text
CANCELLED + STUDENT
points derivados = 1
autorización excepcional consumida NO se restaura
```

No se acepta cancelación tardía como Command del alumno.

---

## 18. `MarkAppointmentAsMissed` PR2

Aplicable cuando:

```text
Appointment = SCHEDULED
entryAuthorizedAt = null
arrivalToleranceUntil ya terminó
no existe evidencia local de llegada/inicio que la proteja
```

Debe ser race-safe con:

```text
arrival / WashExecutionStarted / entry authorization
```

DoD específico:

```text
si una llegada válida gana la carrera
→ MISSED no puede sobrescribirla
```

---

## 19. Exceptional authorization PR2

`GrantExceptionalAppointmentAuthorization`:

- Supervisor autorizado;
- alumno/fecha válidos;
- no permite superar máximo de dos `COMPLETED`;
- idempotencia funcional;
- motivo/auditoría requeridos por contrato.

`CancelExceptionalAppointmentAuthorization`:

- no produce estados contradictorios con una cita que ya la consumió;
- idempotente.

Restauración por clínica:

```text
Appointment CANCELLED + CLINIC
+
appointment.exceptionalAuthorizationId != null
→ cancel Appointment + restore authorization en misma tx
```

Pruebas obligatorias:

- redelivery del mismo evento clínico;
- dos workers intentando restaurar;
- cancelación student nunca restaura;
- `MISSED`/`ENTRY_REJECTED` nunca restauran.

---

## 20. Capacidad y cambio administrativo PR2

`HandleResourceCapacityEvent` registra el cambio local y genera recálculos internos para slots afectados.

`RecalculateAppointmentCapacityForTimeSlot`:

- bloquea/reclama el slot/projection con estrategia race-safe;
- calcula capacidad física/proyectada;
- corre matching completo;
- decide si requiere cancelaciones;
- genera evento de capacidad correspondiente.

`CancelScheduledAppointmentsForCapacityLoss`:

```text
solo SCHEDULED
entryAuthorizedAt = null
```

Orden de sacrificio:

```text
createdAt DESC
appointmentId DESC
```

Cancelación:

```text
CLINIC / CAPACITY_LOSS
0 puntos
```

Si consumió autorización excepcional:

```text
restore en misma tx
```

No mover citas automáticamente.

Pruebas:

- cierre completo de slot;
- reducción parcial;
- NORMAL/JOURNEY compartiendo fallback;
- IMMUNO;
- entrada autorizada concurrente;
- IN_PROGRESS protegido.

---

## 21. Eventos de Execution PR2

Handlers deben estar implementados sin RabbitMQ real; tests llaman directamente al input port con event application model.

### `HandleWashEntryRejected`

```text
SCHEDULED → ENTRY_REJECTED
```

No puntos.

No restaura autorización excepcional en V1.

### `HandleWashExecutionStarted`

```text
SCHEDULED → IN_PROGRESS
```

Debe proteger contra `MISSED`/cancelación concurrente.

### `HandleWashExecutionCompleted`

```text
IN_PROGRESS → COMPLETED
```

Solo aquí la cita pasa a contar como completada.

Debe resolver evaluaciones de capacidad pendientes causales después de aplicar el estado terminal, en la misma transacción donde corresponda.

### `HandleWashExecutionCancelled`

```text
SCHEDULED|IN_PROGRESS → CANCELLED + CLINIC
```

Propaga metadata clínica canónica.

Si existe autorización excepcional consumida:

```text
restore atómico
```

---

## 22. Out-of-order PR2

Implementar persistencia/dispatcher interno para:

```text
DEFERRED
APPLIED
```

Caso mínimo:

```text
WashExecutionCompleted llega antes que WashExecutionStarted
```

Esperado:

- no saltar directamente de SCHEDULED a COMPLETED;
- no descartar definitivamente;
- persistir DEFERRED;
- aplicar STARTED cuando llegue;
- reprocesar COMPLETED;
- terminar COMPLETED exactamente una vez.

Caso capacidad:

```text
CleaningPeriodStarted antes del evento terminal causal
```

Esperado:

- `pending_capacity_evaluation` durable;
- no cancelar sobrecapacidad fantasma;
- estado terminal local se aplica antes de resolver matching pendiente.

---

## 23. Authorization PR2

Implementar evaluator local puro:

```text
ActorContext
+
AccountRole projection
+
SystemAccess projection
+
matriz estática V1 de permisos
```

No invocar Authorization remotamente.

Proyección ausente/no confiable:

```text
ACTOR_NOT_AUTHORIZED o código canónico equivalente
```

No retry técnico por rechazo funcional.

---

## 24. Outbox/Inbox PR2

Aunque no exista RabbitMQ real, PR2 debe producir modelos internos de mensaje/Outbox para Result/Event.

Atómico:

```text
mutation
+
Inbox/idempotency
+
Outbox Result
+
Outbox Event(s)
```

Pruebas de rollback forzado:

```text
Outbox insert falla
→ Appointment/cancelación/autorización/InBox no quedan committeados parcialmente
```

---

## 25. Acceptance Criteria PR2

PR2 solo se aprueba si:

- todos los casos de uso V1 tienen happy path y rechazo funcional;
- `ScheduleAppointment` es race-safe;
- puntos y bloqueo diario son derivados correctamente;
- una cita no se reprograma;
- `ENTRY_REJECTED` no cuenta ni da punto;
- solo `COMPLETED` consume el límite diario;
- autorización excepcional se consume/restaura exactamente según contrato;
- cambio administrativo cancela solo SCHEDULED elegibles;
- entrada autorizada/IN_PROGRESS quedan protegidos;
- IMMUNO preventive reservation es simétrica;
- eventos Execution fuera de orden convergen;
- `MISSED` pierde contra llegada válida;
- Inbox/idempotency/Outbox son atómicos;
- no existe dependencia AMQP;
- `./mvnw clean verify` pasa.

---

# PR3 — Wire Contracts, RabbitMQ And Projection Recovery

## 26. Propósito

Conectar el comportamiento ya probado a la arquitectura de mensajería de plataforma y completar bootstrap/recovery de proyecciones.

PR3 no debe rediseñar reglas de dominio.

---

## 27. RabbitMQ PR3

Agregar dependencias AMQP y topología canónica:

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

Nunca usar aliases:

```text
appointments-service
commands.appointments-service
results.appointments-service
```

Agregar:

- durable queues;
- bindings;
- retry queues;
- DLQ;
- manual ACK;
- publisher confirms;
- connection/readiness indicators.

---

## 28. Wire contracts PR3

Crear DTOs/contracts versionados separados del dominio.

Como mínimo:

```text
CommandEnvelope / platform envelope mapping
Command payload DTOs
Result DTOs
Appointments-produced Event DTOs
consumed Academic Events
consumed Authorization Events/Results
consumed Wash Administration Events/Results
consumed Wash Execution Events/Results
Query DTOs ofrecidos a Wash Execution
```

Idealmente incluir:

```text
JSON Schema
AsyncAPI
```

si esa es la convención ya adoptada por servicios maduros del repo.

Validación wire antes de mapear a Application:

- campos obligatorios;
- enums;
- UUIDs;
- timestamps;
- versiones;
- actor/envelope;
- no permitir primitives missing que se conviertan silenciosamente a `0`/false.

---

## 29. Commands listener PR3

Listener:

```text
Rabbit wire
→ validate
→ map to application input model
→ application use case
→ commit
→ ACK
```

Listener no contiene reglas de negocio.

Fallos:

```text
functional rejection
→ Result REJECTED durable
→ ACK

transient technical
→ retry

permanent invalid wire
→ DLQ/quarantine according to contract
```

---

## 30. Event consumers PR3

### Academic

Consumir exclusivamente eventos V1 actuales.

Pruebas explícitas para rechazar/no bindear contratos obsoletos:

```text
AcademicPeriodRegistered
StudentAcademicProfileUpdated
StudentAcademicStatusChanged
```

### Authorization

Mantener role/access projection monotónica.

### Wash Administration

Mantener:

```text
ServiceCalendar
ServiceSchedule
AdministrativeClosure
Cabin
Tank
ResourcePriority
```

### Wash Execution

Mantener:

```text
ResourceUnavailability
CleaningPeriod
WashExecution state
```

más los cuatro eventos propietarios de transición de Appointment.

Todos:

- Inbox;
- source version monotonicity;
- stale/duplicate safe;
- out-of-order durable cuando causalmente necesario.

---

## 31. Recovery PR3

### Academic

Implementar los Queries/Results autoritativos vigentes necesarios para reconstruir:

```text
academic period
student academic profile
course sections/enrollments
```

No inventar nombres: revisar contrato Academic en `main` al comenzar PR3.

### Authorization

Implementar projection recovery canónico de:

```text
AccountRole
SystemAccess
```

### Wash Administration

```text
GetWashAdministrationConfiguration
```

### Wash Execution

Implementar los snapshots/queries vigentes definidos por su contrato canónico para reconstruir estado operativo/capacidad.

### Appointments como owner de Query

Implementar:

```text
GetAppointmentExecutionContext
ListAppointmentExecutionContextsPage
```

con requester allowlist:

```text
wash-execution
```

---

## 32. Recovery state machine PR3

Cada familia de proyección crítica debe tener estado durable, por ejemplo:

```text
UNINITIALIZED
REBUILDING
READY
DEGRADED
```

El nombre exacto puede seguir la convención común del repo.

Reglas:

- startup con proyección vacía dispara bootstrap;
- Events continúan llegando durante rebuild;
- snapshot/event race converge por source version;
- no marcar READY antes de terminar reconciliación;
- fallo persistente marca estado no confiable;
- Commands dependientes fallan cerrado.

RabbitMQ no es Event Store.

---

## 33. Outbox Relay PR3

Implementar relay real:

- multi-replica safe;
- claim/fencing;
- publisher confirm;
- retry/backoff;
- no finite transient-to-FAILED automático;
- permanent invalid contract puede quedar FAILED/quarantine;
- restart safe;
- métricas.

Pruebas:

```text
broker caído después del commit
→ Outbox permanece pendiente
→ broker vuelve
→ publica exactamente el desenlace lógico esperado
```

---

## 34. QR PR3

Implementar protección criptográfica elegida.

Requisitos:

- tamper detection;
- secret/key por configuración segura;
- no secreto en repo;
- test de token manipulado;
- test de appointmentId distinto;
- test de QR criptográficamente válido pero Appointment final/no utilizable;
- no usar QR como autorización de entrada.

---

## 35. Testing PR3

RabbitMQ Testcontainers obligatorio.

Cubrir:

- Command → Result + Event;
- duplicate Command delivery;
- idempotency conflict;
- functional rejection no retry;
- transient failure retry;
- DLQ;
- publisher confirms;
- Outbox recovery/restart;
- duplicate/stale Event;
- out-of-order Execution Events;
- projection FULL/ENTITY recovery;
- snapshot + concurrent Event;
- requester allowlist de Queries;
- readiness down durante rebuild.

---

## 36. Acceptance Criteria PR3

PR3 solo se aprueba si:

- topología usa exclusivamente `appointments`;
- listeners son adapters delgados;
- DTO wire no contaminan domain;
- Commands funcionan E2E con RabbitMQ real de Testcontainers;
- Result/Event salen por Outbox Relay confirmado;
- retry/DLQ están probados;
- proyecciones convergen por versión;
- recovery no usa Event Store;
- Appointments puede responder Queries a Execution;
- QR es tamper-resistant;
- readiness refleja rebuild/degraded;
- `./mvnw clean verify` pasa.

---

# PR4 — Bounded-context E2E And Failure Hardening

## 37. Propósito

Demostrar que Appointments funciona dentro del bounded context real de Lavado y que las garantías sobreviven fallos distribuidos.

No agregar nuevas reglas de negocio salvo corrección de bug/contradicción previamente documentada.

---

## 38. Entorno mínimo PR4

```text
appointments-service
wash-administration-service
wash-execution-service
RabbitMQ
PostgreSQL aislado por servicio
```

Agregar Academic/Authorization reales al E2E cuando sea viable; como mínimo sus contratos/proyecciones deben estar representados de forma contractual fiel.

---

## 39. Escenarios funcionales E2E obligatorios

1. configuración semanal → materialización de slots → disponibilidad;
2. registrar NORMAL con capacidad preferida;
3. NORMAL usando JOURNEY como alternativa;
4. JOURNEY usando fallback NORMAL;
5. IMMUNO con reserva preventiva simétrica;
6. cancelación STUDENT → punto + sin restauración excepcional;
7. MISSED → punto;
8. ENTRY_REJECTED → sin punto y libera reserva IMMUNO si aplica;
9. COMPLETED → cuenta para límite diario;
10. segunda cita con autorización excepcional;
11. cancelación clínica de segunda cita → autorización restaurada;
12. cambio de horario semanal en curso → slots/capacidad recalculados;
13. reducción de capacidad → cancelación de citas más recientes;
14. cita con entrada autorizada protegida de cancelación automática;
15. cita IN_PROGRESS protegida de cambio administrativo;
16. CleaningPeriod real cambia capacidad futura;
17. CleaningPeriodStarted fuera de orden respecto al terminal causal;
18. WashExecutionCompleted fuera de orden respecto a Started;
19. última capacidad bajo carrera;
20. MISSED vs llegada válida bajo carrera.

---

## 40. Escenarios de recovery/fallo PR4

Probar al menos:

- restart de Appointments con Outbox pendiente;
- RabbitMQ temporalmente caído;
- duplicate redelivery después de restart;
- perder/recrear projection Academic;
- perder/recrear projection Authorization;
- perder/recrear projection Wash Administration;
- perder/recrear projection Wash Execution;
- Events llegando durante FULL rebuild;
- stale snapshot/result;
- DLQ de mensaje permanentemente inválido;
- múltiples réplicas reclamando Outbox;
- múltiples réplicas recalculando el mismo slot.

---

## 41. Escenario semanal administrativo

Validar explícitamente:

```text
ServiceCalendar W35
TUE 08:00-17:00
→ Appointments materializa slots
→ alumnos reservan

Admin cambia TUE a 10:00-14:00
→ ServiceScheduleUpdated
→ Appointments recalcula
→ slots fuera de rango dejan de ser reservables
→ SCHEDULED afectados se cancelan CLINIC/CAPACITY_LOSS
→ no auto-reschedule
→ autorizaciones excepcionales clínicas se restauran
→ entrada autorizada/IN_PROGRESS no se cancelan
```

La UI preview no es precondición autoritativa; backend recalcula estado real.

---

## 42. Definition of Done PR4

PR4 solo se aprueba si:

- escenarios funcionales críticos pasan;
- recovery de cada proyección crítica converge;
- no existe sobrecupo bajo pruebas concurrentes;
- no existe doble cita activa del mismo alumno;
- no existe doble consumo/restauración de autorización;
- no se pierde Result/Event tras fallo del broker;
- no se requiere HTTP/gRPC interno de negocio;
- Appointments + Administration + Execution convergen tras out-of-order/redelivery;
- observabilidad permite identificar backlog, retries, recovery y capacidad perdida;
- `./mvnw clean verify` pasa.

---

# 43. Checklist global de arquitectura hexagonal

Antes de mergear cualquiera de los PRs:

```text
[ ] domain no importa Spring
[ ] domain no importa JDBC
[ ] domain no importa AMQP
[ ] domain no conoce wire DTOs
[ ] application no importa infrastructure adapters
[ ] input adapters solo mapean + llaman ports
[ ] output adapters implementan ports
[ ] SQL vive en infrastructure.persistence
[ ] RabbitMQ vive en infrastructure.messaging
[ ] contratos wire viven separados del dominio
[ ] tests ArchUnit siguen verdes
```

---

# 44. Checklist global de datos/consistencia

```text
[ ] sin FK cross-service
[ ] Flyway desde DB vacía
[ ] constraints race-safe
[ ] optimistic/conditional locking probado
[ ] Inbox durable
[ ] idempotency durable
[ ] Outbox atómico
[ ] Outbox relay restart-safe
[ ] source versions monotónicas
[ ] deferred event processing durable
[ ] recovery state durable
[ ] readiness fail-closed
```

---

# 45. Checklist global de negocio

```text
[ ] no rescheduling
[ ] no point balance persistido independiente
[ ] solo COMPLETED cuenta para límite diario
[ ] cancel STUDENT = 1 punto
[ ] MISSED = 1 punto
[ ] CLINIC = 0 puntos
[ ] ENTRY_REJECTED = 0 puntos
[ ] authorization CLINIC restores
[ ] authorization STUDENT/MISSED/ENTRY_REJECTED does not restore
[ ] Tank.configuredCapacity es fuente física
[ ] Cabin no tiene capacidad propia
[ ] NORMAL/JOURNEY matching compartido
[ ] IMMUNO reservation simétrica +3h
[ ] admin change puede cancelar SCHEDULED
[ ] entry-authorized/IN_PROGRESS protegidos
[ ] Execution events no saltan estados por out-of-order
```

---

# 46. Definition of Done V1

`appointments-service` se considera implementado V1 únicamente cuando se cumplen conjuntamente:

```text
PR1 domain/persistence/concurrency ✅
PR2 application behavior ✅
PR3 messaging/recovery ✅
PR4 bounded-context E2E ✅
```

Y además:

- no quedan contradicciones BLOCKER/HIGH frente a la documentación canónica;
- todos los gates arquitectónicos tienen tests automatizados donde sea razonable;
- los escenarios de concurrencia y recovery no dependen de pruebas manuales solamente;
- no existen shortcuts de comunicación síncrona no documentados;
- no existen aliases RabbitMQ `*-service`;
- no se inventaron decisiones diferidas fuera de V1.
