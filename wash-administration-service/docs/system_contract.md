# wash-administration-service System Contract

## Estado

Contrato de implementación para `wash-administration-service`.

Este documento prepara la implementación futura del servicio, pero **este PR no implementa comportamiento de negocio**. Los cambios de código deberán realizarse después, siguiendo `execution_plan.md` y respetando la división de tres PRs allí definida.

Si este archivo entra en conflicto con la documentación canónica de plataforma, la documentación de plataforma prevalece y el conflicto debe resolverse antes de implementar el comportamiento afectado.

## Fuente De Verdad

Fuente canónica: `AnthonyNav/estomatologia-platform-docs`, `main` observado en `af603c80ebb1259b155209e81a0fd3a0469a6fba`.

Documentos principales:

- `03-services/domains/lavado-ultrasonico/wash-administration-service/README.md`
- `03-services/domains/lavado-ultrasonico/wash-administration-service/data-model.md`
- `03-services/domains/lavado-ultrasonico/wash-administration-service/commands.md`
- `03-services/domains/lavado-ultrasonico/wash-administration-service/events.md`
- `03-services/domains/lavado-ultrasonico/wash-administration-service/interfaces.md`
- `03-services/domains/lavado-ultrasonico/wash-administration-service/queries.md`
- `02-architecture/messaging-architecture.md`
- `02-architecture/data-consistency-architecture.md`
- `02-architecture/domains/lavado-ultrasonico-architecture.md`
- `01-domain/lavado-business.md`
- `01-domain/lavado-ultrasonico-bounded-contexts.md`

Para autorización local también deben respetarse:

- `03-services/shared/authorization-service/README.md`
- `03-services/shared/authorization-service/data-model.md`
- `03-services/shared/authorization-service/events.md`
- `03-services/shared/authorization-service/interfaces.md`

Antes de iniciar cada PR de implementación, el agente debe revisar si estos contratos cambiaron desde la referencia anterior. No debe implementar reglas basándose únicamente en este archivo si la fuente canónica ya evolucionó.

## Estado Actual Del Scaffold

El servicio existente en `develop` es solamente un scaffold:

- Spring Boot `4.1.0`;
- Java `25`;
- Maven Wrapper;
- Actuator, WebMVC y Prometheus;
- `SystemController` con endpoints temporales `/health` y `/version`;
- sin PostgreSQL;
- sin Flyway;
- sin Spring JDBC;
- sin RabbitMQ;
- sin dominio implementado;
- sin persistencia funcional;
- sin autorización local;
- sin Outbox/Inbox real.

La implementación futura puede reemplazar el `SystemController` por configuración Actuator equivalente. No debe conservarse un controller funcional solo por compatibilidad con el scaffold.

## Logical Component ID

Identidad lógica de mensajería:

```text
wash-administration
```

`producer`, routing keys y `ActorContext.type = SYSTEM.service` usan el namespace de `logicalComponentId` definido por la arquitectura transversal.

## Responsabilidad

`wash-administration-service` responde:

> ¿Cuándo puede operar Lavado Ultrasónico y qué recursos existen administrativamente para hacerlo?

Es la fuente oficial de:

- `ServiceCalendar`;
- `ServiceSchedule`;
- `AdministrativeClosure`;
- `Cabin`;
- `Tank`;
- `ResourcePriority` fijo.

También mantiene persistencia técnica propia para:

- Consumer Inbox;
- idempotencia funcional de Commands;
- Transactional Outbox;
- proyecciones mínimas de Authorization necesarias para validar operaciones administrativas.

## Lo Que No Posee

El servicio no es propietario de:

- `AcademicPeriod`;
- matrícula, semestre, NRC o inscripciones;
- `AppointmentTimeSlot`;
- `Appointment`;
- QR, puntos o asistencia;
- asignaciones físicas;
- ocupación;
- fallas operativas;
- limpiezas;
- `WashExecution`;
- Persona;
- Cuenta, credenciales o sesiones;
- roles, permisos o `SystemAccess` como fuente oficial;
- comunicaciones o buzón.

No puede escribir directamente en bases de otros microservicios.

## Límite Con Academic

`ServiceCalendar` y `AcademicPeriod` son conceptos independientes.

```text
academic-service
└── AcademicPeriod

wash-administration-service
└── ServiceCalendar
```

Reglas V1:

- no existe `academicPeriodId` en `ServiceCalendar`;
- no se consume `AcademicPeriod` para administrar el calendario operativo;
- ambos pueden coincidir temporalmente sin compartir ownership ni lifecycle.

## Aggregate Roots

Para implementación V1 se tratarán como agregados versionados independientes:

```text
ServiceCalendar
ServiceSchedule
AdministrativeClosure
Cabin
Tank
ResourcePriority
```

La razón es contractual: cada entidad tiene identidad y `version` propias y existen Commands que modifican cada una de forma independiente.

Las invariantes entre agregados se coordinan desde Application mediante puertos de lectura/escritura dentro de una transacción local cuando corresponda.

Ejemplos:

- activar `ServiceCalendar` requiere comprobar al menos un `ServiceSchedule ACTIVE`;
- retirar `Cabin` requiere comprobar que todas sus `Tank` estén `RETIRED`;
- programar un cierre sobre una cabina/tina requiere comprobar existencia y estado administrativo;
- actualizar un horario debe comprobar solapamiento con otros horarios del calendario/día.

El modelo de dominio no debe depender de repositorios Spring ni conocer SQL.

## Modelo Funcional

### ServiceCalendar

Estados:

```text
DRAFT
ACTIVE
INACTIVE
```

Reglas:

- `code` único e inmutable;
- `effectiveUntil >= effectiveFrom` cuando exista;
- dos calendarios `ACTIVE` no pueden solaparse;
- para activar requiere al menos un `ServiceSchedule ACTIVE`;
- no se elimina físicamente;
- `version` se incrementa en cada cambio confirmado.

### ServiceSchedule

Estados:

```text
ACTIVE
INACTIVE
```

Reglas:

- `dayOfWeek` entre 1 y 7;
- `openingTime < closingTime`;
- `slotDurationMinutes > 0`;
- el intervalo debe dividirse en bloques completos;
- horarios `ACTIVE` del mismo calendario/día no se solapan;
- no representa `AppointmentTimeSlot`;
- `version` propia.

### AdministrativeClosure

Scopes:

```text
SERVICE
CABIN
TANK
```

Estados persistidos:

```text
SCHEDULED
CANCELLED
```

No existe `COMPLETED` persistido.

Reglas:

```text
SERVICE → cabinId NULL, tankId NULL
CABIN   → cabinId NOT NULL, tankId NULL
TANK    → cabinId NULL, tankId NOT NULL
```

Además:

- `endsAt > startsAt`;
- intervalo dentro de la vigencia del calendario;
- `reason` obligatorio;
- no cierres exactamente duplicados;
- recurso objetivo existente y no `RETIRED` al programar;
- puede existir fuera de horario de servicio y simplemente no producir efecto;
- el fin temporal se deriva de `endsAt`.

### Cabin

Tipos:

```text
NORMAL
JOURNEY
IMMUNOCOMPROMISED
```

Estados:

```text
ACTIVE
INACTIVE
RETIRED
```

Reglas:

- `code` único e inmutable;
- `name` y `cabinType` modificables mientras no esté `RETIRED`;
- `INACTIVE` reversible;
- `RETIRED` irreversible;
- desactivar una cabina no modifica el estado persistido de sus tinas;
- una cabina `INACTIVE` hace no utilizables sus tinas aunque alguna siga `ACTIVE`;
- para retirar una cabina, todas sus tinas deben estar previamente `RETIRED`;
- no existe retiro en cascada V1;
- **Cabin no tiene capacidad configurada propia**.

### Tank

Estados:

```text
ACTIVE
INACTIVE
RETIRED
```

Reglas:

- pertenece a una sola `Cabin`;
- `code` único dentro de la cabina e inmutable;
- no cambia de cabina mediante Update;
- `configuredCapacity` entero y mayor que cero;
- `INACTIVE` reversible;
- `RETIRED` irreversible;
- no almacena ocupación, falla ni limpieza.

### ResourcePriority

Matriz fija V1:

```text
NORMAL + NORMAL                         → PREFERRED   / 1
NORMAL + JOURNEY                        → ALTERNATIVE / 2
JOURNEY + JOURNEY                       → PREFERRED   / 1
JOURNEY + NORMAL                        → ALTERNATIVE / 2
IMMUNOCOMPROMISED + IMMUNOCOMPROMISED  → EXCLUSIVE   / 1
```

Reglas:

- solo existen esas cinco filas;
- la combinación, `usageMode` y `priorityOrder` son inmutables;
- permanecen `ACTIVE` mientras los tipos estén soportados;
- el Administrador no las crea, modifica ni desactiva;
- `ConfigureResourcePriority` es aprovisionamiento interno idempotente;
- al crear una fila por primera vez se genera `ResourcePriorityConfigured`.

La implementación no debe sembrar las filas de forma que se pierda el Event inicial. El enfoque esperado es un bootstrap interno idempotente que use el mismo caso de uso y Outbox que el resto del servicio, no un INSERT de negocio invisible a consumidores.

## Semántica De Capacidad

`Tank.configuredCapacity` significa:

> máximo de alumnos que pueden utilizar simultáneamente una tina durante un mismo bloque.

Consumo:

```text
1 Appointment / 1 WashExecution activa
= 1 unidad de capacidad
```

`instrumentCount` no cambia el consumo.

No existe:

```text
Cabin.configuredCapacity
```

Capacidad administrativa derivada:

```text
CabinCapacity
= SUM(Tank.configuredCapacity)
  de tinas administrativamente utilizables
```

Para agenda, Appointments combina esta configuración con su propia demanda y con proyecciones operativas de Execution. Wash Administration no calcula disponibilidad reservable final.

## Commands V1

Commands administrativos desde BFF:

```text
CreateServiceCalendar
UpdateServiceCalendar
ActivateServiceCalendar
DeactivateServiceCalendar

ConfigureServiceSchedule
UpdateServiceSchedule
DeactivateServiceSchedule

ScheduleAdministrativeClosure
CancelAdministrativeClosure

RegisterCabin
UpdateCabin
ActivateCabin
DeactivateCabin
RetireCabin

RegisterTank
UpdateTank
ActivateTank
DeactivateTank
RetireTank
```

Command interno:

```text
ConfigureResourcePriority
```

No existen:

```text
UpdateResourcePriority
DeactivateResourcePriority
CreateAppointmentTimeSlot
CancelAppointmentTimeSlot
ScheduleAppointment
CancelAppointment
DisableOperationalResource
RestoreOperationalResource
ResolvePendingReassignment
CancelWashExecutionByClinic
```

## Permission Matrix V1

Cada Command administrativo exige acceso `ACTIVE` a `LAVADO_ULTRASONICO` y su permiso específico:

```text
CreateServiceCalendar          → wash.admin.calendar.create
UpdateServiceCalendar          → wash.admin.calendar.update
ActivateServiceCalendar        → wash.admin.calendar.activate
DeactivateServiceCalendar      → wash.admin.calendar.deactivate
ConfigureServiceSchedule       → wash.admin.schedule.configure
UpdateServiceSchedule          → wash.admin.schedule.update
DeactivateServiceSchedule      → wash.admin.schedule.deactivate
ScheduleAdministrativeClosure  → wash.admin.closure.schedule
CancelAdministrativeClosure    → wash.admin.closure.cancel
RegisterCabin                  → wash.admin.cabin.register
UpdateCabin                    → wash.admin.cabin.update
ActivateCabin                  → wash.admin.cabin.activate
DeactivateCabin                → wash.admin.cabin.deactivate
RetireCabin                    → wash.admin.cabin.retire
RegisterTank                   → wash.admin.tank.register
UpdateTank                     → wash.admin.tank.update
ActivateTank                   → wash.admin.tank.activate
DeactivateTank                 → wash.admin.tank.deactivate
RetireTank                     → wash.admin.tank.retire
```

`ConfigureResourcePriority` no usa permiso administrativo humano.

## Autorización Local

El BFF valida sesión/token y acceso general, pero el servicio propietario valida el permiso específico.

V1 combina:

```text
AccountRole projection
+
SystemAccess projection para LAVADO_ULTRASONICO
+
matriz estática Role → Permission V1
```

Eventos de Authorization consumidos:

```text
AccountRoleAssigned
AccountRoleChanged
SystemAccessGranted
SystemAccessSuspended
SystemAccessRevoked
SystemAccessRestored
```

Authorization V1 no publica cambios dinámicos de permisos porque el catálogo se carga mediante configuración/migración controlada. No se debe inventar una `permission_projection` que no pueda mantenerse.

Si en una versión futura los permisos se vuelven dinámicos, primero debe existir un Event contractual de Authorization y después podrá agregarse la proyección correspondiente.

Ante proyección insuficiente para demostrar autorización:

```text
fail closed
→ ACTOR_NOT_AUTHORIZED
```

No existe fallback síncrono hacia `authorization-service`.

## ActorContext Y Auditoría

Para Commands de BFF:

```text
producer = bff
actor = ACCOUNT { accountId }
```

`requestedByAccountId` no existe en payload.

Campos locales como:

```text
createdByAccountId
updatedByAccountId
cancelledByAccountId
```

se derivan de `ActorContext.accountId` cuando el actor es humano.

No se transportan nombre, correo, teléfono, JWT, token ni perfil completo.

## Optimistic Concurrency

Toda modificación sobre un agregado existente recibe `expectedVersion`.

La persistencia debe aplicar la actualización de forma atómica:

```text
UPDATE ...
SET ..., version = version + 1
WHERE id = :id
  AND version = :expectedVersion
```

Cero filas actualizadas por conflicto de versión produce:

```text
VERSION_CONFLICT
```

No debe implementarse como read-then-write sin protección atómica.

Creaciones se protegen adicionalmente mediante claves únicas de base de datos.

Las invariantes de solapamiento y retiro deben ser race-safe. Una simple consulta previa sin lock/constraint no es suficiente. La implementación puede usar constraints PostgreSQL, locks transaccionales o aislamiento equivalente, pero debe incluir pruebas concurrentes que demuestren la garantía.

## Rechazos Funcionales

Catálogo mínimo contractual:

```text
ACTOR_NOT_AUTHORIZED
VERSION_CONFLICT
INVALID_STATE

SERVICE_CALENDAR_NOT_FOUND
SERVICE_CALENDAR_CODE_ALREADY_EXISTS
SERVICE_CALENDAR_INVALID_PERIOD
SERVICE_CALENDAR_OVERLAP
SERVICE_CALENDAR_HAS_NO_ACTIVE_SCHEDULES

SERVICE_SCHEDULE_NOT_FOUND
SERVICE_SCHEDULE_INVALID_DAY
SERVICE_SCHEDULE_INVALID_INTERVAL
SERVICE_SCHEDULE_INVALID_SLOT_DURATION
SERVICE_SCHEDULE_OVERLAP
SERVICE_SCHEDULE_DOES_NOT_PRODUCE_COMPLETE_SLOTS

ADMINISTRATIVE_CLOSURE_NOT_FOUND
ADMINISTRATIVE_CLOSURE_INVALID_PERIOD
ADMINISTRATIVE_CLOSURE_INVALID_SCOPE
ADMINISTRATIVE_CLOSURE_DUPLICATED

CABIN_NOT_FOUND
CABIN_CODE_ALREADY_EXISTS
CABIN_INVALID_STATE
CABIN_HAS_NON_RETIRED_TANKS

TANK_NOT_FOUND
TANK_CODE_ALREADY_EXISTS_IN_CABIN
TANK_INVALID_CAPACITY
TANK_INVALID_STATE
CABIN_RETIRED

RESOURCE_PRIORITY_DUPLICATED
RESOURCE_PRIORITY_NOT_IN_FIXED_MATRIX
```

Los rechazos funcionales no son fallos técnicos y no deben entrar en retry/DLQ por esa razón.

## Query V1

Única Query autoritativa:

```text
GetWashAdministrationConfiguration
```

Requesters autorizados V1:

```text
appointments
wash-execution
```

Uso:

- bootstrap de una proyección vacía;
- reconciliación explícita;
- recuperación de una proyección perdida/corrupta.

No se usa para:

- pantallas del BFF;
- validar cada cita;
- validar cada asignación;
- sustituir Events.

Entrada:

```text
referenceDate? // opcional
```

Resultado `SUCCEEDED.data` contiene snapshot consistente de:

```text
serviceCalendars[]
serviceSchedules[]
administrativeClosures[]
cabins[]
tanks[]
resourcePriorities[]
snapshotGeneratedAt
```

No existe capacidad propia en `cabins[]`.

La Query es side-effect-free y no requiere idempotencia funcional de Command.

## Events Producidos

```text
ServiceCalendarCreated
ServiceCalendarUpdated
ServiceCalendarActivated
ServiceCalendarDeactivated

ServiceScheduleConfigured
ServiceScheduleUpdated
ServiceScheduleDeactivated

AdministrativeClosureScheduled
AdministrativeClosureCancelled

CabinRegistered
CabinUpdated
CabinActivated
CabinDeactivated
CabinRetired

TankRegistered
TankUpdated
TankActivated
TankDeactivated
TankRetired

ResourcePriorityConfigured
```

No existen:

```text
AdministrativeClosureCompleted
ResourcePriorityUpdated
ResourcePriorityDeactivated
```

Cada Event asociado a agregado versionado conserva identidad y versión de origen. Un consumidor atrasado no debe sobrescribir una versión más nueva.

## Efectos Distribuidos

Wash Administration publica hechos, no instrucciones.

```text
cambio administrativo
→ commit local + Outbox
→ Event
→ consumidor actualiza su proyección
```

El servicio nunca:

- cancela una cita directamente;
- escribe `AppointmentTimeSlot`;
- finaliza una ejecución activa;
- reasigna físicamente a un alumno.

Appointments decide el efecto sobre sus propios bloques/citas. Execution decide el efecto operativo sobre futuras asignaciones y fallas.

## Mensajería Durable

Envelope transversal obligatorio:

```text
messageId
messageType
messageVersion
messageKind
producer
occurredAt
correlationId
causationId?
operationId?
idempotencyKey?
aggregateId?
aggregateVersion?
actor
payload
```

No existe `targetService` dentro del envelope.

Semántica:

```text
messageId      → deduplicación de entrega / Consumer Inbox
idempotencyKey → identidad de intención funcional
operationId    → seguimiento observable
```

No son intercambiables.

Commands durables de cliente exigen `idempotencyKey`. La misma intención con nuevo `messageId` no puede repetir mutación ni Events funcionales.

## Results

Los Commands y la Query responden por `platform.results`.

Forma transversal:

```text
messageType = <OperationName>Result
messageKind = RESULT
payload.status = SUCCEEDED | REJECTED | FAILED | EXPIRED
payload.data?
payload.error?
```

Los nombres `...Succeeded` / `...Rejected` usados narrativamente en algunos documentos de dominio se interpretan como desenlaces conceptuales del mismo Result, no como Events ni como contratos wire independientes.

Para Commands desde BFF, el Result se enruta al requester `bff`.

Para Queries service-to-service, el Result se enruta al `producer` requester original.

## Transactional Boundary

Para un Command durable aplicado:

```text
BEGIN

Consumer Inbox
+ command idempotency record
+ validación de autorización local
+ mutación de dominio
+ auditoría local necesaria
+ Result Outbox
+ Event(s) Outbox

COMMIT
ACK
```

Un rechazo funcional válido debe persistir el Result terminal correspondiente y después ACK; no se vuelve retry técnico.

Una falla que impide confirmar la transacción no produce ACK prematuro.

## Persistence Boundary

El servicio escribe únicamente en su PostgreSQL propio.

Nombre lógico esperado:

```text
wash-administration-db
```

Tablas funcionales mínimas:

```text
service_calendars
service_schedules
administrative_closures
cabins
tanks
resource_priorities
```

Estado técnico mínimo:

```text
authorization_role_projection
authorization_access_projection
processed_messages
command_idempotency_records
outbox_messages
```

Puede existir una tabla de auditoría local si la implementación demuestra que es necesaria, pero no debe duplicar datos personales ni convertirse en fuente de Authorization.

No existen FKs a bases de otros servicios.

## Persistencia Técnica Elegida

Para este servicio se adopta:

```text
PostgreSQL
Spring JDBC / JdbcClient
Flyway
```

No JPA/Hibernate.

Motivos:

- optimistic locking explícito;
- constraints e invariantes SQL visibles;
- Consumer Inbox con `ON CONFLICT`;
- idempotencia funcional con unicidad compuesta;
- Outbox con claims/locks;
- consultas race-safe para invariantes;
- snapshot autoritativo de Query sin cargar grafos ORM implícitos.

Flyway:

```text
src/main/resources/db/migration/
V{n}__description.sql
```

Una migración aplicada nunca se reescribe.

## Idempotencia Funcional

Además de Inbox, Commands durables deben conservar una intención funcional persistente.

Clave recomendada:

```text
producer + operationType + idempotencyKey
```

Debe almacenarse un `requestFingerprint` canónico de los datos funcionales relevantes.

Misma clave + mismo fingerprint:

```text
→ reutilizar el resultado funcional
→ no repetir mutación
→ no incrementar version otra vez
→ no duplicar Events
```

Misma clave + fingerprint diferente:

```text
→ REJECTED
```

El código exacto de reutilización indebida debe alinearse con el vocabulario transversal que use el repositorio al momento de PR2; no debe inventarse de forma incompatible con otros servicios.

## Consumer Inbox

Clave mínima:

```text
consumerName + messageId
```

Aplica a:

- Commands durables consumidos;
- Events de Authorization consumidos.

Queries side-effect-free no necesitan el mismo mecanismo obligatorio para la solicitud, aunque su Result debe conservar correlación correcta.

ACK siempre después del commit local.

## Transactional Outbox

Toda salida durable se registra antes de publicar:

```text
EVENT
RESULT
```

La fila Outbox debe conservar suficiente metadata para reconstruir el envelope sin acceder a otra base.

El relay real se implementa hasta PR3.

PR1/PR2 pueden crear y probar la persistencia Outbox sin broker vivo.

## Arquitectura Hexagonal

Package root:

```text
com.estoma.washadministration
```

Estructura objetivo:

```text
com.estoma.washadministration
├── domain
│   ├── calendar
│   │   └── ServiceCalendar
│   ├── schedule
│   │   └── ServiceSchedule
│   ├── closure
│   │   └── AdministrativeClosure
│   ├── resource
│   │   ├── Cabin
│   │   └── Tank
│   ├── priority
│   │   └── ResourcePriority
│   └── shared
│       ├── value objects
│       ├── domain exceptions
│       └── domain event abstractions when justified
│
├── application
│   ├── command
│   │   ├── calendar
│   │   ├── schedule
│   │   ├── closure
│   │   ├── cabin
│   │   └── tank
│   ├── query
│   │   └── GetWashAdministrationConfiguration
│   ├── authorization
│   │   └── authorization decision/evaluator
│   ├── projection
│   │   └── authorization projection updaters
│   └── port
│       ├── in
│       └── out
│
├── infrastructure
│   ├── persistence
│   │   ├── jdbc
│   │   └── migration support
│   ├── messaging
│   │   ├── consumer
│   │   ├── publisher
│   │   ├── outbox
│   │   └── topology
│   ├── observability
│   └── config
│
└── contracts
    ├── command
    ├── query
    ├── result
    ├── event
    ├── consumed
    └── envelope
```

### Dependency Rules

```text
domain
→ no depende de Spring, JDBC, AMQP ni contracts wire

application
→ depende de domain + ports
→ no depende de infrastructure

infrastructure
→ implementa ports
→ puede depender de Spring/JDBC/AMQP

contracts
→ DTOs wire y vocabulario de integración
→ sin reglas de negocio
```

Se recomienda agregar pruebas ArchUnit en PR1 para impedir dependencias inversas.

No crear interfaces especulativas. Un port se agrega cuando existe un caso de uso real que lo requiere.

## Application Layer

Cada Command tiene un caso de uso explícito.

El handler/application service:

1. valida contexto técnico mínimo;
2. obtiene el actor del contexto de aplicación;
3. solicita decisión de autorización al port local;
4. carga los agregados necesarios;
5. aplica invariantes de dominio;
6. persiste usando expectedVersion cuando corresponde;
7. registra Result y Event(s) en Outbox dentro de la misma transacción;
8. devuelve un outcome interno sin depender de RabbitMQ.

PR2 debe poder probar todos los casos de uso directamente sin broker.

PR3 solo adapta mensajes wire a esos casos de uso; no reimplementa reglas.

## Internal Bootstrap De ResourcePriority

El bootstrap de las cinco filas debe ser:

- automático al levantar una instancia con esquema listo;
- idempotente;
- seguro con múltiples réplicas;
- ejecutado mediante un caso de uso interno;
- protegido por constraints de unicidad;
- capaz de registrar `ResourcePriorityConfigured` en Outbox exactamente una vez por fila.

Un segundo pod no debe generar eventos duplicados.

## HTTP Operativo

No existe HTTP de negocio.

Rutas operativas esperadas según el contrato de plataforma:

```text
GET /health/startup
GET /health/readiness
GET /health/liveness
GET /metrics
```

Se recomienda usar Actuator con base path `/` y health groups, siguiendo el patrón ya usado por servicios refinados del repositorio.

`/health` e `/info` pueden mantenerse como endpoints Actuator agregados si la configuración común del repositorio los requiere.

Debe eliminarse el controller temporal cuando Actuator cubra estas rutas.

Readiness debe reflejar como mínimo:

PR1/PR2:

- PostgreSQL disponible;
- migraciones compatibles.

PR3:

- PostgreSQL;
- RabbitMQ;
- command consumer;
- query consumer;
- event consumer de Authorization;
- Outbox Relay operativo.

## Seguridad HTTP

No se requiere una superficie REST funcional.

Una configuración Spring Security debe permitir únicamente los endpoints operativos explícitos y negar cualquier otra ruta HTTP.

No se implementa autenticación de cliente final dentro de este servicio.

## Observabilidad

Logs estructurados, sin payload completo.

Campos mínimos recomendados:

```text
service
operation
messageId
messageType
correlationId
operationId
aggregateId
actorType
actorAccountId cuando aplique
result
errorCode cuando aplique
duration
```

No registrar:

- payload completo de Commands;
- secretos;
- tokens;
- datos personales no necesarios.

Métricas mínimas esperadas:

```text
wash_administration_commands_received_total
wash_administration_commands_succeeded_total
wash_administration_commands_rejected_total
wash_administration_commands_failed_total
wash_administration_messages_duplicate_total
wash_administration_outbox_pending
wash_administration_outbox_failed_total
wash_administration_projection_lag_seconds
wash_administration_authorization_denied_total
wash_administration_query_total
```

Los nombres pueden alinearse con la convención Micrometer final en PR3, pero el significado no debe cambiar.

## Testing Strategy

### Unit

- agregados y value objects sin Spring;
- transiciones de estados;
- validación de periodos;
- capacidad positiva;
- matriz fija de prioridad;
- invariantes de retiro;
- reglas de cierre;
- `Role → Permission` estático.

### PostgreSQL Integration

Con Testcontainers:

- Flyway completo;
- constraints;
- repositories JdbcClient;
- optimistic locking;
- carreras de códigos únicos;
- carreras de calendarios activos solapados;
- carreras de horarios solapados;
- retiro de cabina con tinas no retiradas;
- Consumer Inbox;
- idempotencia funcional;
- Outbox atómico;
- snapshot consistente de Query.

### Application Integration

Sin RabbitMQ:

- los 19 Commands administrativos;
- rechazo de actor sin acceso/permiso;
- bootstrap de ResourcePriority;
- misma idempotency key no duplica efecto;
- VersionConflict;
- Result + Events en misma transacción.

### Messaging Integration

Solo PR3, con RabbitMQ Testcontainers:

- Command real → handler → Result + Event;
- Query real → snapshot Result;
- Authorization Event → proyección local;
- duplicate delivery → una sola aplicación;
- retry/DLQ técnico;
- Outbox Relay con publisher confirms;
- routing por `logicalComponentId`.

## Prohibited Behavior

La implementación no debe:

- introducir JPA/Hibernate sin una decisión explícita que reabra este contrato;
- exponer REST de negocio;
- llamar HTTP/gRPC a otro microservicio;
- acceder a otra base de datos;
- almacenar `AcademicPeriod`;
- almacenar capacidad en `Cabin`;
- interpretar `instrumentCount` como capacidad;
- crear o cancelar `AppointmentTimeSlot`;
- cancelar `Appointment` directamente;
- modificar una `WashExecution`;
- representar una falla operativa como estado administrativo;
- retirar cabina en cascada;
- permitir reactivación de `RETIRED`;
- permitir editar códigos inmutables;
- permitir al administrador editar `ResourcePriority`;
- confiar únicamente en que BFF autorizó la operación;
- hacer fallback síncrono a Authorization;
- publicar Event antes del commit local;
- ACK antes del commit;
- reutilizar `messageId` como `idempotencyKey`;
- convertir RabbitMQ en Event Store;
- duplicar reglas de negocio dentro de listeners de RabbitMQ.

## Gate De Contratos Para PR3

PR3 está bloqueado por las decisiones de contrato registradas en `docs/pr3_contract_decisions.md`. Deben resolverse en `estomatologia-platform-docs`; este repositorio no debe inventar reglas wire ni topologías de compatibilidad.

Existe además una inconsistencia documental de naming que **no debe resolverse dentro del código**:

El contrato específico del servicio documenta:

```text
commands.wash-administration-service
queries.wash-administration-service
```

Mientras `messaging-architecture.md` define:

```text
logicalComponentId = wash-administration
commands.<logicalComponentId>
queries.<logicalComponentId>
```

lo que implicaría:

```text
commands.wash-administration
queries.wash-administration
```

Antes de PR3 debe corregirse la fuente canónica y completarse la checklist de decisiones para que exista una sola convención. PR3 no puede introducir aliases permanentes ni cablear ambos nombres para ocultar la contradicción.

Hasta ese gate, PR1 y PR2 permanecen broker-agnostic y no dependen del nombre físico de cola.

## Reglas De Entrega

- La implementación se divide en exactamente tres PRs después de este PR documental.
- PR1 y PR2 son del servicio puro y deben funcionar sin RabbitMQ vivo.
- PR3 contiene contratos wire y conexiones reales.
- Ningún PR debe mezclar cambios de Appointments o Wash Execution.
- Cada PR debe compilar con `./mvnw clean verify`.
- No se agregan secretos al repositorio.
- Las decisiones canónicas de negocio no se reinterpretan durante implementación.
- Si el agente detecta contradicción con `estomatologia-platform-docs`, debe detener solamente el comportamiento afectado, documentar el conflicto y corregir primero la fuente canónica.

## Definition Of Done Del Servicio Completo

Después de los tres PRs:

- todos los Commands V1 están implementados;
- la Query V1 está implementada;
- PostgreSQL/Flyway representa todas las invariantes V1;
- la arquitectura hexagonal está protegida por tests de arquitectura;
- Authorization se valida localmente sin RPC;
- Inbox, idempotencia funcional y Outbox son durables;
- los Events producidos respetan las versiones de origen;
- RabbitMQ está cableado con los contratos canónicos corregidos;
- Appointments y Execution pueden bootstrap/reconciliar sus proyecciones mediante la Query;
- no hay HTTP funcional;
- `./mvnw clean verify` pasa;
- tests PostgreSQL y RabbitMQ cubren concurrencia, redelivery y publicación;
- el servicio es desplegable de manera independiente.
