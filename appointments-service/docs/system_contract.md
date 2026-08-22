# Appointments Service — System Contract V1

**Ruta:** `appointments-service/docs/system_contract.md`  
**Estado:** contrato local de implementación V1  
**Logical Component ID:** `appointments`

## 1. Propósito

Este documento traduce la documentación canónica de plataforma a un contrato directamente implementable para `appointments-service`.

No sustituye la documentación canónica. Si existe drift, prevalece `AnthonyNav/estomatologia-platform-docs/main`, en particular el commit canónico que cerró Appointments V1 (`dedd907a6f289737f2ed2668cc2a4d2fc8cf641f`) o cualquier versión posterior de `main`.

Fuentes principales:

- `01-domain/lavado-business.md`;
- `02-architecture/messaging-architecture.md`;
- `02-architecture/rabbitmq-topology-naming.md`;
- `02-architecture/data-consistency-architecture.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/README.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/v1-contract-gate.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/data-model.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/commands.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/events.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/interfaces.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/queries.md`;
- `03-services/domains/lavado-ultrasonico/appointments-service/wash-execution-integration.md`;
- `03-services/domains/lavado-ultrasonico/wash-administration-service/scheduling-model.md`;
- contratos vigentes de `academic-service`, `authorization-service`, `wash-administration-service` y `wash-execution-service`.

El documento canónico `v1-contract-gate.md` prevalece ante fragmentos históricos todavía presentes en documentos de Appointments. No se debe copiar al código nomenclatura o reglas marcadas como obsoletas por ese gate.

Antes de iniciar cada PR de implementación se debe verificar si la documentación canónica cambió desde la referencia anterior. Si aparece una contradicción que cambia comportamiento V1, se corrige primero la documentación canónica.

---

## 2. Estado actual del scaffold

En `develop`, `appointments-service` es todavía un scaffold:

- Spring Boot `4.1.0`;
- Java `25`;
- Maven Wrapper;
- Actuator, WebMVC y Prometheus;
- `SystemController` temporal;
- sin PostgreSQL funcional;
- sin Flyway;
- sin Spring JDBC/JdbcClient;
- sin RabbitMQ;
- sin dominio implementado;
- sin persistencia funcional;
- sin proyecciones externas reales;
- sin Authorization local;
- sin Consumer Inbox / Transactional Outbox reales.

El `README.md` actual contiene ownership histórico incorrecto —por ejemplo rescheduling, QR como estado propio y balance persistido de puntos— y debe quedar reemplazado por el contrato V1 de este documento y la documentación canónica.

---

## 3. Arquitectura obligatoria

La implementación V1 usa arquitectura hexagonal.

Package root objetivo:

```text
com.estoma.appointments
├── domain
│   ├── model
│   ├── service
│   └── error
├── application
│   ├── port
│   │   ├── in
│   │   └── out
│   ├── usecase
│   └── model
├── infrastructure
│   ├── persistence
│   ├── messaging
│   ├── scheduling
│   ├── security
│   ├── observability
│   └── config
└── contracts
    ├── command
    ├── event
    ├── query
    └── result
```

Reglas de dependencia:

```text
domain
→ no depende de Spring, JDBC, RabbitMQ, Jackson ni infrastructure

application
→ depende de domain y de ports
→ no depende de adapters concretos de infrastructure

infrastructure
→ implementa ports de application
→ puede depender de Spring/JDBC/RabbitMQ/Jackson

contracts
→ contiene modelos wire/versionados
→ no contiene lógica de negocio
```

Se debe proteger esta dirección mediante ArchUnit.

No se acepta:

- lógica de dominio dentro de listeners RabbitMQ;
- repositorios Spring/JDBC dentro de `domain`;
- DTOs wire usados como entidades de dominio;
- acceso directo a bases de otros servicios;
- HTTP/gRPC de negocio para saltarse RabbitMQ;
- un service class central que concentre dominio, SQL, transporte y mapping.

---

## 4. Ownership V1

`appointments-service` es fuente oficial de:

```text
AppointmentTimeSlot
Appointment
ExceptionalAppointmentAuthorization
```

También mantiene estado técnico local:

```text
Consumer Inbox
functional idempotency
Transactional Outbox
projection recovery state
deferred/out-of-order event processing
pending_capacity_evaluation
```

Y proyecciones locales mínimas de:

```text
Academic
Authorization
Wash Administration
Wash Execution
```

No es propietario de:

```text
ServiceCalendar
ServiceSchedule
AdministrativeClosure
Cabin
Tank
ResourcePriority
WashExecution
ResourceAssignment
ResourceUnavailability
CleaningPeriod
AcademicPeriod
CourseSection
AccountRole
SystemAccess
Persona / Account / Session
communications
```

No existen FKs hacia bases externas.

---

## 5. Aggregate roots y modelo funcional

Para V1 se tratarán como agregados funcionales versionados independientes:

```text
AppointmentTimeSlot
Appointment
ExceptionalAppointmentAuthorization
```

Las invariantes que involucran varios agregados se coordinan desde Application dentro de una transacción local y con locks/constraints explícitos cuando exista carrera.

### 5.1 `AppointmentTimeSlot`

Representa un bloque concreto reservable.

No representa:

- cabina;
- tina;
- capacidad persistida disponible;
- horario recurrente.

Debe conservar al menos:

```text
appointmentTimeSlotId
sourceScheduleId
serviceDate
startsAt
endsAt
timezone
bookingDeadlineAt
cancellationDeadlineAt
arrivalToleranceUntil
status
version
```

Estados V1:

```text
ACTIVE
CANCELLED
```

La capacidad utilizable se calcula/proyecta aparte.

Un bloque materializado no es un snapshot intocable. Cambios administrativos confirmados pueden provocar recálculo/cancelación futura, pero nunca escrituras cross-service desde Administration.

### 5.2 `Appointment`

Estados V1:

```text
SCHEDULED
CANCELLED
MISSED
ENTRY_REJECTED
IN_PROGRESS
COMPLETED
```

Estados finales:

```text
CANCELLED
MISSED
ENTRY_REJECTED
COMPLETED
```

No existe `INTERRUPTED`.

Una cita no se edita ni se reprograma:

```text
cambiar datos/horario
→ cancelar
→ registrar nueva cita
```

Debe conservar como mínimo:

```text
appointmentId
appointmentTimeSlotId
studentAccountId
courseSectionId
appointmentType
instrumentCount
pieceType
status
entryAuthorizedAt?
exceptionalAuthorizationId?
cancellationType?
cancellationSubreason?
createdAt
updatedAt
version
```

### 5.3 `ExceptionalAppointmentAuthorization`

Aplica a alumno + fecha de servicio y permite una cita adicional cuando la regla normal ya fue consumida.

No requiere `status` persistido si su condición puede derivarse de sus campos y asociación con la cita.

Regla cerrada:

```text
cita adicional registrada
→ autorización consumida

esa cita CANCELLED + CLINIC
→ autorización restaurada

esa cita CANCELLED + STUDENT
→ autorización NO restaurada

MISSED / ENTRY_REJECTED
→ autorización NO restaurada en V1
```

La restauración por clínica debe ser atómica con la cancelación y resistente a redelivery/concurrencia.

---

## 6. Tipos de cita y reglas de registro

Tipos:

```text
NORMAL
JOURNEY
IMMUNOCOMPROMISED
```

`instrumentCount`:

```text
NORMAL             → 1..40
JOURNEY            → 1..80
IMMUNOCOMPROMISED  → 1..40
```

`pieceType`:

```text
HIGH_SPEED
LOW_SPEED
CONTRA_ANGLE
```

Reglas obligatorias:

- solo se agenda para la fecha de servicio permitida por el contrato V1;
- un alumno no puede tener dos citas activas simultáneamente;
- solo `COMPLETED` consume el límite diario normal de citas finalizadas;
- con una cita `COMPLETED`, una cita adicional requiere autorización excepcional;
- máximo confirmado: dos citas `COMPLETED` por alumno/fecha;
- 3 o más puntos derivados durante la fecha bloquean un nuevo registro;
- el NRC/`CourseSection` debe estar respaldado por proyección académica utilizable;
- rol/acceso requerido debe demostrarse localmente; ante proyección no confiable se falla cerrado.

---

## 7. Puntos y bloqueo

No existe una entidad `PointBalance`, `PenaltyPoint` ni contador diario persistido como fuente independiente.

Los puntos se derivan de citas:

```text
MISSED               → 1
CANCELLED + STUDENT  → 1
CANCELLED + CLINIC   → 0
ENTRY_REJECTED       → 0
resto                → 0
```

Con `>= 3` puntos durante la fecha de servicio:

```text
ScheduleAppointment
→ rechazado
```

El conteo vuelve a cero de forma derivada en la siguiente fecha de servicio.

Historial/corrección manual de puntos no bloquea V1.

---

## 8. Capacidad de agenda

Fuente física configurada:

```text
Tank.configuredCapacity
```

No existe:

```text
Cabin.configuredCapacity
```

Cada Appointment activa consume una unidad de demanda independientemente de `instrumentCount`.

Por bloque se derivan:

```text
C_NORMAL
C_JOURNEY
C_IMMUNOCOMPROMISED

D_NORMAL
D_JOURNEY
D_IMMUNOCOMPROMISED
```

Estados que reservan demanda:

```text
SCHEDULED
IN_PROGRESS
```

La capacidad utilizable considera proyecciones de:

- Cabin/Tank y estado administrativo;
- `Tank.configuredCapacity`;
- `AdministrativeClosure`;
- `ResourceUnavailability`;
- `CleaningPeriod`;
- reserva preventiva IMMUNO;
- `ResourcePriority`/compatibilidad.

Compatibilidad:

```text
NORMAL
1. NORMAL preferida
2. JOURNEY alternativa

JOURNEY
1. JOURNEY preferida
2. NORMAL alternativa

IMMUNOCOMPROMISED
1. IMMUNOCOMPROMISED exclusiva
```

`NORMAL` y `JOURNEY` comparten capacidad alternativa; no se modelan como depósitos sumables independientes. La disponibilidad se decide mediante matching determinista.

---

## 9. Reserva preventiva IMMUNOCOMPROMISED

Una cita `IMMUNOCOMPROMISED` activa reserva preventivamente:

```text
[slot.startsAt, slot.endsAt + 3h)
```

La validación debe ser simétrica:

- impedir una cita posterior dentro de la limpieza esperada;
- impedir una cita anterior cuya propia limpieza esperada alcance a una IMMUNO ya registrada.

La reserva preventiva:

- pertenece a Appointments;
- no es un `CleaningPeriod` físico;
- se deriva de las citas activas;
- desaparece si la cita termina antes de uso físico en `CANCELLED`, `MISSED` o `ENTRY_REJECTED`;
- debe participar en recálculos de capacidad.

---

## 10. Cambios administrativos y pérdida de capacidad

Wash Administration publica hechos; Appointments aplica consecuencias locales.

Flujo:

```text
Administration confirma cambio
→ Event
→ Appointments actualiza proyección
→ identifica slots futuros afectados
→ recalcula capacidad/existencia
→ cancela SCHEDULED si hace falta
```

Cancelación por pérdida de capacidad:

```text
status = CANCELLED
cancellationType = CLINIC
cancellationSubreason = CAPACITY_LOSS
points = 0
```

Si solo debe reducirse demanda, se cancelan primero las citas más recientes:

```text
createdAt DESC
appointmentId DESC
```

Nunca se reprograma automáticamente.

No participan en cancelación automática:

```text
SCHEDULED con entryAuthorizedAt != null
IN_PROGRESS
```

Una ejecución física ya activa se resuelve en `wash-execution-service`.

Si la cita cancelada por clínica consumió una autorización excepcional, la restauración se realiza en la misma transacción local.

---

## 11. Integración con Wash Execution

Eventos físicos consumidos y transición local propietaria:

```text
WashEntryRejected
→ HandleWashEntryRejected
→ SCHEDULED → ENTRY_REJECTED

WashExecutionStarted
→ HandleWashExecutionStarted
→ SCHEDULED → IN_PROGRESS

WashExecutionCompleted
→ HandleWashExecutionCompleted
→ IN_PROGRESS → COMPLETED

WashExecutionCancelled
→ HandleWashExecutionCancelled
→ SCHEDULED|IN_PROGRESS → CANCELLED + CLINIC
```

Cada evento debe tener un único handler propietario dentro del servicio.

Los handlers de Execution:

- no reciben `Appointment.expectedVersion` del productor;
- deduplican por `sourceEventId`/`messageId` según contrato wire;
- comparan `executionVersion` con `wash_execution_state_projection.source_version`;
- usan transición condicional/locking local;
- incrementan `Appointment.version` como efecto local;
- difieren de forma durable un evento cuya transición causal todavía no sea aplicable.

Mientras Execution esté `PENDING_REASSIGNMENT` después de entrada autorizada:

```text
Appointment.status = SCHEDULED
entryAuthorizedAt != null
```

Esto protege contra `MISSED` y cancelación automática por cambios administrativos.

---

## 12. Eventos fuera de orden y evaluación diferida

RabbitMQ no garantiza orden global.

Caso crítico:

```text
CleaningPeriodStarted
puede llegar antes de
WashExecutionCompleted / WashExecutionCancelled
```

No se debe cancelar una cita adicional de más mientras la Appointment causal todavía figure activa localmente.

`pending_capacity_evaluation` debe permitir:

- registrar evaluación pendiente de manera durable;
- vincularla al evento/ejecución causal;
- resolverla cuando la transición terminal de Appointment se confirme;
- sobrevivir reinicios;
- ser idempotente;
- no dejar filas huérfanas bajo carrera.

Consumer Inbox para eventos diferibles debe distinguir al menos:

```text
DEFERRED
APPLIED
```

Registrar delivery no equivale a haber aplicado el efecto.

---

## 13. Academic projection

Contratos V1 relevantes:

```text
StudentAcademicProfileCreated
StudentAcademicProfileActivated
StudentAcademicProfileDeactivated
StudentSemesterChanged
AcademicPeriodActivated
AcademicPeriodClosed
CourseSectionRegistered
CourseSectionUpdated
CourseSectionStatusChanged
StudentEnrolledInCourseSection
StudentEnrollmentReactivated
StudentRemovedFromCourseSection
```

No implementar como vigentes:

```text
StudentAcademicProfileUpdated
StudentAcademicStatusChanged
AcademicPeriodRegistered
```

Proyecciones mínimas deben separar versiones por identidad/stream propietario; no inventar una versión global del estudiante.

Recovery/bootstrap debe usar los contratos autoritativos vigentes publicados por `academic-service`.

Ante proyección necesaria ausente/no confiable:

```text
ScheduleAppointment
→ fail closed
```

No fallback HTTP/gRPC.

---

## 14. Authorization projection

Mantener localmente lo mínimo para demostrar:

```text
AccountRole
SystemAccess(LAVADO_ULTRASONICO)
```

Eventos y recovery deben seguir el contrato canónico vigente de Authorization.

No existe `ValidateAccess` remoto en la ruta del Command.

Un actor no demostrado como autorizado produce rechazo funcional, no retry técnico.

---

## 15. Wash Administration projection y recovery

Appointments mantiene proyecciones mínimas de:

```text
ServiceCalendar
ServiceSchedule
AdministrativeClosure
Cabin
Tank
ResourcePriority
```

Recovery autoritativo disponible:

```text
GetWashAdministrationConfiguration
```

Uso:

- bootstrap de DB/proyección vacía;
- rebuild;
- reconciliación explícita;
- reparación tras gap/corrupción.

Events siguen siendo el canal incremental normal.

---

## 16. Wash Execution projection y recovery

Appointments mantiene el mínimo necesario para:

```text
ResourceUnavailability
CleaningPeriod
wash_execution_state_projection
```

Debe implementar el recovery/reconciliation definido por los contratos canónicos de Execution y no depender de RabbitMQ como Event Store.

Cuando no exista evidencia confiable para una decisión que requiere estado operativo, la operación debe fallar o diferirse de forma segura; nunca asumir disponibilidad.

---

## 17. Queries autoritativas ofrecidas a Execution

Appointments expone por RabbitMQ:

```text
GetAppointmentExecutionContext
ListAppointmentExecutionContextsPage
```

Requester V1 permitido:

```text
wash-execution
```

Uso:

- ENTITY recovery;
- FULL/rebuild operativo por `serviceDate`.

No son endpoints para cliente ni mecanismo normal de lectura UI.

Queries son side-effect-free y responden mediante `platform.results` hacia `results.wash-execution`.

---

## 18. QR V1

El QR es una representación protegida de `appointmentId`.

No es:

- entidad;
- tabla;
- autoridad de entrada;
- validación de identidad;
- lifecycle independiente.

La protección técnica debe impedir que un cliente modifique libremente el identificador y produzca una referencia válida a otra cita.

La elección concreta —por ejemplo firma/HMAC o token firmado equivalente— se define en implementación, con estas restricciones:

- integridad criptográfica;
- sin datos sensibles innecesarios;
- rotación/configuración de secreto o clave fuera del repo;
- validación funcional siempre contra el estado actual de Appointment;
- una firma válida no autoriza entrada por sí sola.

---

## 19. Commands y casos de uso V1

Casos iniciados por cliente/actor según contrato canónico:

```text
ScheduleAppointment
CancelAppointment
GrantExceptionalAppointmentAuthorization
CancelExceptionalAppointmentAuthorization
```

Casos internos de agenda/capacidad:

```text
CreateAppointmentTimeSlot
CancelAppointmentTimeSlot
HandleResourceCapacityEvent
RecalculateAppointmentCapacityForTimeSlot
CancelScheduledAppointmentsForCapacityLoss
MarkAppointmentAsMissed
```

Handlers de eventos de Execution:

```text
HandleWashEntryRejected
HandleWashExecutionStarted
HandleWashExecutionCompleted
HandleWashExecutionCancelled
```

La implementación debe revisar `commands.md` canónico antes de codificar payload exacto de cada operación. Ante nombres de cola `*-service` o eventos Academic obsoletos presentes en secciones históricas, usar las correcciones de `v1-contract-gate.md`.

---

## 20. Mensajería V1

Identidad lógica:

```text
appointments
```

Topología:

```text
platform.commands
platform.events
platform.queries
platform.results
platform.dlx

commands.appointments
events.appointments
queries.appointments
results.appointments
```

No usar:

```text
commands.appointments-service
events.appointments-service
queries.appointments-service
results.appointments-service
```

Commands de cliente:

```text
Client
→ BFF
→ platform.commands
→ commands.appointments
→ application input port
→ local transaction
→ Outbox Result/Event
```

Reglas:

- manual ACK después de commit local exitoso;
- retry/DLQ para fallo técnico;
- rechazo funcional genera Result terminal y ACK, no retry;
- publisher confirms en Outbox Relay;
- no marcar Outbox publicado antes del confirm;
- Consumer Inbox por `messageId` para Commands/Events mutantes;
- `idempotencyKey` para intención funcional de Commands;
- `operationId` para seguimiento de Commands/Queries cuando aplique;
- no loggear payloads completos con datos innecesarios;
- RabbitMQ no es Event Store.

---

## 21. Persistencia V1

Tecnología esperada:

```text
PostgreSQL
Flyway
Spring JDBC / JdbcClient
```

No JPA/Hibernate salvo decisión explícita futura y consistente con el resto del bounded context.

Tablas funcionales mínimas:

```text
appointment_time_slots
appointments
exceptional_appointment_authorizations
appointment_capacity_projections
```

Proyecciones mínimas — nombres físicos pueden variar si conservan semántica:

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

Infraestructura durable:

```text
processed_messages
command_idempotency_records
outbox_messages
```

No FKs cross-database.

---

## 22. Constraints e índices obligatorios

El diseño físico debe proteger como mínimo:

- unicidad de `AppointmentTimeSlot` equivalente para schedule/fecha/intervalo;
- una cita activa simultánea por alumno;
- asociación válida de Appointment con slot;
- `version > 0`;
- `instrumentCount > 0`;
- consistencia de cancelación `cancellationType/cancellationSubreason`;
- una autorización excepcional compatible por alumno/fecha según reglas canónicas;
- idempotencia/uniqueness de Inbox;
- uniqueness/fingerprint de idempotency records;
- Outbox polling eficiente;
- uniqueness de proyecciones por source identity;
- uniqueness/claim seguro de `pending_capacity_evaluation`.

Índices mínimos:

```text
appointments(student_account_id, status)
appointments(appointment_time_slot_id, status)
appointments(student_account_id, service_date derivable/index strategy)
appointments(appointment_time_slot_id, created_at desc, appointment_id desc)
exceptional_authorizations(student_account_id, service_date)
outbox(status, next_attempt_at, created_at)
processed_messages(message_id)
projection tables(source id/version)
pending_capacity_evaluation(status / causal execution)
```

No se exige un nombre SQL exacto si el constraint demuestra la misma garantía.

---

## 23. Concurrencia obligatoria

No se acepta `read → validate → write` sin protección transaccional equivalente.

Debe existir estrategia explícita de locks/constraints/conditional updates para:

1. dos alumnos intentando el último cupo;
2. dos citas concurrentes del mismo alumno;
3. `ScheduleAppointment` concurrente con cambio/pérdida de capacidad;
4. dos recálculos del mismo slot;
5. cancelación automática concurrente con entrada autorizada;
6. `MarkAppointmentAsMissed` concurrente con llegada/inicio;
7. evento terminal de Execution concurrente con evaluación pendiente;
8. consumo/restauración de autorización excepcional;
9. redelivery del mismo Command/Event;
10. snapshot/recovery concurrente con Events incrementales.

El orden de locks debe ser estable y documentado para evitar deadlocks.

---

## 24. Idempotencia y atomicidad

Para Command iniciado por cliente, una única transacción local debe cubrir según aplique:

```text
Inbox
functional idempotency
locks/read model local
mutation
restauración/consumo de autorización
Outbox Result
Outbox Event(s)
```

Mismo `messageId`:

```text
→ no repite efecto
```

Misma `idempotencyKey` + misma intención/fingerprint:

```text
→ no repite mutación/evento
→ devuelve/reconstruye el mismo desenlace lógico
```

Misma `idempotencyKey` + fingerprint distinto:

```text
→ rechazo de conflicto de idempotencia
```

Un fallo al persistir Outbox debe hacer rollback del cambio funcional correspondiente.

---

## 25. Outbox Relay

Debe soportar:

- múltiples réplicas sin doble claim inseguro;
- fencing/lease o estrategia equivalente;
- publisher confirms;
- backoff para fallos transitorios;
- cuarentena/FAILED únicamente para contrato permanentemente inválido;
- recovery tras restart;
- métricas de pending, oldest age, retries y permanent failures.

No se pierde un Event/Result porque el broker esté temporalmente caído después del commit local.

---

## 26. Readiness y observabilidad

Endpoints técnicos objetivo:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/prometheus
```

Los endpoints scaffold `/health` y `/version` pueden retirarse cuando Actuator cubra su responsabilidad.

Readiness completa V1 considera:

```text
PostgreSQL reachable
Flyway/schema compatible
RabbitMQ/topology disponible
Outbox publisher operativo
consumidores necesarios levantados
proyecciones críticas READY/confiables
```

Una instancia no debe declararse lista para procesar Commands dependientes de una proyección que esté `REBUILDING`, `DEGRADED` o no confiable.

Métricas mínimas:

```text
command/result latency
functional rejections by code
outbox pending/oldest age/retries
consumer retry/dlq
deferred event count/age
projection readiness/recovery
capacity recalculation latency
appointments cancelled by capacity loss
missed marking failures
idempotency conflicts
```

---

## 27. Testing mínimo contractual

La suite debe incluir:

### Unit/domain

- estados/transiciones de Appointment;
- reglas de puntos;
- límites de tipos/instrumentos;
- autorización excepcional;
- IMMUNO preventive reservation;
- matching/capacity pure logic;
- invariantes de tiempo.

### PostgreSQL/Testcontainers

- migraciones desde DB vacía;
- repositories JDBC;
- optimistic/conditional locking;
- constraints;
- transacciones y rollback con Outbox;
- carreras críticas.

### RabbitMQ/Testcontainers

- Command → Result/Event;
- duplicate delivery;
- retry/DLQ;
- publisher confirm;
- Outbox Relay restart;
- stale/out-of-order events;
- recovery snapshot + Events concurrentes.

### E2E bounded context

- Appointments + Wash Administration + Wash Execution;
- Academic/Authorization projections o productores reales cuando el entorno E2E lo permita;
- RabbitMQ;
- PostgreSQL aislado por servicio.

---

## 28. Seguridad

- no secretos en repo;
- QR signing secret/key mediante configuración segura;
- no JWT/credentials persistidos en Inbox/Outbox;
- no PII innecesaria en Events;
- actors no se aceptan como IDs arbitrarios en payload cuando el envelope canónico provea `ActorContext`;
- Authorization se demuestra localmente mediante proyecciones;
- Queries service-to-service solo aceptan requesters allowlisted;
- endpoints HTTP de negocio no se exponen.

---

## 29. Decisiones diferidas que no bloquean V1

No inventar durante implementación:

- ownership/versionado futuro del reglamento;
- mecanismo físico exacto de validación de identidad;
- UX exacta de búsqueda manual;
- umbral futuro `instrumentCount` vs materiales de salida;
- comunicaciones adicionales;
- historial/corrección manual de puntos;
- submotivos clínicos adicionales;
- reportes/alertas futuras.

---

## 30. Definition of Done del servicio V1

Appointments V1 no se considera completo hasta demostrar:

- arquitectura hexagonal protegida por ArchUnit;
- dominio sin dependencias de framework;
- PostgreSQL/Flyway/JdbcClient funcional;
- cero JPA/Hibernate;
- Commands idempotentes;
- Inbox/Outbox atómicos;
- último cupo seguro bajo concurrencia;
- no dos citas activas concurrentes del mismo alumno;
- pérdida de capacidad cancela exactamente las citas necesarias y nunca una atención ya autorizada;
- restauración de autorización excepcional correcta y atómica por `CLINIC`;
- IMMUNO preventive reservation simétrica;
- Events de Execution fuera de orden convergen sin saltos inválidos;
- `MISSED` no gana una carrera contra llegada válida;
- proyecciones pueden reconstruirse sin usar RabbitMQ como Event Store;
- Queries de recovery para Execution funcionan;
- RabbitMQ usa `logicalComponentId = appointments` sin aliases `*-service`;
- readiness refleja estado real de dependencias/proyecciones;
- escenarios E2E críticos pasan;
- `./mvnw clean verify` verde en cada PR implementador;
- no quedan contradicciones BLOCKER/HIGH conocidas frente a documentación canónica.
