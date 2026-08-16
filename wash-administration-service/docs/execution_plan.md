# wash-administration-service Execution Plan

## Objetivo

Implementar `wash-administration-service` con arquitectura hexagonal mediante **exactamente tres PRs de implementación** después de este PR documental:

```text
PR1 → dominio + persistencia + foundation
PR2 → comportamiento completo del servicio sin broker
PR3 → contratos wire + RabbitMQ + conexiones externas del servicio
```

Este PR que agrega `docs/system_contract.md` y `docs/execution_plan.md` es preparatorio y **no cuenta como uno de los tres PRs de implementación**.

## Política De Branches Y PRs

- Todos los PRs apuntan a `develop`.
- No hacer commits directos a `develop`.
- Los PRs son secuenciales: PR2 parte de `develop` después de mergear PR1; PR3 parte de `develop` después de mergear PR2.
- Cada PR debe compilar y pasar `./mvnw clean verify` por sí mismo.
- Cada PR debe incluir pruebas propias suficientes para su alcance.
- No introducir secretos.
- No mezclar cambios funcionales de Appointments o Wash Execution.
- No modificar reglas canónicas de negocio desde el repositorio de implementación.
- Si el código descubre una contradicción contractual, corregir primero `estomatologia-platform-docs` y reanudar después el comportamiento afectado.

Nombres sugeridos:

```text
PR1 branch: feat/wash-administration-domain-persistence
PR2 branch: feat/wash-administration-application
PR3 branch: feat/wash-administration-messaging-contracts
```

Los nombres pueden variar sin cambiar el alcance.

---

# PR1 — Domain, Persistence And Hexagonal Foundation

## Propósito

Construir un servicio funcionalmente vacío pero técnicamente sólido: dominio puro, modelo PostgreSQL, repositorios, invariantes de persistencia, seguridad HTTP operativa y enforcement de arquitectura.

Al finalizar PR1 todavía no existen handlers completos de Commands ni RabbitMQ.

## Scope

### 1. Dependencias

Agregar las dependencias necesarias para la foundation:

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

`spring-boot-starter-webmvc` puede conservarse únicamente para infraestructura Actuator/configuración existente; no autoriza REST de negocio.

Test:

```text
spring-boot-testcontainers
testcontainers-junit-jupiter
testcontainers-postgresql
ArchUnit
```

No agregar AMQP en PR1.

### 2. Arquitectura Hexagonal

Crear el package root y las fronteras de `system_contract.md`:

```text
com.estoma.washadministration
├── domain
├── application
├── infrastructure
└── contracts
```

En PR1:

- `domain` contiene modelo real;
- `infrastructure.persistence` contiene adapters JDBC;
- `application` puede contener ports realmente necesarios por repositories/foundation, pero no handlers funcionales completos;
- `contracts` permanece vacío o con `package-info.java`; no definir DTOs wire prematuramente.

Agregar tests ArchUnit:

```text
domain !-> Spring/JDBC/AMQP/contracts
application !-> infrastructure
contracts !-> domain business implementation
```

### 3. Dominio

Implementar agregados y value objects para:

```text
ServiceCalendar
ServiceSchedule
AdministrativeClosure
Cabin
Tank
ResourcePriority
```

Enums mínimos:

```text
ServiceCalendarStatus = DRAFT | ACTIVE | INACTIVE
ServiceScheduleStatus = ACTIVE | INACTIVE
AdministrativeClosureStatus = SCHEDULED | CANCELLED
AdministrativeClosureScope = SERVICE | CABIN | TANK
CabinType = NORMAL | JOURNEY | IMMUNOCOMPROMISED
ResourceStatus = ACTIVE | INACTIVE | RETIRED
UsageMode = PREFERRED | ALTERNATIVE | EXCLUSIVE
AppointmentType = NORMAL | JOURNEY | IMMUNOCOMPROMISED
```

El dominio debe contener invariantes que no requieren consultar otros agregados.

Ejemplos:

- capacidad positiva;
- intervalos válidos;
- código inmutable por diseño;
- `RETIRED` irreversible;
- scopes de cierre consistentes;
- matriz de prioridad válida;
- `EXCLUSIVE` solo para la combinación confirmada.

### 4. PostgreSQL + Flyway

Crear migraciones aditivas para:

Funcional:

```text
service_calendars
service_schedules
administrative_closures
cabins
tanks
resource_priorities
```

Foundation técnica:

```text
authorization_role_projection
authorization_access_projection
processed_messages
command_idempotency_records
outbox_messages
```

No existen FKs a otras bases.

No sembrar las cinco `ResourcePriority` mediante un INSERT de negocio invisible a Outbox. PR1 crea el schema y constraints; PR2 hace el bootstrap mediante caso de uso interno.

### 5. Constraints

Implementar al menos:

```text
unique service_calendars(code)
unique cabins(code)
unique tanks(cabin_id, code)
check tanks.configured_capacity > 0
check version > 0
check de AdministrativeClosure scope/references
unique ResourcePriority appointment_type + cabin_type
unique ResourcePriority appointment_type + priority_order
```

Los conflictos de calendarios/horarios deben tener una estrategia race-safe. El agente puede elegir constraint PostgreSQL, lock transaccional o mecanismo equivalente, pero debe probar concurrencia real; no se acepta solo un pre-check sin protección.

### 6. Repositories

Implementar ports y adapters JDBC únicamente para necesidades reales:

```text
ServiceCalendarRepository
ServiceScheduleRepository
AdministrativeClosureRepository
CabinRepository
TankRepository
ResourcePriorityRepository
AuthorizationRoleProjectionRepository
AuthorizationAccessProjectionRepository
ProcessedMessageRepository
CommandIdempotencyRepository
OutboxRepository
```

Usar `JdbcClient`/Spring JDBC.

No JPA/Hibernate.

Optimistic locking mediante update condicionado por `expectedVersion`.

### 7. HTTP Operativo

Eliminar `SystemController` cuando Actuator cubra su responsabilidad.

Configurar:

```text
/health/startup
/health/readiness
/health/liveness
/metrics
```

Puede conservarse `/health` e `/info` como endpoints Actuator agregados.

Spring Security debe negar cualquier otra ruta HTTP.

Readiness en PR1:

```text
PostgreSQL
Flyway/schema compatible
```

### 8. Observabilidad Foundation

Configurar logging estructurado y build-info sin payloads completos.

No es necesario implementar todavía métricas de RabbitMQ.

## Out Of Scope PR1

- handlers de los 19 Commands;
- bootstrap real de ResourcePriority;
- `GetWashAdministrationConfiguration` completo;
- evaluación funcional de autorización de cada Command;
- dispatcher de Commands;
- wire DTOs;
- Rabbit listeners;
- Rabbit publishers;
- Outbox Relay;
- RabbitMQ topology;
- retry/DLQ real;
- conexiones con BFF/Appointments/Execution.

## Acceptance Criteria PR1

- `./mvnw clean verify` pasa.
- Testcontainers PostgreSQL ejecuta todas las migraciones.
- No existe JPA/Hibernate.
- Domain no depende de Spring/JDBC/AMQP.
- ArchUnit protege las dependencias hexagonales.
- Repositories tienen integration tests.
- Optimistic locking está probado.
- Un `Cabin` no tiene campo de capacidad.
- `Tank.configuredCapacity` es positivo y persistido.
- Retiro/activación de estados inválidos falla en dominio o application foundation correspondiente.
- Constraints de claves únicas funcionan bajo carrera.
- Solapamientos relevantes tienen prueba concurrente.
- No existen endpoints HTTP de negocio.
- No existe dependencia AMQP.

---

# PR2 — Complete Service Behavior Without RabbitMQ

## Propósito

Terminar todo el comportamiento de negocio y aplicación del microservicio usando PostgreSQL real, pero manteniéndolo broker-agnostic.

Al finalizar PR2, toda regla del servicio debe poder probarse llamando directamente a Application. PR3 únicamente conectará contratos externos a comportamiento ya probado.

## Scope

### 1. Application Commands

Implementar casos de uso para los 19 Commands administrativos:

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

Y el caso de uso interno:

```text
ConfigureResourcePriority
```

Cada use case recibe modelos internos de aplicación; todavía no recibe DTOs AMQP.

### 2. Authorization Evaluator

Implementar un port/use case local que evalúe:

```text
actor ACCOUNT conocido
AND SystemAccess LAVADO_ULTRASONICO = ACTIVE
AND role compatible
AND permiso requerido presente en matriz estática V1
```

La matriz `Role → Permission` de V1 puede ser código/configuración interna versionada porque Authorization no administra permisos dinámicamente en V1.

No hacer RPC/HTTP a Authorization.

### 3. Projection Updaters

Implementar la lógica application-level que actualiza:

```text
authorization_role_projection
authorization_access_projection
```

Debe aceptar un modelo interno de evento y aplicar monotonía por versión.

En PR2 se prueba invocando directamente el updater; el Rabbit listener llega en PR3.

### 4. Transactional Command Dispatcher

Crear un dispatcher broker-agnostic capaz de recibir:

```text
CommandMetadata
+
internal command payload
```

Metadata interna mínima:

```text
messageId
producer
correlationId
operationId
idempotencyKey
actor
```

Responsabilidades:

1. deduplicar entrega mediante Inbox;
2. validar/reutilizar idempotencia funcional;
3. ejecutar el caso de uso;
4. persistir mutation + auditoría necesaria;
5. persistir Result Outbox;
6. persistir Event(s) Outbox;
7. confirmar todo dentro de la misma transacción local.

No publicar a RabbitMQ todavía.

### 5. Results Internos

Definir un modelo interno:

```text
SUCCEEDED
REJECTED
FAILED
EXPIRED
```

PR2 debe generar el snapshot funcional que PR3 mapeará al Result wire.

Los rechazos funcionales deben conservar su `errorCode` contractual y no producir Event de mutación.

### 6. Idempotencia

Implementar:

```text
producer + operationType + idempotencyKey
requestFingerprint
terminal result snapshot
```

Tests obligatorios:

- mismo `messageId` → no repetir;
- distinto `messageId`, misma idempotency key + mismo fingerprint → no repetir;
- misma key + fingerprint distinto → rechazo controlado;
- retry no incrementa `version`;
- retry no duplica Outbox Events.

### 7. Todos Los Invariantes Cross-Aggregate

Implementar y probar:

- activar calendario solo con horario activo;
- no solapar calendarios activos;
- no solapar horarios activos del mismo calendar/day;
- programar cierre solo con resource válido/no retirado;
- desactivar Cabin no cambia estado de Tank;
- reactivar Cabin no reactiva Tanks retiradas/inactivas;
- retirar Cabin exige todas sus Tanks `RETIRED`;
- actualizar Tank no cambia Cabin ni code;
- reducir capacidad no modifica ejecuciones externas;
- ningún handler intenta escribir en Appointments/Execution.

### 8. ResourcePriority Bootstrap

Implementar bootstrap interno:

```text
5 filas fijas
→ idempotente
→ seguro con múltiples instancias
→ usa ConfigureResourcePriority
→ genera ResourcePriorityConfigured Outbox solo al crear
```

Debe existir una prueba de concurrencia/múltiple invocación.

### 9. Query Use Case

Implementar application use case:

```text
GetWashAdministrationConfiguration
```

Sin transporte RabbitMQ todavía.

Debe devolver snapshot consistente con:

```text
snapshotGeneratedAt
serviceCalendars[]
serviceSchedules[]
administrativeClosures[]
cabins[]
tanks[]
resourcePriorities[]
```

Opcionalmente acepta `referenceDate`.

No incluye:

- Appointments;
- Execution state;
- Academic data;
- Authorization data;
- `Cabin.configuredCapacity`.

### 10. Outbox Persistence Completa

PR2 debe dejar filas `EVENT`/`RESULT` completamente serializables de manera neutral, pero no debe depender de AMQP.

El mapper wire definitivo se añade en PR3.

## Out Of Scope PR2

- `spring-boot-starter-amqp`;
- RabbitMQ Testcontainers;
- exchange/queue declarations;
- `@RabbitListener`;
- Outbox Relay publicando a broker;
- routing keys reales;
- retry queues/DLQ;
- DTOs wire finales;
- implementación del productor BFF;
- cambios en Appointments;
- cambios en Wash Execution.

## Acceptance Criteria PR2

- `./mvnw clean verify` pasa.
- Los 19 Commands administrativos tienen tests de éxito/rechazo.
- `ConfigureResourcePriority` provisiona exactamente 5 filas.
- Authorization local es fail-closed.
- Todos los Commands pueden probarse sin RabbitMQ.
- Mutation + Inbox + idempotency + Result Outbox + Event Outbox comparten transacción.
- Un fallo transaccional no deja mutation sin Outbox ni Outbox sin mutation.
- La Query produce snapshot consistente.
- No existe capacidad en Cabin.
- Cada Tank conserva capacidad de alumnos simultáneos.
- Ninguna operación escribe datos propiedad de Appointments/Execution.
- Tests de concurrencia cubren los invariantes críticos.
- No existe dependencia AMQP.

---

# PR3 — Messaging Contracts And External Connections

## Propósito

Agregar la superficie de integración real del servicio sobre el comportamiento ya terminado en PR2.

Este PR contiene:

```text
contratos wire
+
RabbitMQ
+
listeners
+
Outbox Relay
+
Query transport
+
consumo de eventos Authorization
+
contract/integration tests
```

No debe reescribir reglas de dominio.

## Gate Contractual Resuelto

`estomatologia-platform-docs` resolvió la nomenclatura en el commit canónico
`459fdd4`. PR3 usa exclusivamente:

```text
logicalComponentId = wash-administration
commands.wash-administration
queries.wash-administration
events.wash-administration
results.wash-administration
```

No se implementan aliases permanentes ni topologías paralelas con sufijo
`-service`. Los requesters de `GetWashAdministrationConfiguration` son
`appointments` y `wash-execution`; el detalle queda en la fuente canónica de
plataforma y en `pr3-contract-gate.md`.

## Scope

### 1. Dependencies

Agregar:

```text
spring-boot-starter-amqp
testcontainers-rabbitmq
```

Agregar RabbitMQ al entorno local `docker-compose.yml` del servicio si se adopta la misma convención de los servicios maduros.

### 2. Contracts Package

Implementar:

```text
contracts/envelope
contracts/command
contracts/query
contracts/result
contracts/event
contracts/consumed
```

DTOs wire no contienen reglas de negocio.

Envelope exacto según arquitectura transversal:

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

No `targetService`.

### 3. Commands Wire

Contratos para los 19 Commands administrativos.

Producer esperado:

```text
bff
```

Actor esperado:

```text
ACCOUNT { accountId }
```

El listener solo decodifica/valida estructura y delega al dispatcher de PR2.

No duplicar reglas funcionales en el adapter.

### 4. Query Wire

Contrato:

```text
GetWashAdministrationConfiguration
```

Requesters permitidos:

```text
appointments
wash-execution
```

El Query listener:

- valida producer técnico permitido;
- mapea `referenceDate?`;
- invoca el use case de PR2;
- publica Result correlacionado al requester.

No requiere idempotency key de Command.

### 5. Result Wire

Forma:

```text
<OperationName>Result
status = SUCCEEDED | REJECTED | FAILED | EXPIRED
data?
error?
```

Routing:

```text
Result routing key = producer del Command/Query original
```

No `replyTo` durable.

### 6. Produced Events

Implementar wire contracts para:

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

Routing keys deben seguir la convención final aprobada:

```text
wash-administration.<subject>.<event>
```

Los nombres exactos de subject/event se fijan en PR3 contra la documentación canónica corregida y quedan cubiertos por contract tests.

### 7. Consumed Authorization Events

Listeners para:

```text
AccountRoleAssigned
AccountRoleChanged
SystemAccessGranted
SystemAccessSuspended
SystemAccessRevoked
SystemAccessRestored
```

El listener delega a los projection updaters de PR2.

No existe consumer de Permission events V1.

### 8. RabbitMQ Topology

Implementar:

```text
platform.commands
platform.queries
platform.results
platform.events
platform.dlx

command queue del servicio
query queue del servicio
events.wash-administration
DLQs/retry topology según arquitectura canónica
```

La cola de eventos es por consumidor desplegable, no por tabla ni por Event.

### 9. Command/Query/Event Listeners

Requisitos:

- manual ACK;
- ACK después del commit;
- duplicate-safe;
- separación functional rejection vs technical failure;
- retry solo técnico;
- DLQ conforme a política transversal;
- mensajes inválidos no ejecutan dominio.

### 10. Outbox Relay

Implementar publicación real de:

```text
EVENT
RESULT
```

Requisitos:

- claim transaccional;
- seguro con múltiples réplicas;
- publisher confirms;
- redelivery tolerable por `messageId`;
- retry/backoff según arquitectura vigente;
- filas no se marcan `PUBLISHED` antes del confirm.

### 11. Readiness

Agregar indicadores para:

```text
RabbitMQ
command consumer
query consumer
Authorization event consumer
Outbox Relay
```

### 12. Integration/Contract Tests

Con PostgreSQL + RabbitMQ Testcontainers:

1. Command real de BFF → Result + Event.
2. Command duplicado → un solo efecto.
3. Query de Appointments → snapshot Result.
4. Query de requester no autorizado → REJECTED.
5. Authorization Event → proyección local → Command autorizado/rechazado correctamente.
6. Evento Authorization duplicado → no duplica cambio.
7. Event producido lleva `aggregateVersion` correcto.
8. Outbox Relay publica con publisher confirm.
9. fallo técnico alcanza retry/DLQ según política.
10. no existe REST/gRPC funcional.

## Conexiones Que PR3 Sí Implementa

Dentro de `wash-administration-service`:

```text
BFF Command ingress
Authorization Event ingress
Appointments/Wash Execution Query ingress
Result egress
Configuration Event egress
RabbitMQ topology owned by this consumer/producer
Outbox Relay
```

## Conexiones Que PR3 No Debe Forzar

PR3 no debe convertir este trabajo en un refactor completo de consumidores.

No implementa dentro de Appointments o Wash Execution:

- sus proyecciones definitivas;
- su cálculo de capacidad;
- su lógica de cancelación por `CAPACITY_LOSS`;
- su lógica de asignación física.

Esos servicios consumen los contratos de Wash Administration cuando sean refinados en sus propios PRs.

Si se requiere un cambio mínimo en un módulo compartido de contratos para compilar la integración, debe ser estrictamente contractual y estar justificado en el PR; no incluir reglas de otro dominio.

## Acceptance Criteria PR3

- gate de naming resuelto en docs canónicas;
- `./mvnw clean verify` pasa;
- RabbitMQ Testcontainers cubre Commands, Queries, Results y Events;
- listener no contiene lógica de dominio;
- `messageId`, `idempotencyKey` y `operationId` mantienen semánticas separadas;
- Authorization events mantienen las proyecciones locales;
- ningún Permission event inexistente es asumido;
- Query solo acepta `appointments` y `wash-execution`;
- Result se enruta al requester original;
- Event routing usa `wash-administration` como contexto lógico;
- Outbox Relay es seguro ante múltiples réplicas;
- ACK solo después de commit;
- retry/DLQ solo aplica a fallos técnicos;
- no existe HTTP/gRPC de negocio;
- el servicio queda listo para que Appointments y Execution lo integren sin acceder a su DB.

---

# Orden De Merge

```text
Planning PR (este PR)
    ↓
PR1 Domain + Persistence
    ↓ merge develop
PR2 Application + Full Service Behavior
    ↓ merge develop
Resolver naming contractual de RabbitMQ si sigue pendiente
    ↓
PR3 Contracts + Messaging Connections
    ↓ merge develop
Servicio completo
```

No comenzar PR2 sobre una rama PR1 sin mergear si ello dificulta el review. No comenzar PR3 mientras el gate contractual de naming siga abierto.

# Regla Para El Agente Implementador

El agente tiene libertad para decisiones internas pequeñas de código mientras preserve `system_contract.md`.

Debe pedir/corregir fuente canónica antes de cambiar una regla que afecte:

- ownership;
- Commands/Queries/Events públicos;
- autorización;
- semántica de capacidad;
- estados/transiciones;
- contratos RabbitMQ;
- comportamiento esperado de otro microservicio.

No debe pedir aprobación para decisiones puramente internas ya cerradas por este plan, como:

- arquitectura hexagonal;
- Spring JDBC/JdbcClient;
- Flyway;
- PostgreSQL;
- Testcontainers;
- ArchUnit;
- ausencia de JPA;
- separación PR1/PR2/PR3;
- broker-agnostic PR1/PR2.
