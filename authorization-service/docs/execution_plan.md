# authorization-service Execution Plan

## Objetivo

Implementar `authorization-service` con arquitectura hexagonal mediante **exactamente tres PRs de implementación** después de este PR documental:

```text
PR1 → dominio + persistencia + foundation hexagonal
PR2 → comportamiento completo + recovery sin broker
PR3 → contratos wire ejecutables + RabbitMQ + conexiones externas
```

Este PR que agrega `docs/system_contract.md` y `docs/execution_plan.md` es preparatorio y **no cuenta como uno de los tres PRs de implementación**.

La intención de la división es explícita:

```text
PR1 + PR2
→ sólo construir el microservicio y su comportamiento local
→ cero dependencia funcional de RabbitMQ

PR3
→ conectar ese comportamiento ya probado con el resto de la plataforma
→ materializar contratos de comunicación y adapters
```

---

# Política De Branches Y PRs

- Todos los PRs apuntan a `develop`.
- No hacer commits directos a `develop`.
- Los PRs son secuenciales: PR2 parte de `develop` después de mergear PR1; PR3 parte de `develop` después de mergear PR2.
- Cada PR debe compilar y pasar `./mvnw clean verify` por sí mismo.
- Cada PR incluye pruebas suficientes para su alcance.
- No introducir secretos.
- No modificar reglas canónicas desde `estoma-services`.
- Si aparece contradicción con `estomatologia-platform-docs`, corregir primero el repo documental y continuar después.
- No refactorizar otros microservicios dentro de estos PRs salvo un cambio mínimo imprescindible de contrato explícitamente aprobado.
- Mantener Java/Spring Boot del scaffold salvo decisión transversal separada.
- Mantener arquitectura hexagonal y ArchUnit como gate desde PR1.

Nombres sugeridos:

```text
PR1 branch: feat/authorization-domain-persistence
PR2 branch: feat/authorization-application-recovery
PR3 branch: feat/authorization-messaging-contracts
```

Los nombres pueden variar sin cambiar el scope.

---

# PR1 — Domain, Persistence And Hexagonal Foundation

## Propósito

Construir un servicio técnicamente sólido pero todavía sin comportamiento completo de mensajería ni handlers funcionales terminados:

```text
dominio puro
+ PostgreSQL
+ Flyway
+ repositories
+ constraints/concurrencia
+ tablas técnicas
+ Actuator/Security operativo
+ enforcement hexagonal
```

Al finalizar PR1:

- existe un modelo persistente correcto;
- Domain no conoce Spring;
- PostgreSQL protege invariantes estructurales;
- no existe RabbitMQ;
- no existen contratos wire;
- los seis Commands todavía no tienen flujo completo de punta a punta.

## Scope

### 1. Dependencias De Foundation

Agregar únicamente lo necesario para foundation.

Producción:

```text
spring-boot-starter-jdbc
PostgreSQL driver
Flyway core + soporte PostgreSQL
spring-boot-starter-security
```

Mantener:

```text
spring-boot-starter-actuator
micrometer-registry-prometheus
```

`spring-boot-starter-webmvc` puede mantenerse únicamente por infraestructura operativa ya existente; no autoriza REST de negocio.

Test:

```text
spring-boot-testcontainers
testcontainers-junit-jupiter
testcontainers-postgresql
ArchUnit
```

No agregar en PR1:

```text
spring-rabbit
spring-boot-starter-amqp
RabbitMQ Testcontainers
AsyncAPI tooling
wire schema validators
```

### 2. Arquitectura Hexagonal

Crear package root:

```text
com.estoma.authorization
├── domain
├── application
├── infrastructure
└── contracts
```

En PR1:

```text
domain
→ modelo real

application
→ ports mínimos de persistencia/transaction necesarios para foundation

infrastructure.persistence
→ JdbcClient adapters

infrastructure.transaction
→ adapter de TransactionRunner

contracts
→ vacío o package-info; no DTO wire prematuro
```

ArchUnit debe impedir:

```text
domain -> Spring/JDBC/AMQP/Jackson/application/infrastructure/contracts
application -> infrastructure
application -> Spring AMQP
contracts -> repositories/domain implementation
```

### 3. Domain Model

Implementar modelos/value objects necesarios para:

```text
AccountRoleAssignment
SystemAccess
RoleCode
SystemCode
PermissionCode
LoginType
SystemAccessStatus
SystemAccessReasonCode
```

Reglas puras mínimas:

```text
MATRICULA -> ALUMNO
USERNAME -> roles de personal

SystemAccess:
ACTIVE -> SUSPENDED
ACTIVE/SUSPENDED -> REVOKED
SUSPENDED -> ACTIVE
REVOKED -> ACTIVE sólo bajo policy/use case autorizado

OTHER -> reasonDetail obligatorio
ROLE_CHANGED reservado para suspensión derivada
```

No meter repositorios dentro de Aggregate Roots.

### 4. Catálogos V1

Crear schema y seed controlado para:

```text
systems
roles
permissions
role_systems
role_permissions
```

Sistemas:

```text
LAVADO_ULTRASONICO
PRACTICAS_PROFESIONALES
```

Roles:

```text
ALUMNO
SUPERVISOR_LAVADO
ADMINISTRADOR_LAVADO
ADMINISTRADOR_PRACTICAS
SUPERADMIN
```

Sembrar exactamente la matriz Role → System y Role → Permission de `system_contract.md`/fuente canónica.

Los catálogos V1 son estáticos; seed por Flyway es válido y no representa una operación administrativa runtime.

### 5. Flyway — Modelo Funcional

Crear migraciones aditivas para:

```text
systems
roles
permissions
role_systems
role_permissions
account_roles
system_accesses
```

Campos físicos deben soportar:

- IDs UUID;
- códigos estables;
- `version`;
- estado;
- razones;
- actor ACCOUNT/SYSTEM denormalizado donde aplique;
- timestamps.

No FK cross-service.

### 6. Flyway — Proyecciones

Crear:

```text
person_status_projection
account_status_projection
academic_eligibility_projection
```

Con:

```text
sourceVersion/version funcional apropiada
updatedAt
claves lógicas de consulta
```

`academic_eligibility_projection` debe conservar `eligibilityVersion` independiente de la versión general del perfil.

### 7. Flyway — Persistencia Técnica

Crear:

```text
processed_messages
command_idempotency_records
outbox_messages
authorization_audit_entries
projection_recovery_jobs
```

El schema se crea completo desde PR1 aunque la lógica se termine en PR2/PR3.

Esto evita migraciones improvisadas cuando el comportamiento ya dependa de atomicidad.

### 8. Constraints E Índices

Implementar al menos:

```text
unique systems(code)
unique roles(code)
unique permissions(system_id, code)
unique account_roles(account_id)
unique system_accesses(account_id, system_id)
PK processed_messages(consumer_name, message_id)
PK command_idempotency_records(producer, operation_type, idempotency_key)
PK academic_eligibility_projection(student_academic_profile_id, system_code)
```

Checks:

```text
version > 0

actor ACCOUNT
→ actor_account_id NOT NULL
→ actor_service NULL

actor SYSTEM
→ actor_account_id NULL
→ actor_service NOT NULL

SUSPENDED/REVOKED
→ reason_code NOT NULL

reason_code = OTHER
→ reason_detail NOT NULL

outbox PROCESSING
→ claim_token + lease_until NOT NULL
```

Recovery:

- un FULL activo por `projection_type`;
- un ENTITY activo por `(projection_type, entity_key)`;
- `entity_key` nulo en FULL y requerido en ENTITY;
- `WAITING_RESULT` exige metadata de Query activa.

Usar índices parciales PostgreSQL si simplifican la invariante.

### 9. Repository Ports

Application ports mínimos:

```text
SystemCatalogRepository
RoleCatalogRepository
PermissionCatalogRepository
AccountRoleRepository
SystemAccessRepository
PersonStatusProjectionRepository
AccountStatusProjectionRepository
AcademicEligibilityProjectionRepository
ProcessedMessageRepository
CommandIdempotencyRepository
OutboxRepository
AuthorizationAuditRepository
ProjectionRecoveryJobRepository
TransactionRunner
```

No crear un repository genérico si las consultas/invariantes reales requieren semántica distinta.

### 10. JDBC Adapters

Implementar `infrastructure.persistence.jdbc` mediante `JdbcClient`/Spring JDBC.

Pruebas obligatorias:

- mapping DB ↔ modelo;
- insert/update;
- constraints;
- optimistic locking;
- query por `accountId`/`systemCode`;
- claims básicos del Outbox/recovery donde se implementen en PR1.

No H2.

### 11. Optimistic Locking

`account_roles`:

```text
UPDATE account_roles
SET ..., version = version + 1
WHERE account_id = :accountId
AND version = :expectedVersion
```

`system_accesses`:

```text
UPDATE system_accesses
SET ..., version = version + 1
WHERE system_access_id = :systemAccessId
AND version = :expectedVersion
```

El repository retorna una señal explícita de conflicto; no convierte cero filas en excepción técnica genérica.

### 12. Transaction Runner

Implementar un port framework-agnostic:

```text
TransactionRunner
```

Adapter Spring mediante `TransactionTemplate`.

No usar `@Transactional` en Domain.

Evitar que Application dependa de anotaciones Spring para expresar atomicidad.

### 13. Endpoints Operativos

Eliminar el `SystemController` temporal y sus tests cuando Actuator cubra su responsabilidad.

Configurar:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/prometheus
```

Opcional:

```text
/actuator/startup
```

No `/actuator/health/startup` custom.

Spring Security:

```text
permitir sólo endpoints operativos requeridos
negar cualquier otra ruta HTTP
```

Readiness PR1:

```text
PostgreSQL
+ schema/Flyway compatible
```

### 14. Observabilidad Foundation

Configurar:

- logs estructurados base;
- build info;
- métricas Actuator;
- sanitización de secretos/payloads.

No métricas RabbitMQ todavía.

## Out Of Scope PR1

- handlers completos de los seis Commands;
- authority matrix completa en runtime;
- aprovisionamiento de Prácticas;
- actualización funcional completa de proyecciones;
- Consumer Inbox aplicado a handlers reales;
- command idempotency completa;
- Outbox intents completos de todos los use cases;
- auditoría completa por operación;
- recovery orchestration;
- snapshot Queries completos;
- RabbitMQ;
- DTOs wire;
- AsyncAPI/JSON Schema;
- listeners/publishers;
- retry/DLQ.

## Acceptance Criteria PR1

- `./mvnw clean verify` pasa.
- Java/Spring Boot baseline se conserva.
- No existe JPA/Hibernate.
- No existe dependencia AMQP.
- Domain compila/probarse sin Spring.
- ArchUnit bloquea dependencias hexagonales inválidas.
- Flyway crea todo el schema en PostgreSQL limpio.
- Seed V1 de systems/roles/permissions es determinista.
- Testcontainers PostgreSQL valida migraciones y repositories.
- `account_roles(account_id)` evita dos roles concurrentes.
- `system_accesses(account_id, system_id)` evita dos accesos concurrentes.
- optimistic locking de rol y acceso está probado.
- checks de actor/reason están probados.
- `projection_recovery_jobs` tiene constraints de unicidad activa.
- no existen endpoints HTTP de negocio.
- `SystemController` temporal ya no es necesario.

---

# PR2 — Complete Application Behavior And Recovery Without Broker

## Propósito

Terminar **todo el comportamiento funcional y técnico local de Authorization** usando PostgreSQL real, pero todavía sin RabbitMQ ni contratos wire.

Al finalizar PR2:

```text
los seis Commands funcionan
+ autoridad funciona
+ proyecciones funcionan
+ aprovisionamiento de Prácticas funciona
+ Inbox/idempotency/Outbox/audit funcionan
+ recovery funciona mediante ports
+ snapshot Queries funcionan como use cases
```

Toda la lógica debe poder probarse invocando Application directamente.

PR3 sólo adapta mensajería externa a este comportamiento ya probado.

## Scope

### 1. Modelos Internos De Application

Crear modelos internos separados de wire DTOs:

```text
CommandMetadata
ActorContext
AssignAccountRoleCommand
ChangeAccountRoleCommand
GrantSystemAccessCommand
SuspendSystemAccessCommand
RevokeSystemAccessCommand
RestoreSystemAccessCommand

ApplicationResult
DomainEventIntent
OutboundResultIntent
OutboundQueryIntent
```

`CommandMetadata` representa semántica interna necesaria:

```text
messageId
producer
correlationId
causationId?
operationId
idempotencyKey
actor
```

No usar anotaciones JSON/AMQP.

### 2. Command Dispatcher Broker-Agnostic

Crear un puerto/caso de aplicación que reciba:

```text
CommandMetadata
+ internal command
```

Responsabilidades comunes:

```text
validar producer/ActorContext
→ Inbox
→ idempotency fingerprint
→ authority
→ use case
→ audit
→ Result intent
→ Event intent(s)
→ commit
```

En PR2 se llama directamente desde tests; listener llega en PR3.

### 3. `AssignAccountRole`

Implementar:

- Account projection necesaria;
- Role existente/ACTIVE;
- loginType compatibility;
- actor/scope;
- unicidad;
- versión inicial;
- `AccountRoleAssigned` intent;
- Result canónico.

Academic ensure:

```text
ALUMNO ya satisfecho
→ SUCCEEDED changed=false
→ sin mutación
→ sin Event funcional nuevo
```

### 4. `ChangeAccountRole`

Implementar en **una transacción**:

```text
validar SUPERADMIN BFF
validar expectedVersion/loginType
actualizar rol
buscar SystemAccess ACTIVE incompatibles
suspender cada uno con ROLE_CHANGED
AccountRoleChanged intent
N SystemAccessSuspended intents
Result
```

Pruebas:

- cero accesos incompatibles;
- uno;
- varios;
- rollback completo si una parte falla;
- conflicto concurrente;
- no auto-grant de acceso compatible nuevo.

### 5. `GrantSystemAccess`

Implementar BFF normal y Academic ensure/preserve.

BFF:

```text
preexistente -> REJECTED
```

Academic:

```text
sólo ALUMNO
sólo LAVADO_ULTRASONICO
relación preexistente cualquier estado
→ SUCCEEDED changed=false
→ sin restauración
```

Probar carrera de dos Grants contra unique `(account_id, system_id)`.

### 6. Suspend / Revoke / Restore

Implementar:

```text
SuspendSystemAccess
RevokeSystemAccess
RestoreSystemAccess
```

Con:

- `expectedVersion`;
- estado válido;
- reason rules;
- actor/scope;
- SUPERADMIN para restore desde REVOKED;
- Result + Event intents.

### 7. Authority Evaluator

Implementar un servicio/policy Application/Domain que use:

```text
Person projection confiable ACTIVE
+ Account projection confiable ACTIVE
+ actor role ACTIVE
+ producer allowlisted
+ target scope
+ operation
```

No consultar HTTP/RPC.

Matriz exacta de `system_contract.md`.

Casos negativos obligatorios:

- self-admin SUPERADMIN;
- target SUPERADMIN;
- ADMINISTRADOR_LAVADO intentando ChangeRole;
- ADMINISTRADOR_PRACTICAS intentando administrar V1;
- Academic intentando target/system/operation fuera de scope;
- projection ausente/no confiable.

### 8. Projection Updaters Internos

Implementar handlers Application broker-agnostic para modelos internos equivalentes a:

```text
PersonCreated
PersonActivated
PersonDeactivated
AccountCreated
AccountActivated
AccountDeactivated
StudentAcademicEligibilityChanged
```

Monotonía:

```text
incoming <= local
→ stale/duplicate

incoming > local + aplicable
→ aplicar

incoming > local + causalmente no aplicable
→ DEFERRED
```

No inferir gap por salto numérico.

`StudentAcademicEligibilityChanged` se aplica como snapshot autónomo y no queda `DEFERRED` sólo porque falten prerrequisitos de aprovisionamiento.

### 9. Consumer Inbox Interno

Completar comportamiento de:

```text
APPLIED
DEFERRED
```

Aunque no exista broker, PR2 debe probar el procesamiento llamando al dispatcher de mensajes internos con `messageId`.

Pruebas:

- mismo `messageId` no duplica efecto;
- `DEFERRED` conserva payload interno suficiente;
- re-aplicación usa mismo `messageId`;
- stale resuelve `DEFERRED → APPLIED` sin mutación;
- application mutation + Inbox + Outbox son atómicos.

### 10. Idempotencia Funcional

Implementar:

```text
producer + operationType + idempotencyKey
```

Fingerprint canónico.

Casos:

```text
same key + same fingerprint
→ reuse

same key + different fingerprint
→ REJECTED IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST
```

Academic:

```text
importId
planItemId
executionGeneration
pendingReason
```

Una generación anterior no puede reaplicarse después de observar una mayor.

### 11. Outbound Message Intents + Outbox

PR2 debe escribir Outbox dentro de la misma transacción de negocio, pero **sin publisher RabbitMQ**.

Debe existir una representación transport-neutral para:

```text
EVENT
RESULT
QUERY
```

Con metadata necesaria para que PR3 pueda serializar/publicar sin volver a ejecutar reglas.

Regla crítica:

```text
commit de dominio
→ ya contiene obligación durable de publicar
```

PR3 no puede reconstruir Events a partir del estado actual ni volver a ejecutar use cases para publicarlos.

### 12. Auditoría

Completar `authorization_audit_entries` para:

- Command success/rejection/failure persistible;
- actor ACCOUNT/SYSTEM;
- derived provisioning;
- source message;
- correlation/causation;
- reason codes;
- business attribution Academic.

No fabricar Accounts técnicas.

### 13. Aprovisionamiento Convergente De Prácticas

Implementar evaluator local:

```text
eligible=true
+ supported policy
+ Account existe
+ role ALUMNO compatible
+ PRACTICAS_PROFESIONALES ACTIVE
+ SystemAccess inexistente
→ crear ACTIVE
→ SystemAccessGranted intent
```

Triggers internos:

```text
eligibility updater
account updater
role assignment/change
snapshot/recovery aplicado
reconciliation
```

Reglas:

- idempotente;
- race-safe;
- actor `SYSTEM/authorization`;
- acceso existente cualquier estado = no-op;
- false eligibility no muta acceso;
- unknown policy fail-closed;
- no Result de Command para derivación.

### 14. `pendingReason`

Implementar:

```text
EVENT_PENDING
PROJECTION_RECONCILIATION
```

`EVENT_PENDING`:

- espera únicamente cuando existe Event futuro garantizado;
- no dispara Query por timeout arbitrario.

`PROJECTION_RECONCILIATION`:

- crea/reutiliza recovery ENTITY;
- mantiene fail-closed hasta resolución.

### 15. Recovery Job Orchestration

Implementar state machine broker-agnostic:

```text
PENDING
→ construir OutboundQueryIntent + guardar current query metadata
→ WAITING_RESULT

WAITING_RESULT
→ aplicar internal QueryResult
→ PENDING siguiente página / COMPLETED / FAILED
```

Ports:

```text
ProjectionRecoveryCoordinator
ProjectionRecoveryJobRepository
RecoveryQueryOutboxPort
DeferredMessageReplayer
```

No espera síncrona.

### 16. Recovery Result Handler Interno

Implementar modelos internos de Results de snapshots.

Reglas:

```text
causationId != currentQueryMessageId
→ stale Result
→ no mutación

FOUND newer version
→ aplicar

FOUND stale version
→ ignorar snapshot

NOT_FOUND + local ausente
→ confirmar ausencia

NOT_FOUND + local presente
→ marcar discrepancia/fail-closed
→ re-query/FULL
```

Result + Inbox + projection + cursor/job update deben ser atómicos.

### 17. FULL Rebuild

Implementar abstracción para:

```text
REBUILDING
READY
```

Debe soportar destino vacío/nueva generación o clear guarded equivalente.

Pruebas sin broker deben simular:

- snapshot page;
- Event más nuevo antes de page;
- Event más nuevo después de page;
- creación detrás del cursor representada por Event;
- fila fantasma eliminada por rebuild;
- restart continuando desde cursor persistido.

La cola real se conecta en PR3; PR2 prueba la lógica de convergencia.

### 18. Snapshot Queries Que Authorization Expone

Implementar use cases internos:

```text
GetAccountAuthorizationSnapshot
ListAccountAuthorizationSnapshotsPage
```

Sin Rabbit listener todavía.

Probar:

- `FOUND` con role y acceso;
- role conocido + sin acceso → FOUND + empty accesses;
- `NOT_FOUND` sólo cuando no existe estado Authorization dentro del scope;
- `systemCode` filtra accesos;
- requesters limitados no pueden ampliar scope;
- paginación keyset estable;
- `roleVersion` y `accessVersion` independientes.

### 19. Readiness Lógico Sin Broker

Crear componentes de estado interno para que PR3 pueda exponer readiness final:

```text
projection READY/REBUILDING
critical recovery failed
eligibility bootstrap required
```

PR2 no agrega chequeos RabbitMQ.

### 20. Tests PR2

Unit/Application:

- seis Commands;
- autoridad completa;
- Result data;
- Event intents;
- reason codes;
- derived provisioning;
- projections;
- `DEFERRED`;
- idempotencia;
- recovery;
- FULL rebuild;
- NOT_FOUND race-safe.

PostgreSQL integration:

- atomicidad Command + Inbox + idempotency + audit + Outbox;
- ChangeRole + N suspensiones rollback-safe;
- carrera de Grant/provisioning;
- optimistic locking;
- claims de recovery;
- restart/resume de jobs.

## Out Of Scope PR2

- Spring AMQP;
- RabbitMQ;
- listeners;
- publishers;
- publisher confirms reales;
- DLQ real;
- retry broker;
- AsyncAPI;
- JSON Schema;
- DTOs wire;
- routing/bindings físicos;
- Testcontainers RabbitMQ;
- cambios en BFF/Academic/Users/Auth/Lavado.

## Acceptance Criteria PR2

- `./mvnw clean verify` pasa.
- No existe dependencia Spring AMQP.
- Los seis Commands están completos y probados.
- Authority matrix completa está probada.
- Academic ensure/preserve está probado.
- ChangeAccountRole es transaccional con suspensiones derivadas.
- Idempotency fingerprint/replay funciona.
- Inbox APPLIED/DEFERRED funciona.
- Outbox intents quedan confirmados con la mutación.
- Derived Practices provisioning es idempotente/race-safe.
- `eligible=false` no muta `SystemAccess`.
- policy desconocida falla cerrada.
- Recovery ENTITY/FULL es durable y reanudable.
- Result tardío no avanza job equivocado.
- NOT_FOUND contradictorio no borra estado concurrente.
- Snapshot Queries se prueban directamente desde Application.
- Domain/Application siguen libres de AMQP/wire DTOs.
- ArchUnit sigue pasando.

---

# PR3 — Executable Messaging Contracts And External Connections

## Propósito

Conectar el comportamiento terminado en PR2 con RabbitMQ y materializar los contratos wire ejecutables de `authorization-service`.

PR3 **no agrega nuevas reglas de negocio**.

Si PR3 necesita cambiar la semántica de un Command, authority rule, eligibility rule, transaction o recovery invariant, significa que PR1/PR2 o la documentación canónica quedaron incompletos y debe corregirse primero ese problema.

## Scope

### 1. Dependencias De Messaging

Agregar:

```text
Spring AMQP / spring-rabbit
RabbitMQ Testcontainers
```

Agregar tooling de validación de JSON Schema/AsyncAPI sólo cuando sea necesario para contract tests; no introducir frameworks de contrato que obliguen Domain/Application a depender de ellos.

### 2. Estructura Wire

Materializar:

```text
authorization-service/contracts/
├── asyncapi.yaml
└── schemas/
    ├── common/
    ├── commands/
    ├── results/
    ├── events/
    └── queries/
```

Java:

```text
com.estoma.authorization.contracts
com.estoma.authorization.infrastructure.messaging
```

`contracts` contiene únicamente DTOs/shapes wire.

### 3. Envelope V1

Implementar envelope común compatible con plataforma:

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
actor
aggregateId?
aggregateVersion?
payload
```

No agregar `replyTo` durable.

No duplicar `operationId`/`idempotencyKey` dentro de payload si ya viven en envelope.

### 4. Command Contracts Inbound

Materializar schemas/DTOs V1 para:

```text
AssignAccountRole
ChangeAccountRole
GrantSystemAccess
SuspendSystemAccess
RevokeSystemAccess
RestoreSystemAccess
```

Listener:

```text
commands.authorization
→ parse/validate wire
→ map contracts -> application internal model
→ invoke dispatcher PR2
→ ACK después de commit
```

Listener no contiene authority ni reglas de dominio.

### 5. Command Results Outbound

Materializar:

```text
AssignAccountRoleResult
ChangeAccountRoleResult
GrantSystemAccessResult
SuspendSystemAccessResult
RevokeSystemAccessResult
RestoreSystemAccessResult
```

Routing:

```text
Command producer = bff
→ platform.results / routing bff

Command producer = academic
→ platform.results / routing academic
```

Result `producer = authorization`.

### 6. Events Outbound

Schemas/DTOs/routing:

```text
AccountRoleAssigned
→ authorization.account-role.assigned

AccountRoleChanged
→ authorization.account-role.changed

SystemAccessGranted
→ authorization.system-access.granted

SystemAccessSuspended
→ authorization.system-access.suspended

SystemAccessRevoked
→ authorization.system-access.revoked

SystemAccessRestored
→ authorization.system-access.restored
```

El Relay publica exactamente la instancia persistida en Outbox; no reconstruye el Event desde estado actual.

### 7. External Events Inbound

Consumir desde `events.authorization`:

```text
users.person.created
users.person.activated
users.person.deactivated

authentication.account.created
authentication.account.activated
authentication.account.deactivated

academic.student-eligibility.changed
```

Cada listener:

```text
wire validation
→ mapping
→ application projection updater
→ commit
→ ACK
```

### 8. Queries Expuestas

Queue:

```text
queries.authorization
```

Materializar:

```text
GetAccountAuthorizationSnapshot
ListAccountAuthorizationSnapshotsPage
```

Requesters V1:

```text
bff
appointments
wash-administration
wash-execution
academic
```

Aplicar ACL por identidad técnica + requester lógico.

Query side-effect-free:

- no requiere idempotencia funcional;
- redelivery puede reevaluarse;
- ACK sólo después de publisher confirm del Result o registro durable equivalente.

### 9. Outbound Recovery Queries

Authorization requester:

```text
producer = authorization
```

Publicar vía Outbox:

```text
queries.users
queries.authentication
queries.academic
```

Contracts:

```text
GetPersonStatusSnapshot
ListPersonStatusSnapshotsPage
GetAccountStatusSnapshot
ListAccountStatusSnapshotsPage
GetStudentAcademicEligibilitySnapshot
ListStudentAcademicEligibilitySnapshotsPage
```

No inventar campos de los owners; usar contratos canónicos.

### 10. Recovery Results Inbound

Queue:

```text
results.authorization
```

Result listener:

```text
validate wire
→ map
→ Consumer Inbox
→ ProjectionRecovery Result handler PR2
→ projection/cursor/job update
→ commit
→ ACK
```

No mezclar estos Results con Command Results de BFF/Academic.

### 11. Transactional Outbox Relay

Implementar Relay multi-réplica:

```text
PENDING
→ claim
→ PROCESSING(claimToken, leaseUntil)
→ publish
→ publisher confirm
→ PUBLISHED
```

Toda transición desde `PROCESSING` condicionada al claim vigente.

Sweeper:

- recupera lease expirado con fencing;
- no permite worker viejo sobreescribir nuevo claim.

Fallo transitorio:

```text
PROCESSING
→ PENDING + availableAt/backoff
```

No límite automático de 3 intentos.

### 12. Retry/DLQ De Consumers

Consumer failure transitorio:

```text
retry/backoff
máximo default 3
```

Rechazo funcional Command:

```text
Result REJECTED
ACK
sin DLQ
```

Mensaje inválido/fallo técnico no persistible agotado:

```text
DLQ
```

Materializar DLQ para las colas durables inbound conforme a la convención transversal, incluyendo Commands/Events/Queries/Results donde corresponda.

Replay manual, controlado y auditable.

### 13. Contract Artifacts

`asyncapi.yaml` debe documentar únicamente la parte implementada por este servicio:

- exchanges usados;
- queues que Authorization posee/consume;
- routing keys producidas/consumidas;
- message types/version;
- schemas locales.

No documentar como propios exchanges/queues de otros servicios que Authorization no administra.

JSON Schemas deben cubrir:

- envelope;
- actor;
- seis Commands;
- seis Results;
- seis Events producidos;
- Events externos consumidos necesarios;
- Queries expuestas;
- Queries de recovery emitidas y sus Results consumidos.

Las copias inbound son contratos de compatibilidad local; source of truth funcional sigue en `estomatologia-platform-docs`/owner.

### 14. Contract Mapping Tests

Probar:

```text
wire DTO -> application model
application Result/Event -> wire DTO
serialized message -> JSON Schema válido
messageVersion no soportada -> rechazo técnico controlado
campo desconocido/crítico según política -> tratamiento contractual
```

Domain no participa en serialization tests.

### 15. RabbitMQ Topology

Usar logicalComponentId canónico:

```text
authorization
```

Exchanges:

```text
platform.commands
platform.results
platform.events
platform.queries
platform.dlx
```

Queues propias/requeridas:

```text
commands.authorization
events.authorization
queries.authorization
results.authorization
```

No crear una cola de Result por request.

No renombrar a `authorization-service` en routing si la arquitectura usa `authorization`.

### 16. Rabbit Security

Credencial técnica propia y mínimo privilegio.

Authorization necesita:

- consumir sus queues;
- publicar Commands/Queries/Results/Events sólo en exchanges/routing permitidos;
- no administrar topología runtime desde la app salvo patrón de infraestructura aprobado;
- no consumir colas de otros requesters.

TLS según infraestructura.

### 17. Readiness Final

Readiness incluye:

```text
PostgreSQL
migrations/schema
RabbitMQ connectivity
commands consumer
external events consumer
queries consumer
results.authorization consumer
Outbox Relay progresando
critical FULL recovery state
```

Liveness no debe caer por una dependencia externa temporal si el proceso está vivo.

Un Entity recovery aislado no vuelve not-ready globalmente.

### 18. Observabilidad Final

Completar métricas de `system_contract.md`.

Logs de mensajería con:

```text
messageId
messageType
messageVersion
producer
consumer
correlationId
causationId
operationId
aggregateId
actorType
result/errorCode
attempt
recoveryJobId cuando aplique
```

No payloads personales completos, tokens o secretos.

### 19. RabbitMQ Integration Tests

Testcontainers RabbitMQ obligatorio.

Casos mínimos:

```text
Command válido -> Result + Event
Command funcionalmente rechazado -> Result REJECTED sin retry
redelivery Command -> no duplicate mutation/Event
event duplicate -> Inbox absorbs
stale Event -> no rollback projection
causal Event -> DEFERRED -> later APPLIED
Outbox publish + publisher confirm
outbox duplicate delivery -> consumer safe
outbox broker outage -> remains retryable
poison message -> DLQ
Query snapshot -> correct requester Result
outbound recovery Query -> Result -> job progresses
late recovery Result -> no progression
FULL rebuild with concurrent Event -> latest state preserved
Practices eligibility -> derived SystemAccessGranted once
```

### 20. No Cambios De Negocio En PR3

PR3 no puede:

- agregar Commands;
- cambiar authority matrix;
- cambiar catálogo de roles/permisos;
- reinterpretar elegibilidad;
- cambiar SystemAccess lifecycle;
- agregar REST de negocio;
- cambiar el modelo de DB salvo migración mínima estrictamente necesaria para wiring ya aprobado;
- refactorizar BFF/Academic/Users/Auth/Lavado.

Si aparece necesidad, detener el wiring y corregir primero el contrato/foundation.

## Acceptance Criteria PR3

- `./mvnw clean verify` pasa.
- Spring AMQP sólo existe en infrastructure.
- Domain/Application no dependen de AMQP/wire DTOs.
- AsyncAPI y JSON Schemas existen y validan la parte implementada.
- Commands/Results/Events/Queries tienen `messageVersion` V1 probado.
- `commands.authorization`, `events.authorization`, `queries.authorization`, `results.authorization` funcionan en Testcontainers.
- publisher confirms probados.
- Outbox Relay multi-réplica/lease/fencing probado.
- retry/DLQ probado.
- ACK sólo después de commit/result reliable publish según tipo de mensaje.
- Results de recovery no se confunden con Results funcionales.
- no `ValidateAccess`.
- no HTTP/gRPC de negocio.
- no secretos en infraestructura durable.
- readiness/liveness/Prometheus configurados.
- ArchUnit sigue pasando.
- E2E crítico de Prácticas funciona exactamente una vez funcionalmente bajo redelivery.

---

# Gates Entre PRs

## Gate PR1 → PR2

Antes de PR2:

- PR1 mergeado en `develop`;
- schema final de Authorization disponible;
- repositories/transaction runner estables;
- ArchUnit activo;
- no AMQP.

## Gate PR2 → PR3

Antes de PR3:

- seis Commands completos sin broker;
- authority completa;
- Inbox/idempotency/Outbox/audit completos;
- Practices provisioning completo;
- recovery completo mediante ports;
- snapshot Queries completos;
- `./mvnw clean verify` verde;
- volver a revisar `estomatologia-platform-docs/main` para drift.

PR3 no empieza con comportamiento pendiente.

---

# Definition Of Done De `authorization-service`

Después de los tres PRs, el servicio debe cumplir:

```text
Hexagonal architecture enforced
PostgreSQL + Flyway
JdbcClient, no JPA
static V1 catalogs
one role per Account
one SystemAccess per Account/System
six Commands V1
BFF/Academic authority matrix
Person/Account/Eligibility projections
Practices convergent provisioning
Consumer Inbox APPLIED/DEFERRED
functional idempotency
Transactional Outbox EVENT/RESULT/QUERY
append-only audit
projection recovery ENTITY/FULL
race-safe NOT_FOUND
snapshot Queries
RabbitMQ contracts/adapters
publisher confirms
lease/fencing
retry/DLQ
Actuator + Prometheus
no business HTTP/gRPC
no ValidateAccess
contract + integration + concurrency tests
```

La implementación se considera incompleta si funciona en happy path pero omite recovery, idempotencia, concurrencia, fail-closed o comunicación durable.