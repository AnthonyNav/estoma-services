# Appointments Service — System Contract V1

**Ruta:** `appointments-service/docs/system_contract.md`  
**Estado:** contrato local de implementación V1  
**Logical Component ID:** `appointments`

## 1. Propósito y fuente de verdad

Este documento traduce la documentación canónica de plataforma a un contrato directamente implementable para `appointments-service`.

No sustituye la documentación canónica. Si existe drift, prevalece `AnthonyNav/estomatologia-platform-docs/main`. La referencia canónica mínima usada para este contrato es el cierre de Appointments V1 mergeado en `dedd907a6f289737f2ed2668cc2a4d2fc8cf641f` o cualquier commit posterior de `main`.

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
- contratos vigentes de `academic-service`, `authorization-service`, `wash-administration-service` y `wash-execution-service`.

### 1.1 Drift histórico que NO debe copiarse al código

El gate V1 y las reglas de negocio posteriores prevalecen sobre fragmentos históricos. En particular, no implementar:

```text
commands.appointments-service / results.appointments-service
AcademicPeriodRegistered
StudentAcademicProfileUpdated
StudentAcademicStatusChanged
Cabin.configuredCapacity
rescheduling de Appointment
PointBalance / PenaltyPoint como fuente persistida independiente
CleaningPeriod que bloquea indefinidamente solo porque completedAt sea null
```

También se consideran incompletas las listas históricas de handlers de Appointments que omiten dos Events ya canónicos de `wash-execution-service`:

```text
WashArrivalRegistered
WashEntryAuthorized
```

Su consumo es obligatorio porque:

- una llegada válida debe impedir que `MarkAppointmentAsMissed` marque una inasistencia incorrecta;
- `WashEntryAuthorized` es la evidencia que permite persistir `entryAuthorizedAt` cuando Execution queda `PENDING_REASSIGNMENT` y Appointment todavía permanece `SCHEDULED`.

Antes de iniciar cada PR de implementación se debe revisar si la documentación canónica evolucionó. Si aparece una contradicción BLOCKER/HIGH que cambia comportamiento, se corrige primero la fuente canónica.

---

## 2. Estado actual del scaffold

En `develop`, antes de los PRs implementadores, el servicio es solamente un scaffold:

- Spring Boot `4.1.0`;
- Java `25`;
- Maven Wrapper;
- Actuator, WebMVC y Prometheus;
- `SystemController` temporal;
- sin PostgreSQL/Flyway/JdbcClient funcional;
- sin RabbitMQ;
- sin dominio ni persistencia de negocio;
- sin proyecciones externas reales;
- sin Authorization local;
- sin Consumer Inbox / Transactional Outbox reales.

El README se alinea en este PR documental y deja de ser fuente independiente de reglas.

---

## 3. Arquitectura hexagonal obligatoria

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
→ no depende de Spring, JDBC, RabbitMQ, Jackson, contracts ni infrastructure

application
→ depende de domain y ports
→ no depende de adapters concretos

infrastructure
→ implementa ports
→ puede depender de Spring/JDBC/RabbitMQ/Jackson

contracts
→ modelos wire/versionados
→ sin lógica de negocio
```

ArchUnit debe impedir regresiones.

No se acepta:

- lógica de negocio dentro de listeners RabbitMQ;
- repositorios/adapters dentro de `domain`;
- DTOs wire usados como entidades de dominio;
- SQL desde input adapters;
- acceso a DB de otro servicio;
- REST/gRPC de negocio para saltarse RabbitMQ;
- una clase central que concentre dominio, SQL, transporte y mapping.

---

## 4. Ownership V1

Fuente oficial de:

```text
AppointmentTimeSlot
Appointment
ExceptionalAppointmentAuthorization
```

Estado técnico local:

```text
Consumer Inbox
functional idempotency
Transactional Outbox
projection recovery state
pending/deferred event processing
pending_capacity_evaluation
```

Proyecciones locales mínimas de:

```text
Academic
Authorization
Wash Administration
Wash Execution
```

No posee:

```text
ServiceCalendar / ServiceSchedule / AdministrativeClosure
Cabin / Tank / ResourcePriority
WashExecution / ResourceAssignment / ResourceUnavailability / CleaningPeriod
AcademicPeriod / CourseSection
AccountRole / SystemAccess
Persona / Account / Session
communications
```

No existen FKs cross-database.

---

## 5. Aggregate roots y modelo funcional

Agregados funcionales versionados:

```text
AppointmentTimeSlot
Appointment
ExceptionalAppointmentAuthorization
```

Invariantes multi-agregado se coordinan desde Application dentro de una transacción local con locks/constraints explícitos.

### 5.1 `AppointmentTimeSlot`

Bloque concreto reservable, no horario recurrente ni recurso físico.

Campos mínimos:

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

Estados:

```text
ACTIVE
CANCELLED
```

La capacidad utilizable se proyecta aparte. Un bloque ya materializado puede quedar invalidado por un cambio administrativo posterior; Administration nunca lo modifica directamente.

### 5.2 `Appointment`

Estados:

```text
SCHEDULED
CANCELLED
MISSED
ENTRY_REJECTED
IN_PROGRESS
COMPLETED
```

Finales:

```text
CANCELLED
MISSED
ENTRY_REJECTED
COMPLETED
```

No existe `INTERRUPTED`.

Una cita no se edita ni se reprograma:

```text
cambiar datos u horario
→ cancelar
→ registrar nueva cita
```

Campos mínimos:

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

Aplica a alumno + fecha de servicio y permite una cita adicional cuando corresponde.

No requiere `status` persistido si su disponibilidad se deriva de:

- vigencia/fecha;
- cancelación explícita de la autorización;
- Appointment(s) que la han utilizado.

Semántica V1:

```text
cita adicional SCHEDULED/IN_PROGRESS/COMPLETED
→ consume autorización

CANCELLED + STUDENT
MISSED
ENTRY_REJECTED
→ siguen consumiendo autorización

CANCELLED + CLINIC
→ deja de consumir autorización
→ la autorización vuelve a ser utilizable ese día
```

Para conservar trazabilidad, una autorización **puede quedar referenciada por varias Appointment históricas** si las anteriores terminaron `CANCELLED + CLINIC`. Esto sustituye la cardinalidad histórica uno-a-uno como restricción física.

Debe existir como máximo **una Appointment consumidora** por autorización al mismo tiempo. Una forma válida es una restricción/índice parcial sobre `appointments.exceptional_authorization_id` que excluya únicamente `CANCELLED + CLINIC`, o una garantía transaccional equivalente demostrada con pruebas concurrentes.

No borrar la relación histórica solo para “restaurar” la autorización salvo que se documente una estrategia de auditoría equivalente. La disponibilidad se deriva de si existe una Appointment consumidora, no de que jamás haya existido una referencia histórica.

La restauración por clínica debe ser atómica con la cancelación.

---

## 6. Registro, tipos y límites

Tipos:

```text
NORMAL
JOURNEY
IMMUNOCOMPROMISED
```

Instrumentos:

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

`ScheduleAppointment` debe demostrar localmente:

- actor/acceso autorizados;
- proyección académica confiable;
- NRC/inscripción vigente;
- fecha de servicio válida;
- slot ACTIVE y booking deadline vigente;
- datos/tipo válidos;
- ausencia de otra cita activa del alumno;
- puntos < 3;
- límite diario de COMPLETED;
- autorización excepcional cuando aplique;
- capacidad final revalidada bajo lock;
- regla IMMUNO cuando aplique.

Solo `COMPLETED` consume el límite diario normal. Máximo confirmado: dos `COMPLETED` por alumno/fecha.

---

## 7. Puntos y bloqueo

No existe balance persistido independiente.

```text
MISSED               → 1
CANCELLED + STUDENT  → 1
CANCELLED + CLINIC   → 0
ENTRY_REJECTED       → 0
resto                → 0
```

Con `>= 3` puntos en la fecha de servicio, `ScheduleAppointment` se rechaza. El reinicio al día siguiente es derivado.

---

## 8. Capacidad de agenda

Fuente física configurada:

```text
Tank.configuredCapacity
```

Nunca `Cabin.configuredCapacity`.

Cada Appointment activa consume una unidad, independientemente de `instrumentCount`.

Por bloque:

```text
C_NORMAL / C_JOURNEY / C_IMMUNOCOMPROMISED
D_NORMAL / D_JOURNEY / D_IMMUNOCOMPROMISED
```

Estados que reservan demanda:

```text
SCHEDULED
IN_PROGRESS
```

Capacidad utilizable considera:

- Cabin/Tank + estado administrativo;
- `Tank.configuredCapacity`;
- `AdministrativeClosure`;
- `ResourceUnavailability`;
- `CleaningPeriod`;
- reserva preventiva IMMUNO;
- `ResourcePriority`.

Compatibilidad:

```text
NORMAL  → NORMAL preferida, JOURNEY alternativa
JOURNEY → JOURNEY preferida, NORMAL alternativa
IMMUNO  → IMMUNOCOMPROMISED exclusiva
```

NORMAL/JOURNEY se resuelven con matching, no con depósitos independientes sumables.

### 8.1 Limpieza física real

La restricción efectiva de un `CleaningPeriod` V1 es:

```text
[startsAt, expectedEndsAt)
```

`expectedEndsAt = startsAt + 3h` y V1 finaliza automáticamente ahí. Una demora técnica en `CleaningPeriodCompleted` no prolonga por sí sola el bloqueo más allá de `expectedEndsAt`.

No copiar la regla histórica “`completedAt IS NULL` bloquea indefinidamente”.

---

## 9. Reserva preventiva `IMMUNOCOMPROMISED`

Una IMMUNO activa reserva:

```text
[slot.startsAt, slot.endsAt + 3h)
```

La validación es simétrica: bloquea citas posteriores dentro de la limpieza prevista y citas anteriores cuya propia limpieza prevista invadiría la cita existente.

No es un `CleaningPeriod` físico. Desaparece si la cita no ocurre sin uso físico:

```text
CANCELLED
MISSED
ENTRY_REJECTED
```

---

## 10. Cambios administrativos y pérdida de capacidad

Flujo:

```text
Administration confirma cambio
→ Event
→ Appointments actualiza proyección
→ identifica slots futuros afectados
→ recalcula existencia/capacidad
→ cancela SCHEDULED elegibles si hace falta
```

Cancelación automática:

```text
CANCELLED
cancellationType = CLINIC
cancellationSubreason = CAPACITY_LOSS
points = 0
```

Orden cuando debe sacrificarse demanda:

```text
createdAt DESC
appointmentId DESC
```

Nunca auto-reschedule.

Protegidas:

```text
SCHEDULED con entryAuthorizedAt != null
IN_PROGRESS
```

Si una cita cancelada por clínica usó autorización excepcional, esa referencia histórica deja de consumir la autorización dentro de la misma transacción.

---

## 11. Integración con Wash Execution

Appointments consume dos clases de Events: **evidencia operacional** y **transiciones de Appointment**.

### 11.1 Evidencia operacional obligatoria

#### `WashArrivalRegistered` → `HandleWashArrivalRegistered`

No cambia `Appointment.status`.

Debe registrar localmente como mínimo:

```text
washExecutionId
appointmentId
arrivedAt
arrivalExecutionVersion
sourceEventId/messageId
```

Efecto: una llegada válida conocida impide `MarkAppointmentAsMissed`.

#### `WashEntryAuthorized` → `HandleWashEntryAuthorized`

No cambia por sí solo `Appointment.status`.

Debe persistir:

```text
Appointment.entryAuthorizedAt = decidedAt
```

cuando corresponda, incluso si Execution terminó esa misma transacción en `PENDING_REASSIGNMENT`. Esto protege contra MISSED y cancelación administrativa automática.

Si `WashExecutionStarted` ya llegó primero, `WashEntryAuthorized` puede completar la metadata sin retroceder el estado.

### 11.2 Transiciones propietarias

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

Cada Event tiene un único handler propietario.

### 11.3 Versionado de evidencia vs transición

`DecideWashEntry` puede publicar `WashEntryAuthorized` y `WashExecutionStarted` en la misma transacción de Execution; por tanto ambos pueden representar el mismo `executionVersion` con **messageId distintos**.

Consecuencia obligatoria:

```text
NO deduplicar Events distintos únicamente porque executionVersion sea igual
```

La deduplicación exacta es por `messageId/sourceEventId`. La monotonía de versión protege contra información vieja, pero debe permitir múltiples hechos distintos de la misma versión resultante.

Implementación recomendada:

- evidencia (`arrival`, `entry authorized`) con campos/versiones propios o aplicación idempotente por Event;
- `transition_source_version` separado para la state machine de Appointment, o esquema equivalente;
- nunca usar un único `source_version` para descartar ciegamente otro tipo de Event válido con la misma versión.

---

## 12. `MISSED` y carrera con llegada

Regla de negocio:

```text
MISSED solo si terminó arrivalToleranceUntil
Y no existe llegada válida que proteja la cita
Y no existe entry authorization/start
```

`MarkAppointmentAsMissed` debe bloquear/releer la Appointment y la evidencia local de Execution en la misma decisión transaccional.

Además:

- si la proyección/consumer de Execution está `REBUILDING`, `DEGRADED`, no confiable o con procesamiento causal pendiente para esa Appointment, **no marcar MISSED**; diferir/reintentar;
- el job no debe depender de ejecutar exactamente en el milisegundo de `arrivalToleranceUntil`; puede usar una pequeña gracia técnica configurable para permitir convergencia de Events sin cambiar la hora funcional de tolerancia;
- una `WashArrivalRegistered` válida recibida antes de que MISSED haga commit debe ganar mediante el mismo lock/conditional update;
- una llegada con `arrivedAt > arrivalToleranceUntil` nunca protege.

Escenario obligatorio:

```text
arrivedAt = +9 min
Supervisor decide entrada = +12 min
→ Appointment NO puede terminar MISSED
```

Un Event tardío que demuestre que una Appointment ya marcada MISSED tenía llegada válida dentro de tolerancia es una inconsistencia técnica crítica: debe activar reconciliación/alerta y nunca descartarse silenciosamente como stale.

---

## 13. Eventos fuera de orden y evaluación diferida

RabbitMQ no garantiza orden global.

Handlers de transición:

- Inbox por `sourceEventId`;
- versión de Execution para monotonía, no contigüidad;
- conditional update/lock por estado local;
- evento causalmente no aplicable → persistir `DEFERRED`, ACK durable y reprocesar después;
- no saltar estados.

Caso mínimo:

```text
WashExecutionCompleted llega antes que WashExecutionStarted
→ no SCHEDULED → COMPLETED
→ COMPLETED queda DEFERRED
→ STARTED aplica
→ COMPLETED se reprocesa exactamente una vez
```

Caso de capacidad:

```text
CleaningPeriodStarted
puede llegar antes de
WashExecutionCompleted / WashExecutionCancelled
```

`pending_capacity_evaluation` debe sobrevivir reinicios y resolverse solo después de aplicar el estado terminal local causal.

---

## 14. Academic projection

Consumir solo Events V1 vigentes:

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

No implementar:

```text
AcademicPeriodRegistered
StudentAcademicProfileUpdated
StudentAcademicStatusChanged
```

Versiones por identidad/stream propietario. No inventar versión global del estudiante.

Ante proyección requerida no confiable: `ScheduleAppointment` falla cerrado. Sin fallback HTTP/gRPC.

---

## 15. Authorization projection

Mantener mínimo:

```text
AccountRole
SystemAccess(LAVADO_ULTRASONICO)
```

Authorization se evalúa localmente con ActorContext + proyecciones + matriz estática V1. Proyección no demostrable → rechazo funcional fail-closed.

---

## 16. Wash Administration projection y recovery

Proyecciones mínimas:

```text
ServiceCalendar
ServiceSchedule
AdministrativeClosure
Cabin
Tank
ResourcePriority
```

Recovery:

```text
GetWashAdministrationConfiguration
```

Events son el canal incremental; Query solo para bootstrap/rebuild/reconciliación.

---

## 17. Wash Execution projection y recovery

Persistir como mínimo:

```text
arrival evidence
entry authorization evidence
ResourceUnavailability
CleaningPeriod
WashExecution transition state
```

El modelo físico puede usar una o varias tablas, pero debe poder contestar de forma race-safe:

```text
¿hubo llegada válida?
¿la entrada fue autorizada?
¿qué transición de Execution fue aplicada?
¿qué restricciones operativas afectan el slot?
```

Recovery/reconciliación no puede tratar RabbitMQ como Event Store. Cuando evidencia operativa crítica no sea confiable, diferir decisiones irreversibles como `MISSED`.

---

## 18. Queries autoritativas ofrecidas a consumidores

Appointments expone por RabbitMQ:

```text
GetAppointmentExecutionContext
ListAppointmentExecutionContextsPage
```

Requester V1:

```text
wash-execution
```

Son side-effect-free, service-to-service y responden por `platform.results` hacia `results.wash-execution`.

### 18.1 Snapshot de disponibilidad para BFF

Appointments también ofrece `GetAppointmentAvailability` V1 exclusivamente al requester técnico
`bff`. Es una Query durable asíncrona (`platform.queries`, routing key `appointments`), no una
llamada HTTP/gRPC ni una lectura directa de `appointments-db`. La respuesta
`GetAppointmentAvailabilityResult` se persiste primero en Transactional Outbox y se publica en
`platform.results` con routing key `bff`.

El payload de la Query contiene exactamente el contexto estable:

```text
studentAccountId
serviceDate
appointmentType
instrumentCount
pieceType
courseSectionId
```

El Result exitoso ecoa ese `requestContext` y contiene:

```text
snapshotId
snapshotGeneratedAt
freshUntil
canSchedule
blockingReasons[]
dailyPenaltyPoints
dailyCompletedAppointments
exceptionalAuthorizationRequired
exceptionalAuthorizationAvailable
exceptionalAuthorizationId
availableTimeSlots[]
  appointmentTimeSlotId
  startsAt
  endsAt
  availableCapacity
  bookingDeadlineAt
```

`canSchedule` usa la misma `LocalAuthorizationPolicy` del Command para demostrar rol `ALUMNO`,
acceso `LAVADO_ULTRASONICO = ACTIVE` y proyección local confiable para el `studentAccountId` del
contexto. El actor técnico `SYSTEM/bff` nunca se trata como el Alumno. Si ese contexto no puede
demostrarse, `blockingReasons` contiene `STUDENT_ACCESS_DENIED` y `canSchedule = false`, equivalente a la decisión
de `ScheduleAppointment`.

Los blockers se producen una sola vez y en orden determinista V1:

```text
ACTIVE_APPOINTMENT_EXISTS
STUDENT_BLOCKED
DAILY_COMPLETED_LIMIT
EXCEPTIONAL_AUTHORIZATION_REQUIRED
ACADEMIC_ENROLLMENT_INVALID
STUDENT_ACCESS_DENIED
```

`snapshotId` es igual a `messageId` del Result. No se inventa una versión agregada global:
`messageVersion = 1` versiona el wire contract y `snapshotGeneratedAt` expresa cuándo se tomó la
fotografía. `aggregateId` y `aggregateVersion` quedan ausentes. El BFF aplica un Result sólo si
`correlationId`, `causationId` y el `requestContext` coinciden con su refresh pendiente más reciente
para esa llave; un Result viejo no puede sobrescribir uno nuevo.

La vigencia máxima es 30 segundos:

```text
freshUntil = min(snapshotGeneratedAt + 30s, bookingDeadlineAt más cercano retornado)
```

El BFF sólo lo considera fresco cuando
`snapshotGeneratedAt <= now <= freshUntil`; nunca lo sirve después de `freshUntil`. Ausencia o snapshot vencido falla cerrado y
dispara un refresh durable; no reconstruye matching, puntos ni autorización excepcional. Consultar
no reserva cupo: `ScheduleAppointment` vuelve a bloquear filas y decide autoritativamente ante
concurrencia.

`availableTimeSlots` incluye exclusivamente slots activos dentro de booking y con capacidad
compatible positiva calculada por `AppointmentCapacityMatcher`. NORMAL/JOURNEY comparten capacidad;
los valores no representan depósitos independientes. Un alumno funcionalmente elegible puede
recibir `canSchedule = true` con arreglo vacío cuando no existe horario compatible en ese instante.

Un payload malformado, actor/requester incorrecto o campos desconocidos se trata como contrato
inválido y sigue retry/DLQ. Un contexto bien formado pero semánticamente inválido recibe
`status = REJECTED`, `error.code = INVALID_AVAILABILITY_CONTEXT`.

---

## 19. QR V1

QR = representación protegida de `appointmentId`.

No es entidad, autoridad de entrada ni validación de identidad.

El formato owner V1 es `v1.<appointmentId>.<HMAC-SHA256 base64url>`. Aporta integridad y
detección de alteraciones. El UUID permanece visible: este formato no aporta confidencialidad y no
debe describirse como cifrado. El cliente y el BFF tratan la cadena completa como opaca. La firma
válida identifica una cita; no autoriza entrada ni sustituye la validación funcional del estado.

La emisión se activa sólo con `ESTOMA_QR_ENABLED=true`; el valor por defecto es `false`. Con el
flag apagado, `AppointmentScheduled` conserva exactamente su shape anterior. Con el flag activo,
todo `AppointmentScheduled` nuevo incluye `qrRepresentation`, calculado dentro de la misma
transacción que persiste Appointment y su Event en Outbox. El proceso no arranca si
`ESTOMA_QR_SECRET` está vacío, es blanco o mide menos de 32 bytes UTF-8. Esa longitud es un mínimo,
no una medición de entropía: operación debe generar material aleatorio de al menos 32 bytes y
provisionar el mismo valor en todas las réplicas sin imprimirlo ni copiarlo al BFF.

No hay rotación automática ni `kid`. El secreto permanece estable mientras existan citas activas.
Antes de rotarlo se cierra el gate QR, se detienen emisiones mixtas, se limpia/reinicializa la
proyección QR del BFF y se ejecuta recovery completa con todas las réplicas de Appointments sobre
el nuevo valor. Rotar una réplica o regenerar tokens sin esa secuencia rompe representaciones ya
entregadas.

### 19.1 Recovery owner para la proyección BFF

`ListAppointmentQrProjectionSnapshotsPage` V1 es una Query exclusiva de recuperación. Exige
`producer=bff`, actor `SYSTEM/service=bff` sin `accountId`, payload exacto con `serviceDate`,
`pageSize` entero de 1 a 100 y `pageToken` opaco opcional. No se invoca por cada escaneo ni habilita
un endpoint HTTP/RPC owner.

El Result se guarda en Outbox antes de publicarse a routing key `bff`. Un replay con el mismo
`messageId` de Query converge sobre un único Result durable. Cada item contiene
`appointmentId`, `appointmentVersion`, `appointmentStatus`, `entryAuthorizedAt` y
`qrRepresentation`. `SCHEDULED` e `IN_PROGRESS` llevan la representación; `CANCELLED`, `MISSED`,
`ENTRY_REJECTED` y `COMPLETED` llevan `qrRepresentation=null` como tombstone explícito. El BFF
aplica cada fila con fence de `appointmentVersion`: un snapshot viejo no restaura un mapping que
un evento owner posterior ya retiró.

El cursor está versionado y ligado a `serviceDate`; usarlo con otra fecha se rechaza. Cada página
usa una transacción `REPEATABLE_READ`, pero la serie completa de páginas no es un snapshot global.
La convergencia requiere consumir Events concurrentes y completar después una pasada de
reconciliación desde la primera página. Una fila insertada concurrentemente antes del cursor puede
llegar por su Event y queda incluida en la siguiente pasada completa.

La Query responde `QR_PROJECTION_DISABLED` mientras el producer está apagado. El rollout despliega
primero un BFF que acepte `AppointmentScheduled` V1 con el campo opcional y procese recovery;
después provisiona el secreto y activa Appointments. El gate público QR permanece cerrado hasta
que la pasada owner termine y BFF demuestre todos los mappings activos.

El consumidor actual de Wash Execution lee de forma selectiva los campos de `AppointmentScheduled`
y tolera este campo aditivo. La dependencia de orden aplica al BFF porque su allowlist de payload es
cerrada: su cambio compatible debe estar desplegado antes de activar el producer QR.

---

## 20. Casos de uso V1

Actor/cliente:

```text
ScheduleAppointment
CancelAppointment
GrantExceptionalAppointmentAuthorization
CancelExceptionalAppointmentAuthorization
```

Internos agenda/capacidad:

```text
CreateAppointmentTimeSlot
CancelAppointmentTimeSlot
HandleResourceCapacityEvent
RecalculateAppointmentCapacityForTimeSlot
CancelScheduledAppointmentsForCapacityLoss
MarkAppointmentAsMissed
```

Evidencia Execution:

```text
HandleWashArrivalRegistered
HandleWashEntryAuthorized
```

Transiciones Execution:

```text
HandleWashEntryRejected
HandleWashExecutionStarted
HandleWashExecutionCompleted
HandleWashExecutionCancelled
```

Todos entran por application input ports.

---

## 21. Mensajería V1

Compatibilidad de lectura acotada para rechazo no clasificado:

```text
WashEntryRejected V1 → continúa aceptado con su contrato histórico
WashEntryRejected V2 → aceptado sólo como EVENT de producer wash-execution
otros messageType/messageKind/producer con messageVersion = 2 → rechazados
```

El payload V2 es null-free: cuando el rechazo no clasifica verificaciones, omite por completo
`identityConfirmed` y `requirementsSatisfied`. Appointments no interpreta esos checks; aplica el
rechazo con `washExecutionId`, `appointmentId`, versión del agregado, metadata de decisión y
`rejectionReason`, igual que para V1. Esta compatibilidad es únicamente de reader: no habilita a
Appointments, al BFF ni al owner a producir `WashEntryRejected` V2, ni cambia ningún Command.

Identidad:

```text
logicalComponentId = appointments
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

No aliases `*-service`.

Reglas:

- manual ACK después de commit local/durable;
- rechazo funcional → Result terminal + ACK, sin retry;
- fallo técnico → retry/DLQ según clasificación;
- publisher confirms en Outbox Relay;
- Outbox no se marca publicado antes del confirm;
- Inbox por messageId/sourceEventId;
- idempotencyKey para intención funcional de Commands;
- RabbitMQ no es Event Store.

---

## 22. Persistencia V1

Tecnología:

```text
PostgreSQL
Flyway
Spring JDBC / JdbcClient
```

No JPA/Hibernate.

Tablas funcionales:

```text
appointment_time_slots
appointments
exceptional_appointment_authorizations
appointment_capacity_projections
```

Proyecciones/estado técnico mínimo:

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
wash_execution_transition_projection   // o esquema equivalente separado por semántica
pending_wash_execution_events
pending_capacity_evaluation
projection_recovery_state
processed_messages
command_idempotency_records
outbox_messages
```

`wash_execution_evidence_projection` debe conservar como mínimo:

```text
washExecutionId
appointmentId
arrivedAt?
arrivalExecutionVersion?
entryAuthorizedAt?
entryAuthorizedExecutionVersion?
updatedAt
```

No es obligatorio usar exactamente estos nombres de tabla si la semántica y pruebas quedan demostradas.

---

## 23. Constraints e índices obligatorios

Garantizar como mínimo:

- slot equivalente único;
- una cita activa simultánea por alumno;
- asociación Appointment→slot válida;
- `version > 0`;
- `instrumentCount > 0`;
- consistencia cancelación type/subreason;
- una autorización excepcional por alumno/fecha según contrato;
- **máximo una Appointment consumidora por `exceptional_authorization_id`**, permitiendo referencias históricas `CANCELLED + CLINIC`;
- Inbox unique por messageId;
- idempotency scope/key + fingerprint;
- proyecciones unique por source identity;
- claim seguro de pending/deferred rows.

Índices:

```text
appointments(student_account_id, status)
appointments(appointment_time_slot_id, status)
appointments(slot, created_at desc, appointment_id desc)
appointments(exceptional_authorization_id)
exceptional_authorizations(student_account_id, authorization_date)
outbox(status, next_attempt_at, created_at)
processed_messages(message_id)
wash_execution evidence por appointmentId/washExecutionId
pending evaluations por status/causal execution
```

---

## 24. Concurrencia obligatoria

Probar al menos:

1. dos alumnos último cupo;
2. mismo alumno dos citas activas;
3. Schedule vs pérdida de capacidad;
4. dos recálculos del mismo slot;
5. capacidad cancel vs entry authorization;
6. MISSED vs `WashArrivalRegistered`;
7. MISSED vs EntryAuthorized/Started;
8. terminal Execution vs pending capacity evaluation;
9. consumo/restauración/reutilización de autorización;
10. redelivery del mismo Event/Command;
11. snapshot/recovery vs Events incrementales;
12. dos Events distintos con mismo `executionVersion` (`WashEntryAuthorized` + `WashExecutionStarted`).

No se acepta `read → validate → write` sin protección transaccional equivalente. Orden de locks estable y documentado.

---

## 25. Idempotencia y atomicidad

Command mutante iniciado por cliente, en una transacción local:

```text
Inbox
functional idempotency
locks/read models locales
mutation
consumo/restauración derivada de autorización
Outbox Result
Outbox Event(s)
```

Mismo messageId → no repite efecto.

Misma idempotencyKey + mismo fingerprint → mismo desenlace lógico sin repetir mutación/evento.

Misma key + fingerprint distinto → conflicto funcional estable.

Falló Outbox insert → rollback del cambio funcional.

---

## 26. Outbox Relay

Debe ser:

- multi-replica safe;
- claim/fencing/lease o equivalente;
- publisher-confirmed;
- retry/backoff para transitorios;
- `FAILED`/quarantine solo para contrato permanentemente inválido;
- restart safe;
- observable.

Broker caído después del commit no pierde Result/Event.

---

## 27. Readiness y observabilidad

Endpoints:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/prometheus
```

Readiness completa:

```text
PostgreSQL reachable
Flyway/schema compatible
RabbitMQ/topology disponible
Outbox publisher operativo
consumidores levantados
proyecciones críticas READY/confiables
```

Métricas mínimas:

```text
command/result latency
functional rejection by code
outbox pending/oldest/retry
consumer retry/dlq
deferred events count/age
projection readiness/recovery
execution evidence lag
capacity recalculation latency
capacity-loss cancellations
missed evaluation deferred/failures
idempotency conflicts
```

---

## 28. Testing mínimo contractual

Unit/domain:

- state machine;
- puntos;
- tipos/instrumentos;
- autorización excepcional y reutilización clínica;
- IMMUNO simétrica;
- matching;
- temporales.

PostgreSQL/Testcontainers:

- Flyway desde DB vacía;
- repositories;
- locking/constraints;
- partial/equivalent authorization-consumer uniqueness;
- rollback Outbox;
- carreras críticas.

RabbitMQ/Testcontainers:

- Command→Result/Event;
- duplicate delivery;
- retry/DLQ;
- confirms;
- restart;
- arrival/authorization evidence;
- same-version distinct Events;
- stale/out-of-order transition Events;
- recovery snapshot + concurrent Events.

E2E:

- Appointments + Wash Administration + Wash Execution;
- RabbitMQ;
- PostgreSQL aislado por servicio;
- Academic/Authorization reales o productores contractualmente fieles.

---

## 29. Seguridad

- sin secretos en repo;
- QR key/secret por configuración segura;
- no JWT/credentials en Inbox/Outbox;
- no PII innecesaria en Events;
- ActorContext del envelope, no IDs humanos libres cuando el contrato lo provee;
- Authorization local por proyección;
- Queries service-to-service allowlisted;
- no endpoints HTTP de negocio.

---

## 30. Decisiones diferidas no bloqueantes

No inventar:

- ownership/versionado futuro del reglamento;
- mecanismo físico exacto de validación de identidad;
- UX exacta de búsqueda manual;
- umbral `instrumentCount` vs materiales de salida;
- comunicaciones adicionales;
- historial/corrección manual de puntos;
- submotivos clínicos adicionales;
- reportes/alertas futuras.

---

## 31. Definition of Done V1

Appointments V1 no está completo hasta demostrar:

- arquitectura hexagonal + ArchUnit;
- dominio sin framework;
- PostgreSQL/Flyway/JdbcClient;
- cero JPA/Hibernate;
- Commands idempotentes;
- Inbox/Outbox atómicos;
- último cupo race-safe;
- no dos citas activas concurrentes;
- cambios administrativos cancelan exactamente SCHEDULED elegibles;
- entry-authorized/IN_PROGRESS protegidos;
- llegada válida protege contra MISSED;
- `WashEntryAuthorized` persiste `entryAuthorizedAt` aunque no haya recurso;
- Events distintos con mismo executionVersion no se pierden;
- autorización excepcional se restaura/reutiliza solo por CLINIC preservando historia;
- IMMUNO simétrica;
- cleaning real usa `[startsAt, expectedEndsAt)`;
- out-of-order converge sin saltos;
- proyecciones reconstruibles sin Event Store;
- Queries de Execution funcionan;
- Query/Result de disponibilidad owner-derived conserva fencing, TTL y semántica de matching;
- RabbitMQ usa `appointments` sin aliases;
- readiness refleja dependencias/proyecciones;
- E2E crítico verde;
- `./mvnw clean verify` verde en cada PR;
- no quedan contradicciones BLOCKER/HIGH conocidas.
