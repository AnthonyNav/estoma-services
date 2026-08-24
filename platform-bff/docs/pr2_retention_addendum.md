# Platform BFF — PR2 Retention Addendum

**Estado:** BLOCKER complementario de `pr2_correction_gate.md`
**Aplica a:** `platform-bff/feat/pr2-durable-command-gateway`

## Motivo

La política V1 de retención del BFF define:

```text
client_operations terminales → 7 días
client_operations PENDING     → no purgar por TTL
idempotency_records           → 24 h después del terminal
```

Por tanto, el TTL de un registro de idempotencia **no empieza en el momento de submit**. Mientras la operación asociada siga `PENDING`, todavía no existe el instante desde el cual calcular `terminalAt + 24h`.

PR1 permite ahora:

```text
idempotency_records.expires_at = NULL
```

para representar correctamente ese estado.

## Corrección obligatoria en PR2

La rama actual crea el registro con una expiración equivalente a:

```text
submittedAt + 24h
```

Eso debe eliminarse.

Al crear una nueva operación durable:

```text
client_operation.status = PENDING
idempotency_record.expires_at = NULL
```

Cuando un Result owner válido lleve la operación por primera vez a un estado terminal:

```text
SUCCEEDED | REJECTED | FAILED | EXPIRED
```

la misma transacción local que confirma el terminal debe fijar:

```text
idempotency_record.expires_at = terminalAt + 24h
```

La transición debe ser idempotente: un Result duplicado o tardío no puede extender otra vez el TTL ni modificar la primera terminalidad observada.

## Consecuencias para repositorio/application

PR2 debe añadir una operación de persistencia equivalente a:

```text
setExpiryForOperationIfUnset(operationId, terminalAt + 24h)
```

con fencing suficiente para que:

- sólo afecte al registro ligado a la operación terminal;
- no cambie un `expires_at` ya fijado;
- participe en la misma transacción que Inbox + `client_operations` terminal;
- no convierta retención en deadline funcional del Command.

Un cleanup posterior sólo puede eliminar registros cuya `expires_at` no sea `NULL` y ya haya vencido.

## Tests obligatorios en PR2

```text
submit nuevo
→ idempotency.expiresAt = null

Result terminal válido
→ operation terminal + idempotency.expiresAt = terminalAt + 24h en la misma transacción

Result duplicado
→ expiresAt no cambia

operación PENDING por más de 24h
→ idempotency record sigue existiendo
→ no se crea un segundo Command con la misma scope key
```

## Gate

```text
submittedAt + 24h como expiry de idempotency = FORBIDDEN
PENDING con expiresAt NULL                     = REQUIRED
terminalAt + 24h                               = REQUIRED
TTL update atomic with terminal Result         = REQUIRED
```
