# Platform BFF — Execution Plan V1

**Ruta:** `platform-bff/docs/execution_plan.md`  
**Estado:** gate local de implementación V1  
**Contrato local:** [`system_contract.md`](system_contract.md)

## 1. Objetivo

Implementar `platform-bff` de forma incremental sin mezclar en un único PR:

```text
persistencia
HTTP público
Command Gateway
RabbitMQ durable
Security RPC
JWT/revocación
Authorization recovery
proyecciones CQRS
composición multi-owner
hardening operativo
```

El BFF es una superficie crítica: recibe tráfico cliente, transporta identidad, ve secretos de autenticación de forma transitoria y consume contratos de todos los owners. Por eso el orden de implementación debe permitir revisar cada frontera por separado.

La documentación canónica vive en `AnthonyNav/estomatologia-platform-docs`. Este plan define únicamente el orden de trabajo dentro de `estoma-services`.

---

## 2. Baseline y gate previo

Estado actual del módulo:

```text
Spring Boot 4.1.0
Java 25
WebMVC
Actuator
Prometheus
SystemController mínimo
sin PostgreSQL
sin Flyway
sin RabbitMQ
sin Spring Security
sin Command Gateway
sin Security RPC
sin read models
```

Antes de implementar comportamiento debe considerarse cerrado [`system_contract.md`](system_contract.md), en especial:

- `logicalComponentId = bff`;
- `results.bff`;
- `events.bff-projections`;
- `/api/v1`;
- `202 + operationId + polling` para Commands durables;
- PostgreSQL + Flyway + JdbcClient;
- Security RPC transient, TTL 10 s, timeout 8 s, sin retries tardíos;
- JWT validado localmente;
- revocación local por Events;
- Authorization projection fail-closed;
- BFF no es Process Manager;
- disponibilidad de Appointments usa el contrato owner V1 y cache-aside asíncrono.

---

## 3. Estrategia de PRs

Secuencia propuesta:

```text
PR1 Foundation + Persistence
→ PR2 Durable Command Gateway + Operations
→ PR3 Security / Authentication Edge
→ PR4 Projection Runtime + Authorization Recovery
→ PR5 Public APIs + Owner Adapters + Composition
→ PR6 Hardening / Resilience / Full Integration
```

Cada PR debe poder revisarse y probarse sin depender de que el siguiente exista.

Los PRs se integran secuencialmente a `develop`; no mantener una pila larga abierta si un PR anterior ya está listo para merge.

---

# 4. PR1 — Foundation, arquitectura y persistencia

## Objetivo

Convertir el skeleton actual en una aplicación preparada para implementar los dos gateways y las proyecciones, todavía sin abrir integraciones funcionales con owners.

## Dependencias

Agregar como mínimo:

```text
spring-boot-starter-jdbc
spring-boot-starter-flyway
spring-boot-starter-amqp
spring-boot-starter-security
soporte JWT/Jose local
spring validation
postgresql
flyway-database-postgresql

spring-boot-starter-jdbc-test
spring-boot-starter-security-test
spring-boot-starter-amqp-test
spring-boot-testcontainers
testcontainers-junit-jupiter
testcontainers-postgresql
testcontainers-rabbitmq
archunit-junit5
```

No introducir JPA/Hibernate.

## Arquitectura

Crear la separación base:

```text
api/
application/
contracts/
domain/
infrastructure/
```

Agregar ArchUnit para impedir:

- `domain → Spring`;
- controllers → repositories;
- listeners → repositories directamente;
- DTO HTTP reutilizado como contrato owner wire;
- infrastructure importada por domain.

## Flyway / PostgreSQL

Crear las tablas técnicas base:

```text
client_operations
idempotency_records
outbox_messages
inbox_messages
projection_status
projection_recovery_jobs
```

Crear además las tablas de seguridad necesarias desde el inicio:

```text
account_person_projection
session_revocations
account_session_revocation_cutoffs
person_session_revocation_cutoffs
authorization_role_projection
authorization_access_projection
```

Los read models funcionales pueden añadirse aquí como migraciones vacías/estructurales si la revisión lo considera manejable, pero su lógica de actualización pertenece a PR4.

## Repositorios técnicos

Implementar puertos + adapters JDBC para:

```text
ClientOperationRepository
IdempotencyRepository
OutboxRepository
InboxRepository
ProjectionStatusRepository
ProjectionRecoveryJobRepository
```

Semántica mínima:

```text
client operation terminal no vuelve a PENDING
idempotency scope único
Inbox unique(consumer_name, message_id)
Outbox claim concurrente seguro
```

## Configuración

Preparar propiedades tipadas para:

```text
PostgreSQL
RabbitMQ
logicalComponentId = bff
Security RPC TTL/timeout
JWK local config
retention defaults
```

No descargar JWK por red.

## Calidad

Agregar:

- tests de migración con PostgreSQL real;
- tests de constraints;
- tests de repositorios;
- tests ArchUnit;
- arranque de contexto con Testcontainers/config de test;
- health endpoints existentes conservados.

## Definition of Done PR1

- `./mvnw clean verify` verde;
- Flyway crea `bff-db` desde cero;
- no hay JPA;
- arquitectura hexagonal protegida;
- repositorios técnicos tienen integración real PostgreSQL;
- no existe todavía lógica owner-specific;
- no existen endpoints funcionales `/api/v1` salvo scaffolding/error handling si se requiere;
- ninguna credencial/token se persiste.

---

# 5. PR2 — Durable Command Gateway y Operations API

## Objetivo

Cerrar el camino durable:

```text
HTTP write intent
→ operation tracking
→ idempotency
→ Outbox
→ RabbitMQ Command
→ Result
→ operation terminal
→ polling
```

sin implementar todavía todo el catálogo HTTP owner-specific.

## Core de Commands

Implementar contratos neutrales:

```text
CommandEnvelope
ActorContext
MessageMetadata
OwnerRoute
CommandSubmission
```

Agregar registry allowlisted:

```text
messageType → owner logicalComponentId → messageVersion
```

Nunca aceptar un `targetService` arbitrario desde request cliente.

## Transactional Outbox

Flujo obligatorio:

```text
BEGIN
client_operations(PENDING)
+ idempotency_records
+ outbox_messages
COMMIT
→ 202
```

Relay:

- claim concurrente/fenced;
- publisher confirms;
- retry técnico acotado;
- `PENDING/PUBLISHED/FAILED`;
- métricas de backlog y edad;
- shutdown seguro.

## Idempotencia HTTP

Implementar:

```text
Idempotency-Key
actorAccountId? + publicOperation + key
requestFingerprint
```

Casos:

```text
misma key + mismo fingerprint → mismo operationId
misma key + distinto fingerprint → 409
```

Probar concurrencia de dos requests simultáneas con la misma key.

## Results consumer

Consumir:

```text
platform.results
→ results.bff
```

Validar:

```text
messageKind
producer
messageType
messageVersion
operationId
payload.status
```

Dispatcher V1:

```text
Command Result → client_operations
Recovery Query Result → NO tocar client_operations
```

En PR2 puede existir únicamente el branch de Command Result; el branch de recovery se incorpora plenamente en PR4, pero el dispatcher debe quedar diseñado para distinguirlos desde aquí.

## Operations API

Implementar:

```text
GET /api/v1/operations/{operationId}
```

Reglas:

- solo actor autorizado puede observar la operación;
- `PENDING/SUCCEEDED/REJECTED/FAILED/EXPIRED`;
- no devolver envelope interno completo;
- no exponer routing keys/colas;
- errores owner permanecen como `error.code` owner.

## Endpoint piloto

Para validar end-to-end usar un Command owner que ya esté implementado y cuyo contrato wire se confirme en `develop` al iniciar PR2.

No elegir disponibilidad de Appointments.

Si ningún owner está listo en ese momento, probar Command Gateway con producer/consumer fixture de integración dentro de tests, sin inventar un contrato productivo.

## Definition of Done PR2

- `202` solo después del commit local;
- redelivery de Result no duplica transición;
- Result inválido no completa operación;
- publisher confirms activos;
- Outbox sobrevive restart;
- idempotencia concurrente estable;
- polling funciona;
- integration test PostgreSQL + RabbitMQ real;
- ningún Security RPC pasa por Outbox.

---

# 6. PR3 — Security RPC, JWT y Authentication edge

## Objetivo

Implementar la superficie sensible de Authentication separada del Command Gateway.

## Security RPC adapter

Implementar:

```text
platform.security.rpc
→ rpc.authentication
→ reply efímero correlacionado
```

Defaults:

```text
TTL = 10 s
timeout = 8 s
automatic retry = 0
```

Operaciones:

```text
AuthenticateAccount
RefreshSession
RevokeSession
ValidateRecoveryCode
ResetPassword
ChangePassword
SetTemporaryPassword
RegenerateTemporaryPassword
```

No implementar `GetRecoveryCodeForDelivery`.

## Secret handling

Probar explícitamente que:

- request/response RPC no llegan a Outbox;
- no llegan a Inbox durable;
- no se escriben en DB;
- no se registran completos en logs/traces;
- exception handlers redactan payloads;
- métricas no incluyen identifier sensible ni token.

## JWT local

Implementar filtro/adaptador que valide:

```text
algorithm
issuer
audience
signature
exp
claims requeridas
```

JWK se carga desde configuración montada/GitOps, sin HTTP runtime.

## Revocación

Implementar aplicación de:

```text
AccountCreated
SessionRevoked
AllAccountSessionsRevoked
OtherAccountSessionsRevoked
AccountDeactivated
PersonDeactivated
```

Regla:

```text
sid revoked
OR iat <= person cutoff
OR (iat <= account cutoff AND sid != except_session_id)
→ reject
```

Probar orden tardío y prioridad `ALL > OTHER` en mismo timestamp.

## Refresh single-flight

Implementar protección de `RefreshSession`:

```text
misma sessionId concurrente
→ máximo una operación efectiva concurrente
```

Con más de una réplica, usar coordinación PostgreSQL/lock fenced o mecanismo equivalente. No usar únicamente un `ConcurrentHashMap` como garantía final.

## HTTP auth endpoints

Implementar:

```text
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
POST /api/v1/auth/password-recovery
POST /api/v1/auth/password-recovery/validate
POST /api/v1/auth/password-recovery/reset
POST /api/v1/me/password
```

`password-recovery` durable usa Command Gateway de PR2; los demás usan Security RPC según contrato.

## Error model

Aplicar `application/problem+json` y no enumerar Cuenta en login/recovery.

## Gate externo

Antes de integrar contra Authentication real verificar que `authentication-service` en `develop` tenga implementados los contratos wire necesarios. Si alguna operación aún no existe, mantener ese endpoint fuera de exposición productiva y cubrir el adapter con fixture contractual hasta que el owner esté listo.

## Definition of Done PR3

- secretos no persisten;
- timeout RPC produce `503` controlado;
- no retry tardío;
- JWT local sin red;
- revocation events invalidan correctamente tokens;
- refresh single-flight probado concurrentemente;
- `PASSWORD_CHANGE_REQUIRED` restringe superficie según contrato;
- tests de seguridad negativos incluidos.

---

# 7. PR4 — Projection Runtime, read models y Authorization recovery

## Objetivo

Implementar el runtime CQRS del BFF:

```text
platform.events
→ events.bff-projections
→ Inbox
→ dispatcher
→ typed read model
```

## Event boundary

Crear registry explícito:

```text
producer + messageType + messageVersion
→ projector
```

Rechazar/aislar:

- producer inesperado;
- versión no soportada;
- aggregate metadata inconsistente;
- payload incompleto.

## Same-version semantics

Probar que Events distintos con misma source version se aplican cuando corresponde.

Caso obligatorio Wash Execution:

```text
WashEntryAuthorized
WashResourceAssigned
WashExecutionStarted
```

No deduplicar por versión; deduplicar por `messageId`.

## Read models

Implementar progresivamente las tablas tipadas del contrato local:

### Users

```text
person_presentation_read_model
```

### Academic

```text
academic_period_read_model
student_academic_profile_read_model
student_academic_eligibility_read_model
course_section_read_model
student_enrollment_read_model
student_import_read_model
student_import_validation_error_read_model
```

### Communications

```text
mailbox_messages_read_model
mailbox_visits_read_model
```

### Wash Administration

```text
wash_service_calendar_read_model
wash_service_schedule_read_model
wash_administrative_closure_read_model
wash_cabin_read_model
wash_tank_read_model
```

### Appointments

```text
appointment_time_slot_read_model
appointment_read_model
exceptional_appointment_authorization_read_model
```

No añadir matching/disponibilidad final owner-derived.

### Wash Execution

```text
wash_execution_read_model
wash_resource_assignment_read_model
wash_operational_resource_unavailability_read_model
wash_cleaning_period_read_model
```

## Authorization recovery

Implementar:

```text
GetAccountAuthorizationSnapshot
ListAccountAuthorizationSnapshotsPage
```

por:

```text
platform.queries
→ queries.authorization
→ results.bff
```

Result de recovery:

```text
→ Inbox
→ recovery dispatcher
→ projection + cursor/job atómico
```

Nunca actualizar `client_operations`.

Implementar `ENTITY` y `FULL` durable conforme al owner, con generación/cursor persistidos.

## Projection readiness

Estados:

```text
BOOTSTRAPPING
READY
DEGRADED
REBUILDING
```

Authorization/revocation no confiable → fail-closed.

Otras proyecciones pueden degradar únicamente endpoints dependientes.

## Recovery no disponible

No inventar snapshot Queries para owners que no las publican. Mantener:

```text
backup/PITR bff-db
+ convergencia incremental posterior
```

hasta contrato owner explícito.

## Definition of Done PR4

- consumer `events.bff-projections` con Inbox transaccional;
- redelivery seguro;
- stale Events no sobrescriben estado nuevo;
- same-version Events distintos funcionan;
- Authorization FULL/ENTITY recovery probado;
- `NOT_FOUND` no borra estado nuevo incorrectamente;
- read models no contienen PII innecesaria;
- mailbox body/title no aparecen en logs/traces.

---

# 8. PR5 — APIs públicas, owner adapters y composición

## Objetivo

Exponer las capacidades de cliente que ya tienen owner wire implementado, usando exclusivamente PR2–PR4.

## Mailbox

```text
GET  /api/v1/mailbox/messages
GET  /api/v1/mailbox/summary
POST /api/v1/mailbox/visits
```

Validar actor/account scope.

## Admin

Exponer bajo `/api/v1/admin/**` únicamente operaciones owner implementadas y autorizadas.

DTO HTTP puede ser client-oriented, pero mapea 1:1 a una intención owner; no fusionar varios Commands en un único endpoint aparentemente atómico.

## Appointments

Permitido cuando el owner wire esté listo:

```text
POST /api/v1/wash/appointments
POST /api/v1/wash/appointments/{appointmentId}/cancel
POST /api/v1/wash/exceptional-authorizations
POST /api/v1/wash/exceptional-authorizations/{authorizationId}/cancel
```

Disponible mediante contrato owner-derived:

```text
GET /api/v1/wash/appointments/availability
```

Un miss/stale responde `503` después de registrar un refresh durable; nunca bloquea la request
esperando el Result.

## Wash Execution

Exponer, cuando los ocho Commands estén implementados en owner:

```text
POST /api/v1/wash/executions/arrivals
POST /api/v1/wash/executions/{id}/entry-decision
POST /api/v1/wash/executions/{id}/reassignment
POST /api/v1/wash/executions/{id}/exit
POST /api/v1/wash/executions/{id}/complete
POST /api/v1/wash/executions/{id}/clinic-cancel
POST /api/v1/wash/operational-resources/disable
POST /api/v1/wash/operational-resources/restore
GET  /api/v1/wash/supervision/pending-reassignments
```

## Composición

Implementar vistas combinando read models locales:

```text
Person + Academic
Appointment + WashExecution
Appointment + CourseSection
WashExecution + ResourceAssignment + Person presentation
```

No hacer llamadas síncronas owner durante composición.

## QR

`qrUsageContext` se compone en lectura y no se persiste.

No crear segunda entidad/identificador para “QR de salida”.

## Multi-owner workflows

Los wizards administrativos permanecen secuencias de operaciones independientes.

No implementar saga automática dentro del BFF.

## Definition of Done PR5

- endpoints solo para owners realmente listos;
- autorización general BFF + autorización específica owner;
- DTO HTTP no filtra wire internals;
- ninguna ruta usa HTTP/gRPC interno;
- Availability conserva ownership, freshness y fencing del contrato owner;
- OpenAPI/documentación HTTP refleja exactamente lo expuesto.

---

# 9. PR6 — Hardening, resiliencia y E2E

## Objetivo

Cerrar comportamiento operativo antes de considerar V1 lista para staging.

## Concurrencia

Probar:

```text
mismo Idempotency-Key concurrente
mismo operationId / Result duplicado
Outbox Relay multi-instancia
Inbox duplicate delivery
RefreshSession multi-instancia
projection recovery + Events concurrentes
same-version Events
```

## Failure injection

Cubrir:

- PostgreSQL cae antes/después del commit;
- RabbitMQ no disponible;
- publisher confirm timeout;
- Result llega dos veces;
- Event llega fuera de orden;
- Security RPC timeout;
- BFF reinicia con Outbox pendiente;
- BFF reinicia durante FULL recovery;
- projection crítica `DEGRADED`;
- JWK config inválida.

## Retención

Implementar jobs/maintenance seguros para:

```text
client_operations terminales
idempotency_records
Outbox PUBLISHED
Inbox
recovery jobs
```

Nunca purgar `PENDING` por TTL genérico.

## Observabilidad

Métricas mínimas:

```text
HTTP latency/status
active requests
Outbox backlog/age/retries
results.bff lag/errors
events.bff-projections lag/errors
operation terminal latency
Security RPC latency/timeouts
projection readiness/recovery progress
idempotency conflict count
```

Logs/traces con:

```text
traceId
correlationId
operationId cuando aplique
messageId cuando aplique
```

sin secretos ni contenido completo de mailbox.

## E2E

Crear pruebas con PostgreSQL + RabbitMQ reales y fixtures/owners reales disponibles para:

```text
Command → Result → polling
login/refresh/logout
session revocation
mailbox projection + visit
Appointment Command disponible
Wash Execution Command disponible
Authorization projection recovery
```

Availability de Appointments entra al E2E coordinadamente con el owner que publica Query/Result V1.

## Definition of Done PR6

- `./mvnw clean verify` verde;
- Testcontainers PostgreSQL/RabbitMQ verde;
- no existen secretos en logs capturados por tests;
- readiness fail-closed correcto;
- restart/recovery probado;
- métricas operativas suficientes;
- contratos expuestos coinciden con owners;
- sin endpoints placeholder que aparenten capacidades no implementadas.

---

## 10. Gates de integración por owner

Antes de implementar cada adapter productivo, confirmar en `develop`:

```text
1. contrato canónico owner cerrado
2. messageType/version/routing definidos
3. producer/actor BFF permitido
4. owner consumer/listener implementado
5. Result terminal implementado
6. Events requeridos por read model implementados
7. topología disponible en infra
```

Si falla cualquiera:

```text
adapter productivo = BLOCKED
foundation genérica = puede continuar
```

No resolver faltantes del owner dentro del BFF.

---

## 11. Gate especial de Appointments availability

Estado inicial, preservado como antecedente:

```text
GET /api/v1/wash/appointments/availability
→ GATED_BY_APPOINTMENTS_OWNER_CONTRACT
```

Para abrirlo se requiere PR documental previo en `estomatologia-platform-docs` que defina una fuente owner-derived consumible por BFF, seguido de implementación en Appointments.

Opciones válidas deben preservar ownership, por ejemplo:

```text
Event/proyección owner-derived
snapshot/read-model contract explícito para BFF
otra decisión arquitectónica aprobada
```

No es válida una implementación BFF del matching.

Estado actual:

```text
GetAppointmentAvailability v1 + GetAppointmentAvailabilityResult v1 publicados por Appointments
→ OWNER_CONTRACT_READY
→ adapter BFF cache-aside asíncrono permitido
```

La resolución detallada y las dependencias de merge están en
[`appointment_availability_gate_resolution.md`](appointment_availability_gate_resolution.md).

---

## 12. Criterio de finalización V1

`platform-bff` puede considerarse V1 listo para integración cuando:

```text
foundation persistence                READY
Command Gateway                        READY
results.bff                            READY
Security RPC                           READY
JWT/revocation                         READY
Authorization projection/recovery      READY
events.bff-projections                 READY
read models requeridos                 READY
HTTP owner adapters implementables     READY
observability/resilience               READY
```

Una capability owner aún gated no debe impedir desplegar foundation, pero tampoco puede presentarse públicamente como disponible.

No forman parte del gate inicial:

```text
SSE
short-wait por Result en la misma request
cache avanzada
separación en múltiples deployables
Prácticas Profesionales fuera del alcance actual
```
