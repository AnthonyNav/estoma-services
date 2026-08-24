# Wash Execution Service — Hexagonal Architecture Contract V1

**Ruta:** `wash-execution-service/docs/hexagonal_architecture_contract.md`
**Estado:** contrato arquitectónico obligatorio para PR1–PR4
**Aplica a:** `wash-execution-service`

## 1. Propósito

Este documento convierte la arquitectura hexagonal de `wash-execution-service` en una restricción contractual verificable.

No introduce reglas nuevas de negocio. Complementa `system_contract.md` y `execution_plan.md` y evita que la implementación mezcle dominio, casos de uso, contratos wire y adapters técnicos.

Ante contradicción funcional, prevalece la documentación canónica de `estomatologia-platform-docs`. Ante una decisión puramente estructural de implementación dentro de este servicio, este contrato es obligatorio salvo ADR o corrección explícita posterior.

---

## 2. Estructura obligatoria

Package root objetivo:

```text
com.estoma.washexecution
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
├── contracts
│   ├── command
│   ├── event
│   ├── query
│   └── result
└── infrastructure
    ├── persistence
    ├── messaging
    ├── scheduling
    ├── security
    ├── observability
    └── config
```

Los nombres internos pueden especializarse si la responsabilidad permanece equivalente, pero no puede colapsarse la separación de capas.

---

## 3. Regla de dependencias

Dirección permitida:

```text
infrastructure ──→ application ──→ domain
      │                 │
      └────────→ contracts
```

Restricciones:

```text
domain
→ no depende de application
→ no depende de infrastructure
→ no depende de contracts
→ no depende de Spring
→ no depende de JDBC/JdbcClient
→ no depende de RabbitMQ/Spring AMQP
→ no depende de Jackson

application
→ depende de domain
→ define y consume ports
→ no depende de adapters concretos
→ no depende de Spring AMQP
→ no ejecuta SQL
→ no conoce JdbcClient/DataSource

contracts
→ contiene exclusivamente modelos wire/versionados y validación estructural
→ no contiene reglas de negocio
→ no se utiliza como modelo de dominio

infrastructure
→ implementa ports
→ contiene adapters JDBC/Flyway, RabbitMQ, schedulers, configuración Spring y observabilidad
→ puede mapear contracts ↔ application/domain
→ no concentra reglas funcionales propietarias
```

El objetivo es que `domain` y los casos de uso puedan probarse sin levantar Spring, PostgreSQL ni RabbitMQ.

---

## 4. Ports obligatorios

### Input ports

Los casos de uso públicos deben entrar por puertos de aplicación, no directamente por listeners/adapters.

Como mínimo, PR2 debe tener puertos equivalentes para:

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

Las operaciones automáticas como `CompleteExpiredCleaningPeriods` también entran a Application mediante un input port/use case; el scheduler solo dispara la operación.

### Output ports

Application debe depender de abstracciones para capacidades externas/técnicas, por ejemplo:

```text
WashExecutionRepository
EntryValidationRepository
ResourceAssignmentRepository
ResourceUnavailabilityRepository
CleaningPeriodRepository
AppointmentProjectionRepository
WashAdministrationProjectionRepository
AuthorizationProjectionRepository
OutboxRepository
InboxRepository
Clock
```

Los nombres exactos pueden variar. La regla obligatoria es que Application no dependa del adapter concreto.

---

## 5. Persistencia

Persistencia V1:

```text
PostgreSQL
Flyway
Spring JDBC / JdbcClient
```

No JPA/Hibernate salvo cambio contractual explícito posterior.

Reglas:

- entidades de dominio no son rows/DTOs JDBC;
- SQL vive en `infrastructure.persistence`;
- mapping row ↔ domain se realiza en Infrastructure;
- transacciones se orquestan en el límite Application/Infrastructure sin introducir dependencias de Spring dentro del Domain;
- constraints de DB complementan, no sustituyen, invariantes de dominio;
- locks y revalidación de capacidad pertenecen al adapter transaccional, expuestos mediante ports adecuados.

---

## 6. Mensajería

RabbitMQ pertenece exclusivamente a Infrastructure.

Flujo de Command:

```text
Rabbit listener
→ validar envelope/wire contract
→ mapear a input model
→ application input port
→ transacción local
→ mutation + Inbox/idempotency + Outbox
→ commit
→ ACK
```

El listener no decide:

- reglas de estado;
- autorización funcional;
- capacidad;
- reasignación;
- limpieza;
- errores funcionales del dominio.

El Outbox Relay tampoco contiene reglas funcionales; publica mensajes ya persistidos y administra delivery técnico.

Queries siguen la misma separación:

```text
Rabbit query listener
→ wire validation
→ application query port
→ authoritative read
→ Result Outbox
```

---

## 7. Authorization

La autorización funcional se evalúa desde Application/Domain usando puertos y proyecciones locales.

Infrastructure se limita a:

- consumir/proyectar Events;
- persistir/reconstruir `authorization_role_projection` y `authorization_access_projection`;
- implementar recovery y adapters.

No se permite que un Rabbit listener o controller decida permisos directamente.

---

## 8. Scheduling

Schedulers son adapters de entrada.

Ejemplo:

```text
CleaningPeriodScheduler
→ CompleteExpiredCleaningPeriods input port
```

El scheduler decide **cuándo invocar**, no **qué significa completar una limpieza**.

La regla de `completedAt = expectedEndsAt`, idempotencia y transición pertenece al caso de uso/dominio.

---

## 9. Contracts wire

`contracts` existe para aislar el contrato de transporte del modelo interno.

Obligatorio:

```text
Command DTO ≠ domain command/entity
Event DTO ≠ domain event/entity
Query DTO ≠ repository model
Result DTO ≠ application result interno
```

Puede haber mapping explícito uno-a-uno, pero no se reutiliza el DTO wire como entidad de negocio para ahorrar clases.

Validaciones como UUID, campos required, enums wire, formato timestamp y `messageVersion` ocurren antes de entrar a Application.

Reglas funcionales como estado permitido, actor autorizado, capacidad y concurrencia ocurren después del mapping, dentro de Application/Domain.

---

## 10. Prohibiciones explícitas

PR1–PR4 no pueden introducir:

```text
@RestController con lógica de negocio
@RabbitListener con lógica de dominio
JdbcClient dentro de domain/application
RabbitTemplate dentro de domain/application
ObjectMapper/Jackson dentro de domain
DTO wire usado como Aggregate/Entity
SQL dentro de use cases
repositorio concreto importado por domain
acceso directo a DB de otro servicio
HTTP/gRPC de negocio como bypass de RabbitMQ
God Service que concentre listener + SQL + dominio + publicación
```

Los endpoints Actuator/operacionales no violan esta regla.

---

## 11. Atomicidad y límites

Application define la unidad funcional que debe ser atómica; Infrastructure proporciona el mecanismo transaccional.

Ejemplo `DecideWashEntry(AUTHORIZED)`:

```text
Application decide:
EntryValidation + asignación + transición + intents de mensajes

Infrastructure garantiza:
BEGIN
locks/revalidación
persistencias
Outbox
COMMIT
```

La lógica no debe quedar partida entre listener, repository y publisher de forma que una transacción local deje estados parciales.

---

## 12. ArchUnit obligatorio

PR1 debe introducir tests ArchUnit que fallen si se rompe al menos lo siguiente:

```text
domain no depende de application/infrastructure/contracts/Spring/JDBC/AMQP/Jackson
application no depende de infrastructure ni adapters concretos
contracts no depende de infrastructure y no contiene dependencias de persistence/messaging adapters
infrastructure puede depender de application/domain/contracts
```

Además, deben existir checks equivalentes para impedir:

```text
@RabbitListener fuera de infrastructure
repositorios JDBC fuera de infrastructure.persistence
configuración Spring fuera de infrastructure/config o bootstrap técnico permitido
```

Si una excepción técnica real resulta necesaria, debe documentarse antes de debilitar el test; no se elimina ArchUnit para hacer pasar la build.

---

## 13. Testing por capa

```text
Domain
→ unit tests puros, sin Spring

Application
→ use-case tests con ports fake/mock
→ sin RabbitMQ real obligatorio

Persistence Infrastructure
→ PostgreSQL Testcontainers
→ Flyway real
→ concurrencia/locking real

Messaging Infrastructure
→ RabbitMQ Testcontainers en PR3
→ contract/wire validation
→ retry/DLQ/ACK/Outbox relay

Architecture
→ ArchUnit en cada clean verify
```

---

## 14. Gate de PR1

PR1 no está completo únicamente porque compile.

Definition of Done arquitectónica adicional:

- estructura hexagonal materializada;
- Domain framework-free;
- Application broker/database-adapter-free;
- ports de entrada/salida explícitos;
- repositories JDBC detrás de output ports;
- Flyway y modelos de persistencia aislados en Infrastructure;
- ArchUnit bloqueando dependencias inválidas;
- tests de dominio no necesitan Spring context;
- `./mvnw clean verify` verde.

---

## 15. Relación con otros documentos

Orden de lectura obligatorio para implementar:

```text
pr0_contract_resolution.md
→ system_contract.md
→ hexagonal_architecture_contract.md
→ execution_plan.md
```

`system_contract.md` define **qué** debe hacer el servicio.

Este documento fija **cómo deben mantenerse las fronteras arquitectónicas** mientras se implementa.

`execution_plan.md` define **en qué orden** se entrega PR1–PR4.

Ningún PR de implementación puede reinterpretar estas fronteras solo por conveniencia técnica.
