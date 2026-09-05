# Platform BFF — System Contract V1

**Ruta:** `platform-bff/docs/system_contract.md`  
**Estado:** contrato local de implementación V1  
**Logical Component ID:** `bff`

## 1. Propósito

Este documento traduce la documentación canónica de `AnthonyNav/estomatologia-platform-docs` a un contrato directamente implementable para `platform-bff`.

No sustituye la documentación canónica. Ante drift o contradicción prevalece, en este orden:

```text
arquitectura transversal vigente
→ contrato del servicio propietario
→ 03-services/edge/bff/pr0-final-review-resolution.md
→ 03-services/edge/bff/pr0-contract-resolution.md
→ resto de documentación canónica del BFF
→ este contrato local
```

Fuentes canónicas principales:

- `02-architecture/messaging-architecture.md`;
- `02-architecture/client-architecture.md`;
- `03-services/edge/bff/README.md`;
- `03-services/edge/bff/pr0-contract-resolution.md`;
- `03-services/edge/bff/pr0-final-review-resolution.md`;
- `03-services/edge/bff/interfaces.md`;
- `03-services/edge/bff/commands.md`;
- `03-services/edge/bff/data-model.md`;
- `03-services/edge/bff/events.md`;
- `03-services/edge/bff/authorization-projection.md`;
- contratos `commands.md`, `events.md`, `queries.md` e `interfaces.md` de cada owner.

Este contrato existe junto al código para impedir que cada PR reinterprete topología, seguridad, persistencia o ownership.

---

## 2. Baseline de implementación

El módulo existente parte de:

```text
Spring Boot 4.1.0
Java 25
Spring MVC
Actuator
Prometheus
```

El código actual es únicamente foundation mínima y no representa la arquitectura final.

La implementación V1 debe añadir como mínimo:

```text
spring-boot-starter-jdbc
spring-boot-starter-flyway
spring-boot-starter-amqp
spring-boot-starter-security
soporte JWT/Jose local sin fetch HTTP de claves
postgresql runtime
flyway-database-postgresql runtime
validation HTTP
Testcontainers PostgreSQL
Testcontainers RabbitMQ
Spring Security test
Spring AMQP test
ArchUnit
```

No se introduce JPA/Hibernate en V1. La persistencia se implementa con JDBC/JdbcClient explícito, siguiendo el patrón de los servicios ya refinados.

---

## 3. Ownership y límites

`platform-bff` es propietario de:

```text
contratos HTTP orientados al cliente
mapeo HTTP → Command/Security RPC owner
seguimiento durable de operaciones del cliente
idempotencia de borde
Transactional Outbox del BFF
Consumer Inbox del BFF
proyecciones CQRS/read models del BFF
composición de vistas
validación local del access token
proyección técnica de revocación de sesión
proyección mínima de Authorization para acceso general/navegación
traducción de errores técnicos del borde
```

No es propietario de:

```text
Person
Account / Credential / Session
Role / Permission / SystemAccess
AcademicProfile / NRC / Enrollment / Import
Communication / EmailDelivery / InboxMessage
ServiceCalendar / ServiceSchedule / Closure / Cabin / Tank
Appointment / AppointmentTimeSlot / ExceptionalAuthorization
WashExecution / EntryValidation / ResourceAssignment / CleaningPeriod
reglas de disponibilidad/matching de Appointments
reglas de negocio de cualquier owner
```

Prohibiciones V1:

- no llamar microservicios por HTTP o gRPC;
- no escribir bases de otros servicios;
- no publicar Events de dominio;
- no convertirse en Process Manager;
- no encadenar silenciosamente Commands de owners distintos como una saga;
- no persistir secretos de Authentication;
- no usar RabbitMQ como Event Store;
- no inventar Queries o contratos owner inexistentes.

---

## 4. Arquitectura local objetivo

Paquetes conceptuales:

```text
com.estoma.platformbff
├── api/
│   ├── auth/
│   ├── operations/
│   ├── mailbox/
│   ├── admin/
│   └── wash/
├── application/
│   ├── commands/
│   ├── operations/
│   ├── securityrpc/
│   ├── projections/
│   └── queries/
├── contracts/
│   ├── messaging/
│   ├── owners/
│   └── http/
├── domain/
│   ├── operation/
│   ├── idempotency/
│   └── projection/
└── infrastructure/
    ├── persistence/
    ├── rabbitmq/
    ├── security/
    ├── observability/
    └── config/
```

Reglas:

- `domain` no depende de Spring;
- `application` depende de puertos, no de adapters concretos;
- DTO HTTP no se reutiliza como DTO wire RabbitMQ;
- contratos owner viven separados por owner/message type;
- controllers no contienen reglas de dominio ni acceso JDBC directo;
- listeners AMQP no ejecutan lógica de proyección fuera de application services transaccionales.

---

## 5. Identidad lógica y topología RabbitMQ

Identidad canónica:

```text
logicalComponentId = bff
producer = bff
result requester = bff
result queue = results.bff
event queue = events.bff-projections
```

Exchanges:

```text
platform.commands
platform.results
platform.events
platform.queries
platform.security.rpc
```

Routing de Commands iniciados por BFF:

| Owner | Routing key | Queue |
| --- | --- | --- |
| Users | `users` | `commands.users` |
| Authentication | `authentication` | `commands.authentication` |
| Authorization | `authorization` | `commands.authorization` |
| Academic | `academic` | `commands.academic` |
| Communications | `communications` | `commands.communications` |
| Wash Administration | `wash-administration` | `commands.wash-administration` |
| Appointments | `appointments` | `commands.appointments` |
| Wash Execution | `wash-execution` | `commands.wash-execution` |

No crear aliases `commands.*-service`, `results.*-service` o `queries.*-service`.

Resultados:

```text
owner
→ platform.results
→ routing key = bff
→ results.bff
```

Events:

```text
owner
→ platform.events
→ bindings explícitos
→ events.bff-projections
```

Security RPC:

```text
BFF
→ platform.security.rpc
→ rpc.authentication
→ reply efímero correlacionado
```

---

## 6. API HTTP V1

Prefijo funcional:

```text
/api/v1
```

Actuator/health queda fuera de ese namespace.

Familias:

```text
/api/v1/auth/**
/api/v1/me/**
/api/v1/operations/**
/api/v1/mailbox/**
/api/v1/admin/**
/api/v1/wash/**
```

Rutas base de seguridad:

```text
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
POST /api/v1/auth/password-recovery
POST /api/v1/auth/password-recovery/validate
POST /api/v1/auth/password-recovery/reset
POST /api/v1/me/password
GET  /api/v1/operations/{operationId}
```

Rutas base de buzón:

```text
GET  /api/v1/mailbox/messages
GET  /api/v1/mailbox/summary
POST /api/v1/mailbox/visits
```

Rutas de Lavado ya contractualmente permitidas:

```text
POST /api/v1/wash/appointments
POST /api/v1/wash/appointments/{appointmentId}/cancel
POST /api/v1/wash/exceptional-authorizations
POST /api/v1/wash/exceptional-authorizations/{authorizationId}/cancel
POST /api/v1/wash/executions/arrivals
POST /api/v1/wash/executions/{washExecutionId}/entry-decision
POST /api/v1/wash/executions/{washExecutionId}/reassignment
POST /api/v1/wash/executions/{washExecutionId}/exit
POST /api/v1/wash/executions/{washExecutionId}/complete
POST /api/v1/wash/executions/{washExecutionId}/clinic-cancel
POST /api/v1/wash/operational-resources/disable
POST /api/v1/wash/operational-resources/restore
GET  /api/v1/wash/supervision/pending-reassignments
GET  /api/v1/wash/supervision/home
POST /api/v1/wash/supervision/lookup
GET  /api/v1/wash/appointments/availability
```

Availability se rige por la resolución owner-derived de la sección 16. No realiza RPC síncrono ni
calcula reglas de Appointments en el BFF.

---

## 7. Command Gateway durable

Toda escritura HTTP de negocio que pertenezca a un owner sigue:

```text
HTTP request
→ validación de borde
→ BEGIN
   client_operations(PENDING)
   + idempotency_records
   + outbox_messages
  COMMIT
→ 202 Accepted
→ Outbox Relay
→ platform.commands
→ owner
```

`202 Accepted` significa únicamente persistencia local exitosa del BFF.

Respuesta mínima:

```json
{
  "operationId": "uuid",
  "status": "PENDING",
  "pollPath": "/api/v1/operations/{operationId}",
  "submittedAt": "timestamp"
}
```

Envelope Command mínimo, conforme al owner:

```text
messageId
messageType
messageVersion
messageKind = COMMAND
producer = bff
occurredAt
operationId
correlationId
causationId?
idempotencyKey
aggregateId?
aggregateVersion?
actor
payload owner-defined
```

El BFF no inventa `targetService` dentro del envelope.

---

## 8. Catálogo BFF de Commands V1

El payload, actor permitido, `expectedVersion`, errores y semántica pertenecen al owner.

### Users

```text
CreatePerson
UpdatePerson
ActivatePerson
DeactivatePerson
```

### Authentication — durable

```text
CreateAccount
ActivateAccount
DeactivateAccount
ChangeLoginIdentifier
RevokeAllAccountSessions
RequestPasswordRecovery
```

### Authorization

```text
AssignAccountRole
ChangeAccountRole
GrantSystemAccess
SuspendSystemAccess
RevokeSystemAccess
RestoreSystemAccess
```

### Academic

```text
CreateStudentAcademicProfile
CorrectStudentNumber
ChangeStudentSemester
ActivateStudentAcademicProfile
DeactivateStudentAcademicProfile
CreateAndActivateAcademicPeriod
RegisterCourseSection
UpdateCourseSection
ActivateCourseSection
DeactivateCourseSection
AddStudentEnrollment
ReactivateStudentEnrollment
RemoveStudentEnrollment
StartStudentImport
ResumeStudentImport
```

### Communications

```text
RegisterInboxVisit
RetryEmailDelivery
ResolveIndeterminateEmailDelivery
```

### Wash Administration

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

### Appointments

```text
ScheduleAppointment
CancelAppointment
GrantExceptionalAppointmentAuthorization
CancelExceptionalAppointmentAuthorization
```

### Wash Execution

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

Commands internos de owners no se exponen por accidente.

---

## 9. `client_operations` y Result dispatcher

Estados:

```text
PENDING
SUCCEEDED
REJECTED
FAILED
EXPIRED
```

Una operación terminal no vuelve a `PENDING`.

`results.bff` recibe dos clases de Results:

```text
Result de Command cliente
→ Inbox
→ client_operations

Result de Query recovery
→ Inbox
→ projection recovery
```

Un Result de recovery nunca actualiza una operación cliente.

Antes de aplicar un Result se valida:

- `messageKind = RESULT`;
- `producer` allowlisted para ese `messageType`;
- `messageVersion` soportada;
- `operationId`/correlación esperada;
- forma mínima `status/data?/error?`.

Un Result desconocido o incompatible se aísla y no completa estado observable.

---

## 10. Idempotencia de borde

Toda escritura HTTP traducida a Command durable requiere `Idempotency-Key`.

Scope:

```text
actorAccountId? + publicOperation + idempotencyKey
```

Se persiste `requestFingerprint` normalizado.

```text
misma key + mismo fingerprint
→ reutilizar operationId/resultado existente
→ no crear segundo Command

misma key + fingerprint diferente
→ HTTP 409
→ no publicar
```

La idempotencia del BFF no sustituye la idempotencia funcional del owner ni Consumer Inbox.

---

## 11. Security RPC Gateway

Operaciones BFF permitidas:

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

`GetRecoveryCodeForDelivery` está prohibido para BFF.

Defaults V1:

```text
request TTL = 10 s
BFF timeout = 8 s
automatic late retries = 0
```

Reglas:

- mensaje transient;
- sin Outbox;
- sin Inbox durable;
- sin `operationId`;
- sin `platform.results`;
- sin DLQ;
- sin retry automático tras timeout;
- no persistir request/response;
- no loggear password, OTP, recovery code, access token, refresh token ni temporary password.

`SetTemporaryPassword` y `RegenerateTemporaryPassword` respetan idempotencia owner; una repetición completada no recrea el secreto.

### Refresh single-flight

`RefreshSession` debe impedir refresh efectivo concurrente del mismo `sessionId`.

V1 requiere:

```text
single-flight por instancia
+
coordinación distribuida si replicaCount > 1
```

La coordinación puede implementarse mediante lock/fencing en PostgreSQL o mecanismo equivalente acordado; no debe depender solo de memoria local cuando exista escalamiento horizontal.

---

## 12. JWT y revocación local

El BFF valida localmente:

```text
algoritmo
issuer
audience
firma
exp
claims requeridas
```

El JWK Set público llega por configuración/GitOps. Está prohibido descargar claves por HTTP/gRPC/Query/RPC en cada request.

Tablas técnicas:

```text
account_person_projection
session_revocations
account_session_revocation_cutoffs
person_session_revocation_cutoffs
```

Regla efectiva:

```text
rechazar si sid está revocado
OR iat <= person cutoff
OR (iat <= account cutoff AND sid != except_session_id)
```

Timestamps de cutoff usan segundos Unix UTC y son monotónicos.

`OtherAccountSessionsRevoked` mantiene excepción del `currentSessionId`; un cutoff `ALL` ganador elimina cualquier excepción.

---

## 13. Authorization projection y recovery

Tablas:

```text
authorization_role_projection
authorization_access_projection
```

Events:

```text
AccountRoleAssigned
AccountRoleChanged
SystemAccessGranted
SystemAccessSuspended
SystemAccessRevoked
SystemAccessRestored
```

Rol y acceso tienen version fencing independiente.

La proyección se usa para navegación/acceso general y fail-closed en el borde; el owner de cada Command sigue validando su autorización específica.

Recovery owner explícito:

```text
GetAccountAuthorizationSnapshot
ListAccountAuthorizationSnapshotsPage
```

Flujo:

```text
platform.queries
→ queries.authorization
→ platform.results
→ results.bff
→ projection recovery dispatcher
```

Una proyección de Authorization ausente/no confiable falla cerrada.

---

## 14. Consumer Inbox y Events

Cola única inicial:

```text
events.bff-projections
```

PK lógica Inbox:

```text
consumer_name + message_id
```

Aplicación:

```text
BEGIN
Inbox
+ read model / cutoff / projection state
COMMIT
ACK
```

Versionado:

```text
messageId → dedupe exacto
owner source version → monotonicidad
```

No exigir contigüidad si se consume un subconjunto de Events.

Un mismo `aggregateVersion`/`executionVersion` puede corresponder a Events distintos. En Wash Execution, por ejemplo, `WashEntryAuthorized`, `WashResourceAssigned` y `WashExecutionStarted` pueden compartir versión y todos deben aplicarse según su propia semántica.

Validar siempre `producer + messageType + messageVersion + aggregate metadata` antes de mutar una proyección.

---

## 15. Persistencia V1

Tecnología:

```text
PostgreSQL
Flyway
Spring JDBC/JdbcClient
```

Tablas técnicas mínimas:

```text
client_operations
idempotency_records
outbox_messages
inbox_messages
projection_status
projection_recovery_jobs
```

Seguridad/autorización:

```text
account_person_projection
authorization_role_projection
authorization_access_projection
session_revocations
account_session_revocation_cutoffs
person_session_revocation_cutoffs
```

Read models tipados mínimos por familias:

```text
person_presentation_read_model
academic_period_read_model
student_academic_profile_read_model
student_academic_eligibility_read_model
course_section_read_model
student_enrollment_read_model
student_import_read_model
student_import_validation_error_read_model
mailbox_messages_read_model
mailbox_visits_read_model
wash_service_calendar_read_model
wash_service_schedule_read_model
wash_administrative_closure_read_model
wash_cabin_read_model
wash_tank_read_model
appointment_time_slot_read_model
appointment_read_model
exceptional_appointment_authorization_read_model
wash_execution_read_model
wash_resource_assignment_read_model
wash_operational_resource_unavailability_read_model
wash_cleaning_period_read_model
```

No usar una tabla genérica `read_models(type, payload_json)` como sustituto de estado funcional tipado.

JSON puede utilizarse de forma acotada para metadata técnica, envelope Outbox o detalles de error, con redacción de secretos.

---

## 16. Disponibilidad de Appointments — gate resuelto

`appointments-service` es owner de `GetAppointmentAvailability` y del algoritmo determinista de matching.

El BFF no puede reconstruir ese resultado a partir de:

```text
capacity - demand
```

ni copiar el algoritmo sin apropiarse de lógica de dominio.

Las Queries de recovery de Appointments:

```text
GetAppointmentExecutionContext
ListAppointmentExecutionContextsPage
```

siguen siendo recovery para `wash-execution` y no se reutilizan como interfaz para BFF.

El gate inicial quedó resuelto por el contrato owner V1 publicado en
`appointments-service/docs/asyncapi.yaml` y `appointments-service/docs/system_contract.md`:

```text
GetAppointmentAvailability v1
platform.queries → appointments

GetAppointmentAvailabilityResult v1
platform.results → bff
```

La ruta HTTP usa cache-aside asíncrono. Un hit fresco sirve el snapshot owner-derived. Un miss o
snapshot vencido registra duraderamente una Query y responde `503`; un reintento posterior puede
servir el Result ya materializado. El BFF aplica fencing por Query/correlation/contexto, Inbox
idempotente y sólo considera fresco el intervalo inclusivo
`snapshotGeneratedAt <= now <= freshUntil`, limitado además a 30 segundos.

Continúa prohibido:

- implementar el endpoint con aproximaciones;
- usar las Queries de recovery existentes;
- duplicar matching;
- consultar Appointments por HTTP/gRPC.

La forma exacta del wire y la evidencia que resuelve el gate están en
[`appointment_availability_gate_resolution.md`](appointment_availability_gate_resolution.md).

---

## 17. Composición y flujos multi-owner

El BFF puede componer una pantalla con read models de varios owners.

No puede convertir una secuencia como:

```text
CreatePerson
→ CreateAccount
→ SetTemporaryPassword
→ AssignAccountRole
→ GrantSystemAccess
```

en una saga oculta dentro del BFF.

V1 acepta un wizard cliente con operaciones independientes y reanudables. Si negocio necesita automatización distribuida, se documentará un Process Manager explícito fuera del BFF.

No se promete atomicidad distribuida.

---

## 18. Errores HTTP

Errores inmediatos del borde usan:

```text
Content-Type: application/problem+json
```

Forma mínima:

```json
{
  "type": "urn:estoma:error:<code>",
  "title": "...",
  "status": 400,
  "code": "BFF.INVALID_REQUEST",
  "detail": "...",
  "traceId": "..."
}
```

Semántica base:

```text
400 → request malformada / validación de forma
401 → autenticación ausente, inválida o expirada
403 → actor autenticado sin acceso general
404 → recurso público no sensible inexistente
409 → conflicto local de idempotencia/precondición del borde
503 → proyección crítica no disponible o Security RPC timeout
```

Los rechazos funcionales owner posteriores al `202` permanecen en `/operations/{operationId}` con su `error.code` original.

Login/recovery no deben revelar existencia de Cuenta.

---

## 19. Retención

Mínimos V1:

| Estado | Retención |
| --- | --- |
| `client_operations` terminal | 7 días |
| `client_operations PENDING` | no purgar por TTL |
| `idempotency_records` | 24 h después del terminal |
| Outbox `PUBLISHED` | 7 días |
| Outbox `PENDING` | no purgar |
| Outbox `FAILED`/quarantine | 30 días o hasta resolución |
| Inbox | 30 días |
| recovery jobs terminales | 30 días |
| read models | lifecycle owner |
| revocation cutoffs | lifecycle operativo de Cuenta/Persona |

La configuración puede ampliar, no reducir silenciosamente, estos mínimos.

---

## 20. Readiness, observabilidad y secretos

Readiness considera como mínimo:

```text
PostgreSQL disponible
Flyway/schema válida
RabbitMQ disponible
Outbox Relay saludable
results.bff consumer saludable
events.bff-projections consumer saludable
JWK config válida
Authorization/revocation projections confiables
```

No incluir en logs, métricas o trazas:

```text
passwords
recovery codes / OTP
access tokens
refresh tokens
temporary passwords
Security RPC payloads sensibles
mailbox title/body completos
```

Una proyección funcional no crítica degradada puede afectar únicamente sus endpoints. Authorization/revocación necesaria para acceso general falla cerrada.

---

## 21. Gate de implementación

Después de este contrato local:

```text
PR1 foundation                         = UNBLOCKED
Command Gateway / operation tracking   = UNBLOCKED
Security RPC foundation                = UNBLOCKED
JWT/revocation foundation              = UNBLOCKED
Authorization projection/recovery      = UNBLOCKED
Typed read-model foundation            = UNBLOCKED
Appointments availability adapter      = OWNER_CONTRACT_READY
Other owner adapters                   = gated by owner implementation readiness
SSE                                     = OUT OF SCOPE V1 inicial
BFF as Process Manager                  = FORBIDDEN
HTTP/gRPC BFF → microservice            = FORBIDDEN
```

No quedan decisiones internas conocidas que bloqueen comenzar PR1 del BFF.
