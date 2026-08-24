# Wash Execution Service — PR0 Contract Resolution

**Ruta:** `wash-execution-service/docs/pr0_contract_resolution.md`
**Estado:** RESOLVED_FOR_PR1
**Canonical baseline:** `AnthonyNav/estomatologia-platform-docs` branch `docs/wash-execution-pr0-contract-hardening`

## 1. Propósito

Este documento incorpora al gate local de implementación las resoluciones previas a PR1 de `wash-execution-service`.

No redefine negocio. Si `system_contract.md` o `execution_plan.md` contienen un nombre/forma histórica incompatible con esta resolución, esta resolución prevalece hasta que esos documentos sean consolidados.

## 2. Authorization projections

Nombres físicos V1 obligatorios:

```text
authorization_role_projection
authorization_access_projection
```

No crear:

```text
role_projection
system_access_projection
```

como tablas alternativas.

Rol y acceso conservan versiones independientes:

```text
role_source_version
access_source_version
```

## 3. Inbox durable

`processed_messages` debe poder representar:

```text
status = DEFERRED | APPLIED
applied_at
deferred_reason
claim_token
lease_until
```

más envelope mínimo suficiente para reprocesar de forma durable el mismo mensaje.

Invariantes:

```text
DEFERRED → deferred_reason != null && applied_at == null
APPLIED  → deferred_reason == null && applied_at != null
```

Cuando exista replay concurrente de `DEFERRED`, el claim debe ser atómico y fenced.

La redelivery del mismo `messageId` mientras está `DEFERRED` no crea una segunda fila ni un segundo efecto.

## 4. Queries propiedad de Execution

PR3 debe declarar:

```text
queries.wash-execution
```

con requester V1:

```text
appointments
```

Queries canónicas:

```text
GetWashExecutionIntegrationContext
ListWashExecutionIntegrationContextsPage
```

Su propósito exclusivo es recovery/reconciliación autoritativa de la proyección que Appointments mantiene de Execution.

No convertir automáticamente estas lecturas lógicas históricas en Queries RabbitMQ:

```text
GetWashExecution
GetPendingReassignments
GetResourceOccupancy
GetOperationalResourceState
```

Las lecturas normales de UI siguen perteneciendo a read models del BFF.

## 5. Snapshot mínimo hacia Appointments

Recovery debe reconstruir:

```text
WashExecution
→ appointmentId
→ washExecutionId
→ appointmentType
→ status
→ arrivedAt
→ startedAt?
→ entryAuthorizedAt?
→ completedAt?
→ cancelledAt?
→ cancellationType?
→ version

ResourceUnavailability[]
→ target cabin/tank
→ disabledAt
→ restoredAt?
→ version

CleaningPeriod[]
→ assignment/cabin
→ startsAt
→ expectedEndsAt
→ completedAt?
→ version
```

`entryAuthorizedAt` debe seguir reconstruible aunque `WashEntryAuthorized` y `WashExecutionStarted` hayan sido emitidos con el mismo `executionVersion`.

## 6. Same-version Events

Regla obligatoria:

```text
same executionVersion != duplicate Event
```

Dedupe exacto:

```text
messageId/sourceEventId
```

No usar una sola condición global `incomingVersion <= projectedVersion => discard` para tipos de Event distintos cuando eso pueda perder evidencia operacional.

## 7. Impacto por PR

### PR1

Debe implementar desde el inicio:

- nombres físicos canónicos de Authorization;
- schema Inbox `DEFERRED/APPLIED`;
- constraints y soporte de replay/fencing necesario para mensajes diferidos.

### PR2

No cambia el catálogo de Commands ni la state machine definida en `system_contract.md`.

### PR3

Debe incluir:

- `queries.wash-execution`;
- wire contracts de las dos recovery Queries;
- Results correlacionados hacia `results.appointments`;
- recovery ENTITY/FULL por fecha;
- same-version Event semantics.

### PR4

Debe probar que Appointments puede reconstruir evidencia/estado de Execution sin RabbitMQ como Event Store y que `MISSED` permanece fail-closed mientras la evidencia no sea confiable.

## 8. Gate final PR0

Con estas resoluciones:

```text
PR1 = UNBLOCKED
PR2 = UNBLOCKED
PR3 = CONTRACTUALLY DEFINED
PR4 = CONTRACTUALLY DEFINED
```

No quedan decisiones BLOCKER/HIGH conocidas dentro de Wash Execution que impidan comenzar PR1.
