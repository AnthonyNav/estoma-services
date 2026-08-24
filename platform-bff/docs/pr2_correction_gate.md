# Platform BFF — PR2 Correction Gate

**Ruta:** `platform-bff/docs/pr2_correction_gate.md`
**Estado:** correcciones obligatorias antes de revisar/mergear PR2
**Aplica a:** `platform-bff/feat/pr2-durable-command-gateway`

## 1. Propósito

Este documento registra los hallazgos detectados al revisar la rama apilada de PR2 contra el PR1 endurecido y contra los contratos canónicos del BFF.

No redefine negocio ni contratos owner. Su objetivo es hacer que la corrección de PR2 sea mecánica y verificable después de integrar PR1.

PR2 no debe mergearse ni considerarse listo mientras cualquiera de los gates `BLOCKER` siguientes siga abierto.

---

## 2. Orden obligatorio de corrección

```text
1. mergear PR1
2. rebasar/reconstruir PR2 sobre el merge commit real de PR1
3. resolver overlaps preservando el hardening de PR1
4. aplicar todos los BLOCKER de este documento
5. ejecutar ./mvnw -B clean verify
6. ejecutar PostgreSQL + RabbitMQ Testcontainers
7. revisar diff PR1..PR2 para confirmar que PR2 sólo agrega Command Gateway/Operations
```

No corregir PR2 haciendo merge ciego de la rama vieja de PR1: la rama actual de PR2 fue creada sobre un snapshot anterior y ya diverge del PR1 revisado.

---

# 3. BLOCKER — preservar el Outbox endurecido de PR1

La versión actualmente apilada de PR2 vuelve a aceptar:

```text
status IN ('PENDING', 'FAILED')
AND next_attempt_at IS NULL OR next_attempt_at <= now
```

Eso reabre el bug ya corregido en PR1: un `FAILED` sin `next_attempt_at` representa quarantine/atención operativa y no debe ser reclamado automáticamente.

Después del rebase debe mantenerse:

```text
PENDING
→ reclamable cuando no tiene next_attempt_at o ya venció

FAILED + next_attempt_at != NULL
→ reclamable únicamente cuando venció

FAILED + next_attempt_at = NULL
→ quarantine; no reclaim automático
```

También deben conservarse:

- claim token/fencing;
- `FOR UPDATE SKIP LOCKED`;
- limpieza de claim después de publish/failure;
- `next_attempt_at` y `last_error_code` limpios al pasar a `PUBLISHED`;
- tests concurrentes de claim y retry/quarantine.

---

# 4. BLOCKER — no perder `aggregateId` / `aggregateVersion`

El envelope durable del BFF admite:

```text
aggregateId?
aggregateVersion?
```

PR1 ya persiste estos campos en `outbox_messages` para poder reconstruir determinísticamente el Command.

La rama vieja de PR2 crea el envelope con esos valores, pero el publisher lo reconstruye con:

```text
aggregateId = null
aggregateVersion = null
```

Eso rompe Commands con identidad/optimistic concurrency en el envelope.

PR2 debe:

1. persistir los valores recibidos por `CommandSubmission` en el `OutboxMessage` de PR1;
2. reconstruir exactamente esos valores en `DefaultConfirmedCommandPublisher`;
3. probar round-trip:

```text
submit aggregateId/version
→ DB Outbox
→ claim
→ serialized CommandEnvelope
→ mismos aggregateId/version
```

Nunca derivarlos heurísticamente desde `payload`.

---

# 5. BLOCKER — fingerprint funcional realmente normalizado

El contrato requiere `requestFingerprint` de campos funcionales normalizados.

La implementación actual concatena:

```text
publicOperation + messageType + JsonNode.toString()
```

Eso no garantiza equivalencia para JSON semánticamente idéntico con distinto orden de propiedades.

Ejemplo que debe producir el mismo fingerprint:

```json
{"a":1,"b":2}
```

```json
{"b":2,"a":1}
```

Corrección requerida:

- canonicalizar recursivamente objetos JSON por nombre de propiedad, preservando orden de arrays;
- o construir el fingerprint desde un modelo funcional normalizado antes de serializar;
- incluir `publicOperation` y `messageType` en el material;
- no incluir metadata técnica cambiante (`messageId`, timestamps, correlationId).

Tests mínimos:

```text
same semantic request + reordered object keys → same fingerprint
changed functional value → different fingerprint
array order changed → different fingerprint cuando el array sea semánticamente ordenado
```

---

# 6. BLOCKER — Application no debe depender de Jackson/JDBC/AMQP

PR1 protege ahora Application contra dependencias directas de:

```text
Jackson
tools.jackson
Spring JDBC
Spring AMQP
```

La rama actual de PR2 viola esta frontera mediante `RequestFingerprint` y uso directo de `JsonNode` desde Application.

Corrección requerida:

```text
HTTP/wire JSON
→ adapter/contract boundary
→ representación neutral de Application
```

Opciones válidas:

- puerto de canonicalización/serialization implementado en Infrastructure;
- value object/estructura neutral ya normalizada;
- bytes/string canónico producido antes de entrar al use case.

Application puede usar Spring para wiring/transacción si se mantiene la convención local, pero no debe conocer el codec JSON, JDBC o AMQP.

---

# 7. BLOCKER — Controller no depende de Infrastructure

`OperationsController` actualmente usa directamente:

```text
MessageEnvelopeCodec
```

que vive en `infrastructure.messaging`.

Eso crea:

```text
API → Infrastructure
```

cuando la frontera esperada es:

```text
API → Application / HTTP contract
Application → ports
Infrastructure → implements ports
```

Corrección requerida:

- mover el mapping/decoding de `resultData` a un application query/view mapper mediante puerto;
- o almacenar/retornar una representación de respuesta que no obligue al controller a usar el codec wire.

El controller no debe importar clases de `infrastructure..`.

---

# 8. BLOCKER — Result trust boundary incompleto

Antes de mutar `client_operations`, PR2 debe validar como mínimo:

```text
messageKind = RESULT
producer esperado
messageType esperado para la operación
messageVersion soportada para ese Result
operationId esperado
correlationId esperado
payload.status válido
```

La rama actual valida producer/correlation/operation, pero:

- no ata `messageType` al Command original;
- usa `messageVersion == 1` como regla genérica en vez de registry por contrato;
- los tests usan nombres de Result inventados/locales sin demostrar que coincidan con el owner wire vigente.

Corrección requerida:

```text
Command registry/owner contract
→ expected Result messageType(s) + version(s) + producer
```

No asumir globalmente `messageType = <Command>Succeeded` ni otra convención sin verificar cada owner implementado en `develop`.

Para el endpoint/Command piloto de PR2, confirmar el wire real del owner antes de habilitarlo productivamente.

---

# 9. BLOCKER — conservar causalidad del Command publicado

Un Result owner debe conservar el contexto del Command que causó el procesamiento. PR2 debe poder validar o al menos correlacionar de forma confiable:

```text
Result.causationId
↔ Command.messageId
```

PR1 ya conserva `message_id` en Outbox. PR2 no debe perder esa relación durante dispatch.

Solución recomendada:

- exponer por puerto el `messageId` del Command asociado a la operación;
- validar `causationId` cuando el owner contract lo exige;
- nunca aceptar un Result únicamente porque conoce un `operationId` válido.

Si un owner legacy aún no cumple causalidad, documentar explícitamente el gate por owner en vez de relajar globalmente el trust boundary.

---

# 10. BLOCKER — dispatcher de Results debe separar Command vs recovery

`results.bff` compartirá:

```text
Command Results del cliente
Recovery Query Results
```

PR2 puede implementar todavía sólo el branch funcional de Command Result, pero debe dejar un dispatcher que clasifique antes de mutar estado.

Diseño mínimo:

```text
results.bff listener
→ structural validation
→ ResultDispatcher
   ├── allowlisted Command Result → command operation handler
   ├── allowlisted recovery Result → recovery handler/gate posterior
   └── unknown/untrusted → quarantine/reject controlado
```

No es válido:

```text
all Results → ResultApplicationService de client_operations
```

ni ACK silencioso de un recovery Result sólo porque aún no existe PR4.

Antes de PR4, un Result de recovery no esperado debe aislarse operacionalmente; no convertirse en un efecto de Command ni perderse silenciosamente.

---

# 11. BLOCKER — no ACK silencioso de mensajes inválidos

La rama actual de `ResultListener` captura `RuntimeException`, escribe un warning y retorna normalmente con `AcknowledgeMode.AUTO`.

Resultado:

```text
mensaje malformed/untrusted
→ catch
→ método retorna
→ ACK
→ mensaje perdido
```

Corrección requerida:

Definir explícitamente:

```text
válido + aplicado/duplicado
→ COMMIT Inbox + efecto
→ ACK

inválido/no confiable permanente
→ quarantine/DLQ o registro durable equivalente
→ ACK sólo después de aislarlo

fallo técnico transitorio antes de commit
→ NACK/retry según política
```

No loggear payloads sensibles ni envelopes completos.

La Inbox y la transición de `client_operations` permanecen en la misma transacción local.

---

# 12. BLOCKER — carrera de Idempotency-Key debe conservar 409

La estrategia `ON CONFLICT DO NOTHING` es válida para arbitrar concurrencia, pero el branch perdedor actual puede terminar en `IllegalStateException` cuando el ganador tiene fingerprint distinto.

Debe conservar la semántica pública:

```text
misma scope key + mismo fingerprint
→ reutilizar operationId ganador

misma scope key + distinto fingerprint
→ IdempotencyConflict
→ HTTP 409
```

Esto debe cumplirse tanto secuencial como concurrentemente.

Tests PostgreSQL obligatorios:

```text
2 requests simultáneas, misma key + mismo fingerprint
→ 1 operation + 1 outbox + mismo operationId para ambas

2 requests simultáneas, misma key + fingerprint distinto
→ 1 ganador
→ perdedor recibe conflicto determinista
→ nunca 500/IllegalStateException
```

---

# 13. BLOCKER — no fijar expiración local arbitraria a PENDING

El contrato permite estado terminal `EXPIRED`, pero ese estado proviene del Result owner correspondiente; además, una `client_operation PENDING` no se purga por TTL genérico.

PR2 actualmente crea nuevas operaciones con `expiresAt = now + 7 días`.

Antes de conservar ese valor debe existir semántica explícita que explique qué representa y quién lo aplica. No debe existir un worker que transforme unilateralmente una operación owner todavía pendiente en `EXPIRED` por ese timestamp.

Corrección inicial recomendada para PR2:

```text
expiresAt = null
```

salvo que un owner/contrato específico publique una expiración funcional explícita.

La retención de 7 días aplica a operaciones **terminales**, no es deadline funcional del Command.

---

# 14. SHOULD — `CommandRegistry` no debe ser un Spring component dentro de contracts

La rama actual coloca `CommandRegistry` bajo `contracts.messaging` y lo anota con `@Component`.

Preferencia para mantener límites claros:

```text
contracts.messaging
→ DTOs/envelopes/value contracts puros

application/config
→ allowlist/registry de rutas owner
```

El registry debe seguir siendo estático/allowlisted y nunca aceptar `targetService` del request cliente.

---

# 15. SHOULD — topología local no debe apropiarse de infra compartida

PR2 puede declarar topología para dev/test cuando sea útil, pero producción sigue siendo responsabilidad de `estoma-infra`.

Verificar que:

- sólo se declare `results.bff` como cola propiedad del BFF;
- no se creen `commands.<owner>` de otros servicios;
- exchange declarations sean compatibles con la topología canónica;
- `platform.messaging.topology-declare` pueda deshabilitarse en ambientes administrados;
- no se introduzcan aliases `*-service`.

---

# 16. Tests mínimos para cerrar PR2

Además de los tests existentes, PR2 corregido debe incluir como mínimo:

```text
aggregateId/version survive Outbox → publisher envelope
publisher confirm ACK → PUBLISHED
publisher NACK/timeout/unroutable → retry técnico y operation sigue PENDING
quarantine FAILED sin nextAttemptAt no se reclama
fingerprint estable ante reorden de JSON
idempotency race same fingerprint
idempotency race different fingerprint
Result producer spoofing rejected
Result wrong messageType rejected
Result wrong version rejected
Result wrong correlation rejected
Result wrong operation rejected
Result duplicate does not reapply
unknown/recovery Result does not mutate client_operations
malformed Result is isolated, not silently ACK-dropped
controller has no infrastructure dependency
Application has no Jackson/JDBC/AMQP dependency
GET /api/v1/operations/{id} respeta actor scope
```

Gate final:

```text
./mvnw -B clean verify
PostgreSQL Testcontainers = green
RabbitMQ Testcontainers = green
ArchUnit = green
PR2 branch behind PR1/develop = 0
```

---

## 17. Estado de salida esperado

Cuando se completen estas correcciones:

```text
PR1 foundation invariants preserved          = YES
Durable Command submission atomic            = YES
Outbox envelope reconstruction lossless       = YES
Idempotency sequential/concurrent             = YES
Result trust boundary                         = YES
Command vs recovery Result separation         = YES
Invalid Result isolation                      = YES
Operations polling layer boundary             = YES
Owner wire assumptions verified               = YES
PR2 ready for merge review                    = YES
```

Hasta entonces:

```text
PR2 = NEEDS_REBASE_AND_HARDENING
```
