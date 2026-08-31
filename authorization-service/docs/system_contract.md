# authorization-service System Contract

## Estado

Contrato de implementación para `authorization-service`.

PR1 materializa la foundation de dominio, persistencia y operación descrita en
este documento. El comportamiento completo de Application se implementará en
PR2 y los adapters/contratos wire en PR3, siguiendo `execution_plan.md`.

Si este archivo entra en conflicto con la documentación canónica de plataforma, la documentación de plataforma prevalece y el conflicto debe resolverse antes de implementar el comportamiento afectado.

## Fuente De Verdad

Fuente canónica: `AnthonyNav/estomatologia-platform-docs`, `main` observado en `ba9c03e2d7bccfc48211bdc4279f3c23bd03f6db`.

Documentos principales:

- `03-services/shared/authorization-service/README.md`
- `03-services/shared/authorization-service/data-model.md`
- `03-services/shared/authorization-service/commands.md`
- `03-services/shared/authorization-service/events.md`
- `03-services/shared/authorization-service/interfaces.md`
- `03-services/shared/authorization-service/queries.md`
- `03-services/shared/authorization-service/projection-recovery.md`
- `02-architecture/messaging-architecture.md`
- `02-architecture/data-consistency-architecture.md`
- `02-architecture/platform-architecture.md`
- `01-domain/shared-platform-business.md`

Para Events/Queries consumidos y coordinación académica también deben respetarse:

- `03-services/shared/users-service/events.md`
- `03-services/shared/users-service/queries.md`
- `03-services/shared/authentication-service/events.md`
- `03-services/shared/authentication-service/queries.md`
- `03-services/shared/academic-service/events.md`
- `03-services/shared/academic-service/queries.md`
- `03-services/shared/academic-service/student-import.md`

Antes de iniciar cada PR de implementación, el agente debe comprobar si estos contratos cambiaron. No debe implementar reglas basándose únicamente en este archivo si la fuente canónica ya evolucionó.

## Estado De La Implementación PR1

Estado observado en `estoma-services` en la rama PR1:

- Spring Boot `4.1.0` y Java `25`;
- Actuator, WebMVC, Prometheus, Spring JDBC, Flyway, PostgreSQL y Security;
- `SystemController` temporal eliminado;
- `/actuator/health`, liveness, readiness y Prometheus como única superficie HTTP operativa;
- dominio puro con modelos de rol y `SystemAccess`;
- PostgreSQL V1 con catálogos, seed determinista, asignaciones, proyecciones y tablas técnicas;
- adapters `JdbcClient`, locking optimista y `TransactionRunner` broker-agnostic;
- ArchUnit y Testcontainers PostgreSQL como gates;
- sin RabbitMQ, handlers completos, recovery orchestration ni contratos wire ejecutables: quedan para PR2/PR3.

No debe conservarse un controller HTTP de negocio ni el `SystemController` temporal.

## Baseline Tecnológica

Mantener, salvo decisión transversal posterior:

```text
Java 25
Spring Boot 4.1.x
Maven
PostgreSQL
Spring JDBC / JdbcClient
Flyway
RabbitMQ mediante Spring AMQP únicamente en PR3
```

Decisión de implementación:

```text
NO JPA / Hibernate
```

Razón: el servicio necesita control explícito sobre optimistic locking, constraints, JSONB técnico, Inbox/Outbox, claims, leases, fencing y queries de recovery. Spring JDBC/JdbcClient mantiene el adapter de persistencia pequeño y visible sin contaminar Domain/Application.

Un cambio de Java/Spring Boot debe tratarse como normalización transversal del repositorio, no como efecto lateral de implementar Authorization.

## Logical Component ID

Identidad lógica canónica:

```text
authorization
```

Se usa en el namespace contractual de mensajería para `producer`, routing y `ActorContext.type = SYSTEM.service` según `messaging-architecture.md`.

## Responsabilidad

`authorization-service` responde:

> ¿Qué rol tiene una Cuenta, a qué sistemas tiene acceso administrativo y qué permisos efectivos puede ejercer bajo el estado proyectado necesario?

Es fuente oficial de:

- sistemas soportados;
- roles;
- permisos;
- compatibilidad Role → System;
- Role → Permission V1;
- rol vigente por Cuenta;
- `SystemAccess` por Cuenta/sistema;
- suspensión, revocación y restauración de `SystemAccess`;
- aprovisionamiento derivado de Prácticas cuando la elegibilidad académica converge.

También mantiene persistencia técnica propia para:

- proyección mínima de Persona;
- proyección mínima de Cuenta;
- proyección del resultado de elegibilidad académica;
- Consumer Inbox;
- idempotencia funcional de Commands;
- Transactional Outbox;
- auditoría append-only;
- bootstrap/reconciliación durable de proyecciones.

## Lo Que No Posee

El servicio no es propietario de:

- Persona, nombre, correo o teléfono;
- credenciales, contraseña o recovery;
- sesiones;
- matrícula, semestre, NRC o estado académico bruto;
- `AcademicEligibilityPolicy` ni su fórmula;
- citas;
- ejecución de Lavado;
- configuración de cabinas/tinas/horarios;
- buzón o correo;
- reglas internas de los dominios consumidores.

No puede:

- leer bases ajenas;
- escribir bases ajenas;
- validar contraseñas;
- crear/revocar sesiones por efecto de un `SystemAccess`;
- llamar HTTP/gRPC a otros microservicios para autorización de cada request;
- exponer `ValidateAccess`.

---

# Arquitectura Hexagonal Obligatoria

## Objetivo

Domain y Application deben poder probarse sin Spring Boot, PostgreSQL ni RabbitMQ.

La estructura objetivo es:

```text
com.estoma.authorization
├── domain
│   ├── model
│   ├── policy
│   └── exception
├── application
│   ├── port
│   │   ├── in
│   │   └── out
│   ├── command
│   ├── query
│   ├── projection
│   ├── recovery
│   └── service
├── infrastructure
│   ├── persistence
│   │   └── jdbc
│   ├── transaction
│   ├── configuration
│   ├── security
│   ├── observability
│   └── messaging
└── contracts
    ├── common
    ├── command
    ├── result
    ├── event
    └── query
```

`infrastructure.messaging` y `contracts` se materializan en PR3. PR1 y PR2 deben permanecer broker-agnostic.

## Reglas De Dependencia

```text
domain
→ no depende de application
→ no depende de infrastructure
→ no depende de contracts
→ no depende de Spring/JDBC/AMQP/Jackson

application
→ puede depender de domain
→ define ports
→ no depende de infrastructure
→ no depende de Spring AMQP
→ no recibe DTOs wire como modelo de negocio

infrastructure
→ implementa ports de application
→ puede depender de application/domain/contracts
→ contiene Spring, JDBC, AMQP y configuración

contracts
→ describe shape wire V1
→ no contiene reglas de negocio
→ no accede a repositories
→ no reemplaza modelos de application/domain
```

Agregar ArchUnit para proteger estas fronteras.

## Domain

Debe contener únicamente reglas que pueden evaluarse sin infraestructura.

Modelos principales:

```text
AccountRoleAssignment
SystemAccess
RoleCode
SystemCode
PermissionCode
SystemAccessStatus
SystemAccessReasonCode
```

Los catálogos estáticos pueden representarse mediante modelos inmutables o value objects, pero Domain no debe saber cómo se cargan desde Flyway/JDBC.

## Application

Application orquesta:

- Commands;
- autoridad;
- validación de proyecciones;
- persistencia mediante ports;
- idempotencia;
- Inbox/Outbox;
- auditoría;
- aprovisionamiento derivado de Prácticas;
- proyección de Events externos;
- Queries de snapshot;
- recovery y reaplicación de `DEFERRED`.

Application opera con modelos internos. Un listener RabbitMQ no puede ser el use case.

## Infrastructure

Infrastructure contiene adapters para:

- PostgreSQL/JdbcClient;
- transacciones;
- Flyway;
- Spring Security de endpoints operativos;
- Actuator;
- métricas/logs;
- RabbitMQ en PR3;
- serialización wire en PR3.

## Contracts

Se introduce en PR3 para DTOs/esquemas wire.

Reglas:

- no reutilizar entidades de Domain como payload AMQP;
- no anotar Domain con `@JsonProperty`;
- no permitir que cambios wire modifiquen reglas de dominio;
- mapping explícito `contracts ↔ application` en adapters;
- `messageVersion` forma parte del contrato;
- no inventar `replyTo` durable ni campos top-level fuera del envelope canónico.

## Límite Transaccional

Application no debe depender de `@Transactional` para expresar reglas.

Preferencia:

```text
application.port.out.TransactionRunner
→ infrastructure.transaction.SpringTransactionRunner
→ TransactionTemplate
```

Así el caso de uso expresa qué debe ocurrir atómicamente sin importar Spring.

Ninguna transacción local permanece abierta esperando RabbitMQ o una Query remota.

---

# Modelo Funcional V1

## `AccountRoleAssignment`

Identidad funcional:

```text
accountId
```

Campos conceptuales:

```text
accountId
roleId
roleCode
version
assignedAt
updatedAt
actor metadata derivada
```

Invariantes:

- una Cuenta tiene un único rol vigente;
- `version > 0`;
- `MATRICULA → ALUMNO` únicamente;
- `USERNAME → SUPERVISOR_LAVADO | ADMINISTRADOR_LAVADO | ADMINISTRADOR_PRACTICAS | SUPERADMIN`;
- cambiar rol requiere `expectedVersion`;
- cambiar rol suspende accesos `ACTIVE` incompatibles en la misma transacción;
- cambiar rol no concede ni restaura accesos.

## `SystemAccess`

Identidad:

```text
systemAccessId
```

Unicidad funcional:

```text
accountId + systemId
```

Estados:

```text
ACTIVE
SUSPENDED
REVOKED
```

Motivos V1:

```text
ADMINISTRATIVE
ROLE_CHANGED
SECURITY
OTHER
```

Reglas:

- `SUSPENDED` y `REVOKED` requieren `reasonCode`;
- `OTHER` requiere `reasonDetail`;
- `ROLE_CHANGED` sólo se usa para suspensión derivada de `ChangeAccountRole`;
- `SUSPENDED → ACTIVE` requiere autoridad dentro de scope;
- `REVOKED → ACTIVE` sólo `SUPERADMIN`;
- restaurar requiere rol/sistema compatibles y estado Persona/Cuenta confiable cuando el actor sea humano;
- `SystemAccess` y elegibilidad académica son estados distintos.

## Catálogos V1

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

Compatibilidad Role → System:

```text
ALUMNO                  → LAVADO_ULTRASONICO
ALUMNO                  → PRACTICAS_PROFESIONALES
SUPERVISOR_LAVADO       → LAVADO_ULTRASONICO
ADMINISTRADOR_LAVADO    → LAVADO_ULTRASONICO
ADMINISTRADOR_PRACTICAS → PRACTICAS_PROFESIONALES
SUPERADMIN              → LAVADO_ULTRASONICO
SUPERADMIN              → PRACTICAS_PROFESIONALES
```

Esto expresa compatibilidad estructural; no concede `SystemAccess`.

## Role → Permission V1

`ALUMNO` en Lavado:

```text
wash.appointment.create
wash.appointment.cancel
wash.execution.exit.submit
```

`SUPERVISOR_LAVADO`:

```text
wash.execution.arrival.register
wash.entry.decide
wash.appointment.exceptional-authorization.grant
wash.appointment.exceptional-authorization.cancel
wash.resource.disable
wash.resource.restore
wash.execution.reassignment.resolve
wash.execution.complete
wash.execution.cancel-by-clinic
```

`ADMINISTRADOR_LAVADO`:

```text
wash.admin.calendar.create
wash.admin.calendar.update
wash.admin.calendar.activate
wash.admin.calendar.deactivate
wash.admin.schedule.configure
wash.admin.schedule.update
wash.admin.schedule.deactivate
wash.admin.closure.schedule
wash.admin.closure.cancel
wash.admin.cabin.register
wash.admin.cabin.update
wash.admin.cabin.activate
wash.admin.cabin.deactivate
wash.admin.cabin.retire
wash.admin.tank.register
wash.admin.tank.update
wash.admin.tank.activate
wash.admin.tank.deactivate
wash.admin.tank.retire
wash.appointment-slot.create
wash.appointment-slot.cancel
```

`ADMINISTRADOR_PRACTICAS` no tiene permisos operativos de dominio V1. `SUPERADMIN` no obtiene automáticamente permisos operativos de Lavado/Prácticas por ser Superadmin.

Los catálogos son estáticos en V1 y se cargan por migración/configuración controlada. No existen Commands V1 de administración dinámica de permisos.

---

# Proyecciones Externas

Authorization mantiene:

```text
person_status_projection
account_status_projection
academic_eligibility_projection
```

No son fuentes oficiales.

## Persona

Campos mínimos:

```text
personId
status = ACTIVE | INACTIVE
sourceVersion
updatedAt
```

## Cuenta

Campos mínimos:

```text
accountId
personId
loginType = MATRICULA | USERNAME
status = ACTIVE | INACTIVE
sourceVersion
updatedAt
```

## Elegibilidad Académica

Clave:

```text
studentAcademicProfileId + systemCode
```

Campos mínimos:

```text
eligibilityId
studentAcademicProfileId
accountId
systemCode
eligible
policyVersion
eligibilityVersion
academicProfileVersion
pendingReason?
evaluatedAt
updatedAt
```

Authorization no recalcula semestre ni `academicStatus`.

---

# Acceso Efectivo

Conceptualmente:

```text
Persona projection confiable + ACTIVE
+
Cuenta projection confiable + ACTIVE
+
Role ACTIVE
+
System ACTIVE
+
Role compatible con System
+
SystemAccess ACTIVE
+
Permission asignado al Role
+
reglas del dominio consumidor
```

Para `PRACTICAS_PROFESIONALES` además:

```text
academic_eligibility_projection presente
+ eligible = true
+ policyVersion soportada
```

Ausencia, inconsistencia o recovery crítico no resuelto es fail-closed.

Authorization no se consulta síncronamente en cada request. BFF y servicios propietarios mantienen sus propias proyecciones para decidir localmente.

---

# Commands V1

Commands durables:

```text
AssignAccountRole
ChangeAccountRole
GrantSystemAccess
SuspendSystemAccess
RevokeSystemAccess
RestoreSystemAccess
```

## `AssignAccountRole`

Input funcional:

```text
accountId
roleCode
importExecution?  // sólo Academic
```

Reglas principales:

- Cuenta proyectada existente/confiable cuando sea necesaria;
- Role existe y está `ACTIVE`;
- Role compatible con `loginType`;
- una Cuenta no puede recibir una segunda asignación normal;
- BFF y Academic tienen semánticas distintas ante estado ya satisfecho.

Academic:

```text
producer = academic
actor = SYSTEM { service: academic }
scope = ASSIGN_STUDENT_ROLE
roleCode = ALUMNO
```

Si `ALUMNO` ya está asignado y coincide con el plan:

```text
SUCCEEDED
changed = false
sin Event funcional nuevo
```

## `ChangeAccountRole`

Input:

```text
accountId
newRoleCode
expectedVersion
```

Sólo `SUPERADMIN` mediante BFF en V1.

Transacción:

```text
cambiar account_roles
+ suspender N SystemAccess ACTIVE incompatibles con ROLE_CHANGED
+ AccountRoleChanged
+ N SystemAccessSuspended
+ Result
```

Cada acceso suspendido incrementa su propia versión.

## `GrantSystemAccess`

Input:

```text
systemAccessId
accountId
systemCode
importExecution?  // sólo Academic
```

BFF normal:

- acceso preexistente → `SYSTEM_ACCESS_ALREADY_EXISTS`.

Academic:

```text
scope = GRANT_STUDENT_SYSTEM_ACCESS
role = ALUMNO
systemCode = LAVADO_ULTRASONICO en V1
```

Academic **nunca** publica el Grant de `PRACTICAS_PROFESIONALES`.

Ensure/preserve Academic:

```text
relación ya existe ACTIVE/SUSPENDED/REVOKED
→ SUCCEEDED
→ changed = false
→ devolver estado/version canónicos
→ no Event nuevo
→ no restaurar restricción
```

## `SuspendSystemAccess`

Input:

```text
systemAccessId
expectedVersion
reasonCode
reasonDetail?
```

Sólo desde `ACTIVE`.

## `RevokeSystemAccess`

Input equivalente con `expectedVersion` y motivo válido. Puede revocar desde estado permitido por el contrato; el resultado final es `REVOKED`.

## `RestoreSystemAccess`

Input:

```text
systemAccessId
expectedVersion
```

Reglas:

- `SUSPENDED → ACTIVE` dentro del scope autorizado;
- `REVOKED → ACTIVE` sólo `SUPERADMIN`;
- no restaurar si rol/sistema dejaron de ser compatibles;
- no inventar estado Persona/Cuenta faltante.

---

# Matriz De Autoridad V1

Antes de cualquier autoridad humana:

```text
Account projection ACTIVE + confiable
+ Person projection ACTIVE + confiable
+ actor role ACTIVE
```

Matriz:

| Producer | Actor | Actor role | Target | System | Operaciones |
| --- | --- | --- | --- | --- | --- |
| `bff` | `ACCOUNT` | `SUPERADMIN` | `ALUMNO`, `SUPERVISOR_LAVADO`, `ADMINISTRADOR_LAVADO`, `ADMINISTRADOR_PRACTICAS` | compatible/elegible | seis Commands; sin target SUPERADMIN ni self-admin |
| `bff` | `ACCOUNT` | `ADMINISTRADOR_LAVADO` | `SUPERVISOR_LAVADO` | `LAVADO_ULTRASONICO` | Assign, Grant, Suspend, Revoke; Restore sólo desde SUSPENDED |
| `bff` | `ACCOUNT` | `ADMINISTRADOR_PRACTICAS` | ninguno V1 | `PRACTICAS_PROFESIONALES` | ninguna |
| `academic` | `SYSTEM {service: academic}` | N/A | `ALUMNO` | `LAVADO_ULTRASONICO` | Assign/Grant con `importExecution` y scope exacto |

Academic nunca:

- cambia rol;
- suspende;
- revoca;
- restaura;
- concede Prácticas mediante Command.

`authorizedByAccountId` de `importExecution` es atribución de negocio; no reemplaza al actor técnico `SYSTEM/academic`.

---

# Aprovisionamiento Derivado De Prácticas

No es un Command público ni un Process Manager nuevo.

Disparadores:

```text
StudentAcademicEligibilityChanged
AccountCreated / projection disponible
AccountRoleAssigned
AccountRoleChanged
snapshot/recovery aplicado
reconciliación operativa
```

Condición:

```text
eligible = true
+ policyVersion soportada
+ Cuenta existente
+ role = ALUMNO compatible
+ PRACTICAS_PROFESIONALES ACTIVE
+ SystemAccess inexistente
→ crear SystemAccess ACTIVE exactamente una vez
→ SystemAccessGranted
```

Reglas:

- Cuenta `ACTIVE` no es necesaria para crear la asignación administrativa; sí para acceso efectivo;
- acceso existente `ACTIVE/SUSPENDED/REVOKED` → no-op;
- `eligible=false` no suspende ni revoca;
- policy desconocida → fail-closed;
- recovery no restaura `SUSPENDED/REVOKED`;
- actor del Grant derivado = `SYSTEM {service: authorization}`;
- auditoría conserva la evidencia/causalidad que motivó la decisión.

`pendingReason`:

```text
EVENT_PENDING
→ existe Event futuro garantizado
→ no disparar Query por impaciencia

PROJECTION_RECONCILIATION
→ existe evidencia autoritativa, pero falta/no es confiable la projection
→ crear/reutilizar recovery ENTITY
```

---

# Results V1

Cada Command publica:

```text
<Command>Result
```

Payload común:

```text
status = SUCCEEDED | REJECTED | FAILED | EXPIRED
data?
error?
```

Authorization V1 no produce `EXPIRED` para los seis Commands actuales.

`REJECTED` es rechazo funcional y no provoca retry técnico.

`FAILED` es fallo técnico terminal persistible.

Un Command `DEFERRED` no tiene Result terminal hasta resolverse.

Success data mínimo:

```text
AssignAccountRole
→ accountId, roleCode, changed, newVersion

ChangeAccountRole
→ accountId, previousRole, newRole, changed=true, newVersion,
  suspendedSystemAccessIds, suspendedSystemAccessCount

GrantSystemAccess
→ systemAccessId, accountId, systemCode, status, changed, newVersion

SuspendSystemAccess
→ systemAccessId, accountId, status=SUSPENDED, changed=true, newVersion

RevokeSystemAccess
→ systemAccessId, accountId, status=REVOKED, changed=true, newVersion

RestoreSystemAccess
→ systemAccessId, accountId, status=ACTIVE, changed=true, newVersion
```

Results para Academic incluyen `importId`, `planItemId` y `executionGeneration`.

---

# Events

## Producidos

```text
AccountRoleAssigned
AccountRoleChanged
SystemAccessGranted
SystemAccessSuspended
SystemAccessRevoked
SystemAccessRestored
```

Routing canónico:

```text
authorization.account-role.assigned
authorization.account-role.changed
authorization.system-access.granted
authorization.system-access.suspended
authorization.system-access.revoked
authorization.system-access.restored
```

Cada Event usa `aggregateId` y `aggregateVersion` del agregado que realmente cambió.

`SystemAccessGranted` puede originarse por:

```text
GrantSystemAccess Command
OR
aprovisionamiento derivado de Prácticas
```

En el segundo caso:

```text
actor = SYSTEM { service: authorization }
causationId = messageId del trigger directo
sin Command Result
operationId/idempotencyKey sólo si el trigger los tenía
```

## Consumidos

Users:

```text
PersonCreated
PersonActivated
PersonDeactivated
```

Authentication:

```text
AccountCreated
AccountActivated
AccountDeactivated
```

Academic:

```text
StudentAcademicEligibilityChanged
```

Routing V1:

```text
users.person.created
users.person.activated
users.person.deactivated
authentication.account.created
authentication.account.activated
authentication.account.deactivated
academic.student-eligibility.changed
```

`aggregateVersion` protege monotonía, no contigüidad.

```text
incoming <= local
→ stale/duplicate

incoming > local + transición aplicable
→ aplicar

incoming > local + transición causalmente no aplicable
→ Inbox DEFERRED
→ ACK
→ fail-closed para esa entidad
```

La falta de prerrequisitos de aprovisionamiento no convierte `StudentAcademicEligibilityChanged` en Inbox `DEFERRED`; el snapshot de elegibilidad se aplica y la espera vive en `pendingReason`.

---

# Queries Y Recovery

## Queries Que Authorization Expone

```text
GetAccountAuthorizationSnapshot
ListAccountAuthorizationSnapshotsPage
GetSystemCatalogSnapshot
ListSystemCatalogSnapshotsPage
```

Requesters for account authorization snapshots:

```text
bff
appointments
wash-administration
wash-execution
academic
```

Requesters limitados a Lavado usan `systemCode = LAVADO_ULTRASONICO` y no reciben accesos de Prácticas.

Snapshots contienen únicamente rol y `SystemAccess` propiedad de Authorization; no incluyen Persona, credenciales, sesiones ni elegibilidad.

Los snapshots del catálogo de Systems están limitados a `bff`. Contienen
`systemId`, `systemCode`, `status` de catálogo y `sourceVersion` positivo. El
catálogo es configuración controlada y no un agregado event-sourced: una
migración que cambia `systems.status` incrementa `systems.version` de forma
atómica. Así, BFF puede reconstruir con la Query paginada y aplicar después un
snapshot puntual sólo si su source version es más nueva, sin un read síncrono a
Authorization.

Un cambio controlado de `systems.status` tiene un gate de despliegue explícito:
la migración/operación identifica sus `systemCode` afectados y BFF emite
`GetSystemCatalogSnapshot` para cada uno. BFF sólo vuelve a considerar ese
System efectivo después de persistir un Result con `sourceVersion` más nueva;
si el recovery no puede terminar, permanece fail-closed. El procedimiento
completo está en [`system_catalog_change_recovery.md`](system_catalog_change_recovery.md).

## Queries Que Authorization Emite Para Recovery

Users:

```text
GetPersonStatusSnapshot
ListPersonStatusSnapshotsPage
```

Authentication:

```text
GetAccountStatusSnapshot
ListAccountStatusSnapshotsPage
```

Academic:

```text
GetStudentAcademicEligibilitySnapshot
ListStudentAcademicEligibilitySnapshotsPage
```

Uso permitido:

```text
Events
→ sincronización normal

Queries
→ reparación puntual o FULL rebuild
```

Prohibido:

```text
Command funcional
→ Query snapshot
→ autorizar inmediatamente el mismo request
```

Eso sería `ValidateAccess` encubierto.

## `projection_recovery_jobs`

Scopes:

```text
FULL
ENTITY
```

Estados durables:

```text
PENDING
WAITING_RESULT
COMPLETED
FAILED
```

Campos conceptuales:

```text
recoveryJobId
projectionType
scope
entityKey?
cursor?
queryAttempt
currentQueryType?
currentQueryMessageId?
currentOperationId?
currentCorrelationId?
attempts
lastErrorCode?
claimToken?
leaseUntil?
createdAt
updatedAt
completedAt?
```

No mantener claim ni transacción abiertos durante `WAITING_RESULT`.

Result tardío:

```text
Result.causationId != currentQueryMessageId
→ stale
→ ACK
→ sin mutación
```

## FULL Rebuild

Orden:

```text
bind Events
→ marcar projection REBUILDING/fail-closed
→ preparar destino vacío/nueva generación
→ consumir Events contra el destino que será activado
→ snapshot paginado
→ converger por sourceVersion
→ revisar DEFERRED
→ activar READY
```

Invariantes:

- ninguna fila fantasma sobrevive;
- ningún Event confirmado durante la ventana se pierde;
- snapshot atrasado no sobreescribe Event más reciente.

## `NOT_FOUND` Puntual

`NOT_FOUND` no lleva una versión de inexistencia comparable con un Event concurrente.

Regla:

```text
NOT_FOUND + projection sigue ausente
→ confirmar ausencia/fail-closed

NOT_FOUND + projection ya existe
→ NO borrar a ciegas
→ marcar discrepancia/no confiable
→ re-query controlada o FULL rebuild
```

---

# Persistencia V1

Tablas funcionales/configuración:

```text
systems
roles
permissions
role_systems
role_permissions
account_roles
system_accesses
```

Proyecciones:

```text
person_status_projection
account_status_projection
academic_eligibility_projection
```

Persistencia técnica:

```text
processed_messages
command_idempotency_records
outbox_messages
authorization_audit_entries
projection_recovery_jobs
```

No existen FKs hacia bases de otros microservicios.

FKs internas entre catálogos de Authorization sí son válidas.

## Constraints Mínimos

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
version > 0 donde aplique
```

Actor XOR:

```text
ACCOUNT → account_id != NULL AND actor_service = NULL
SYSTEM  → account_id = NULL AND actor_service != NULL
```

`SystemAccess`:

```text
SUSPENDED/REVOKED → reason_code NOT NULL
OTHER → reason_detail NOT NULL
```

Recovery:

- máximo un FULL activo por `projection_type`;
- máximo un ENTITY activo por `(projection_type, entity_key)`;
- `entity_key` nulo en FULL y requerido en ENTITY;
- `WAITING_RESULT` exige current query metadata.

Outbox:

```text
PROCESSING → claimToken + leaseUntil obligatorios
PENDING/PUBLISHED/FAILED → sin claim activo
```

La migración física puede usar índices parciales PostgreSQL cuando ayuden a materializar estas invariantes.

---

# Consumer Inbox

Clave:

```text
consumerName + messageId
```

Aplica a mensajes durables de entrada que pueden producir efecto local:

```text
COMMAND
EVENT
RESULT de Query/recovery
```

Estados:

```text
APPLIED
DEFERRED
```

Una fila `DEFERRED` conserva envelope/payload suficientes para reaplicar el mismo mensaje.

ACK sólo después del commit local.

Reaplicación:

```text
Event/snapshot/reconciliación
→ reclamar DEFERRED
→ releer estado
→ stale: DEFERRED → APPLIED sin mutación
→ aplicable: mutación + Outbox + DEFERRED → APPLIED
→ aún faltante: conservar DEFERRED
```

No crear un nuevo `messageId` para reaplicar.

Commands académicos diferidos validan `executionGeneration` antes de mutar.

---

# Idempotencia Funcional

Scope:

```text
producer + operationType + idempotencyKey
```

Estados durables:

```text
DEFERRED
COMPLETED
FAILED
```

Fingerprint no incluye metadata de transporte como `messageId` o timestamps.

```text
misma key + mismo fingerprint
→ reutilizar estado/Result
→ no repetir mutación/Event

misma key + fingerprint distinto
→ IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST
```

Para Academic persistir:

```text
importId
planItemId
executionGeneration
pendingReason?
```

Una generación obsoleta nunca se reaplica.

---

# Transactional Outbox

Clases:

```text
EVENT
RESULT
QUERY
```

PR2 debe dejar preparada la persistencia neutral al broker; PR3 conecta el Relay RabbitMQ y congela los contratos wire ejecutables.

Estados:

```text
PENDING
PROCESSING
PUBLISHED
FAILED
```

Relay:

- múltiples réplicas;
- claim seguro;
- lease;
- fencing;
- publisher confirms;
- `AT_LEAST_ONCE`;
- retry con backoff.

El límite de tres intentos de Consumer retry **no** aplica automáticamente al Outbox Relay.

Fallo transitorio de RabbitMQ:

```text
PROCESSING
→ PENDING + backoff
→ conservar obligación durable
```

`FAILED` se reserva para error permanentemente no publicable o cuarentena operativa explícita.

---

# Concurrencia

## Optimistic Locking

```text
account_roles.version
system_accesses.version
```

Updates existentes:

```text
UPDATE ...
SET ..., version = version + 1
WHERE id = :id
  AND version = :expectedVersion
```

Cero filas actualizadas → conflicto funcional correspondiente.

## Creaciones

Constraints de unicidad son la defensa final bajo carrera.

Especialmente:

```text
account_roles(account_id)
system_accesses(account_id, system_id)
```

El aprovisionamiento derivado de Prácticas debe tolerar dos triggers concurrentes y crear máximo un acceso.

## Recovery Multi-réplica

Selection/claim de jobs y `DEFERRED` debe ser race-safe mediante lock/lease/fencing equivalente.

No se acepta un worker que mantenga un lock de DB mientras espera un Result remoto.

---

# ActorContext Y Auditoría

Actor humano:

```text
ACCOUNT { accountId }
```

Actor técnico V1 inbound:

```text
SYSTEM { service: academic }
```

Actor técnico del aprovisionamiento derivado:

```text
SYSTEM { service: authorization }
```

Nunca fabricar una Cuenta para representar un actor `SYSTEM`.

Persistencia/auditoría separa:

```text
actorType
actorAccountId?
actorService?
businessInitiatorAccountId?
businessImportId?
```

`businessInitiatorAccountId` no sustituye el actor técnico.

`authorization_audit_entries` es append-only y conserva cuando aplique:

```text
sourceMessageId
sourceProducer
aggregateType
aggregateId
aggregateVersion
action
actor
business attribution
correlationId
causationId
operationId
idempotencyKey
result
reasonCode/reasonDetail
occurredAt
```

No registrar secretos ni payloads completos innecesarios.

---

# Endpoints Operativos

Convención V1:

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/prometheus
```

Opcional de diagnóstico:

```text
GET /actuator/startup
```

No crear `/actuator/health/startup` como health group obligatorio.

`SystemController` temporal `/health` y `/version` debe eliminarse cuando Actuator cubra la necesidad.

Spring Security debe negar cualquier ruta HTTP no operativa. No existen endpoints HTTP de negocio.

## Readiness

Al final de PR3 debe considerar como mínimo:

- PostgreSQL;
- schema/migraciones compatibles;
- RabbitMQ;
- consumidores críticos;
- Outbox Relay capaz de progresar;
- FULL bootstrap crítico requerido y no completado.

Un recovery ENTITY aislado no vuelve globalmente not-ready; degrada/falla cerrado para esa entidad.

Para Prácticas, el bootstrap de elegibilidad es crítico cuando `PRACTICAS_PROFESIONALES` está habilitado.

---

# Observabilidad

Métricas mínimas finales:

```text
authorization_commands_received_total
authorization_commands_succeeded_total
authorization_commands_rejected_total
authorization_commands_failed_total
authorization_scope_checks_total
authorization_access_denied_total
authorization_messages_consumed_total
authorization_duplicate_messages_total
authorization_messages_retry_total
authorization_consumer_processing_duration_seconds
authorization_outbox_pending
authorization_outbox_failed_total
authorization_outbox_publish_attempts_total
authorization_outbox_publish_success_total
authorization_outbox_oldest_age_seconds
authorization_outbox_processing_stuck
authorization_projection_deferred_total
authorization_projection_deferred
authorization_projection_lag_seconds
authorization_projection_recovery_jobs_total
authorization_projection_recovery_failed_total
authorization_projection_recovery_active
authorization_projection_recovery_duration_seconds
authorization_projection_bootstrap_page_total
authorization_projection_deferred_reapplied_total
authorization_projection_deferred_stale_resolved_total
authorization_eligibility_pending
authorization_eligibility_reconciliation_total
authorization_dlq_messages_total
```

Logs estructurados con IDs/códigos, nunca secretos ni snapshots personales completos.

---

# Contratos De Comunicación: Regla De Implementación

Los contratos wire se materializan **únicamente en PR3**.

Se espera:

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

Y Java:

```text
com.estoma.authorization.contracts.*
com.estoma.authorization.infrastructure.messaging.*
```

PR3 debe materializar sólo los contratos que este servicio realmente produce, consume o expone.

No debe convertir este repositorio en fuente de verdad de reglas de Users/Authentication/Academic; las copias/esquemas de compatibilidad inbound deben probarse contra los contratos canónicos.

---

# Patrones Prohibidos

No implementar:

```text
Controller REST de negocio
ValidateAccess síncrono
HTTP/gRPC service-to-service de negocio
shared database
JPA/Hibernate
Domain con anotaciones Spring/Jackson/JDBC/AMQP
Application dependiendo de infrastructure
Rabbit listener con reglas de negocio
Event usado como Result de Command
Command Result usado como Event
messageId usado como idempotencyKey
operationId usado como dedupe
read-then-write sin protección de concurrencia
Outbox retry limitado a 3 por copiar política del Consumer
hard delete para SystemAccess/Role assignment
Academic concediendo PRACTICAS_PROFESIONALES por Command
recovery Query dentro del mismo request para convertir fail-closed en allow
```

---

# Estrategia De Pruebas

## Domain

- unit tests puros;
- transiciones de `SystemAccess`;
- compatibilidad Role/LoginType;
- policies sin Spring.

## Application

- use cases con ports fake/stub;
- autoridad;
- idempotencia;
- derivación de Events/Results;
- aprovisionamiento de Prácticas;
- recovery/reaplicación.

## Persistence

Testcontainers PostgreSQL obligatorio para:

- Flyway;
- repositories;
- optimistic locking;
- constraints;
- JSONB técnico;
- transacciones;
- carreras de creación;
- claims/leases.

No usar H2 para validar semántica PostgreSQL específica.

## Architecture

ArchUnit obligatorio.

## Messaging

Sólo PR3:

- Testcontainers RabbitMQ;
- publisher confirms;
- redelivery;
- retry/DLQ;
- Outbox Relay;
- contract serialization;
- routing;
- recovery Query/Result;
- E2E de mensajes críticos.

---

# Definition Of Ready Para Implementación

Antes de iniciar PR1:

- este PR documental mergeado;
- revisar `estomatologia-platform-docs/main` vigente;
- confirmar que no existe drift en Commands/Events/Queries/recovery;
- trabajar desde `develop` actualizado.

Antes de PR3:

- PR1 y PR2 mergeados;
- comportamiento completo probado sin broker;
- contratos canónicos de plataforma revisados nuevamente;
- no existen decisiones funcionales pendientes dentro de Authorization V1.

La implementación no debe usar el código para resolver contradicciones documentales silenciosamente. Si aparece un conflicto, se corrige primero la documentación canónica.
