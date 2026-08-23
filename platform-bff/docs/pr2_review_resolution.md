# Platform BFF — PR2 Review Resolution

**Estado:** PR2 reconstruido y corregido para merge review  
**Rama:** `platform-bff/feat/pr2-durable-command-gateway`

## Resoluciones aplicadas

- PR2 se reconstruye sobre el `develop` que ya contiene PR1; no reutiliza adapters de persistencia obsoletos.
- Se preserva el Inbox transaccional de PR1 (`ON CONFLICT DO NOTHING`), sin capturar violaciones de PK dentro de la transacción.
- `idempotency_records.expires_at` nace `NULL` y se fija únicamente al terminalizar la operación, usando `terminalAt + platform.retention.idempotency-hours` con mínimo V1 de 24 horas.
- La carrera de `Idempotency-Key` arbitra tanto `scope_key` como la clave lógica `(actorAccountId?, publicOperation, idempotencyKey)` y el perdedor reutiliza al ganador o devuelve conflicto funcional; nunca debe convertirse en 500 por una violación concurrente esperada.
- El fingerprint canonicaliza JSON y también protege `aggregateId`, `aggregateVersion` y el contexto funcional del actor; metadata técnica cambiante no participa.
- `ActorContext` usa el wire canónico `accountId` y conserva `type/scope/service/systemId` conforme al contrato owner. `RequestPasswordRecovery` es la única operación durable BFF anónima y usa `ANONYMOUS/PUBLIC`.
- El registry deja de inferir Results por convención: cada Command declara explícitamente owner, routing, `messageVersion` y Result `messageType/version` aceptado.
- El Result trust boundary valida tipo/version/producer/operation/correlation y `causationId == Command.messageId` antes de mutar estado.
- Inbox + terminalización de `client_operations` + inicio de retención de idempotencia ocurren dentro de una sola transacción local.
- Un terminal tardío idéntico es idempotente. Un terminal contradictorio no cambia la operación y se trata como mensaje no confiable para aislamiento.
- `results.bff` usa ACK manual: sólo ACK después de aplicación o quarantine durable; fallos técnicos previos al aislamiento hacen NACK/requeue.
- Results de recovery de Authorization quedan clasificados por separado y se aíslan explícitamente hasta PR4; nunca actualizan `client_operations` ni se pierden silenciosamente.
- El Outbox Relay reclama un mensaje por vez, conserva fencing/lease de PR1, exige publisher confirm/routability y aplica retry acotado; al agotar intentos queda `FAILED + next_attempt_at = NULL` como quarantine operativo.
- Se mantienen `aggregateId/aggregateVersion` durante DB -> claim -> envelope publicado.

## Cobertura agregada

- contratos Result explícitos por owner;
- wire canónico de `ActorContext`;
- fingerprint estable ante orden de propiedades y sensible a aggregate/actor scope;
- carrera PostgreSQL misma key + mismo fingerprint;
- carrera PostgreSQL misma key + fingerprint distinto;
- `expiresAt NULL` mientras PENDING y `terminalAt + 24h` al terminalizar;
- Result producer/type/causation fencing y terminal contradictorio;
- publisher confirm + unroutable + aggregate metadata;
- ACK/quarantine/NACK del listener;
- retry exhaustion del Outbox.

## Gates que permanecen fuera de PR2

- `CancelAppointmentTimeSlot`: el owner Appointments reconoce la capacidad BFF, pero la ruta HTTP pública todavía no está cerrada en el contrato canónico del BFF. PR2 no inventa esa exposición.
- Appointment availability sigue `GATED_BY_APPOINTMENTS_OWNER_CONTRACT`.
- JWT/Security RPC enforcement funcional pertenece a PR3.
- Public owner adapters/endpoints funcionales pertenecen a PR5 y sólo se habilitan cuando cada owner esté listo.

## Gate final

PR2 sólo puede considerarse listo para merge cuando:

```text
develop...PR2 = ahead 1 / behind 0
./mvnw -B clean verify = green cuando GitHub Actions pueda ejecutar runner
PostgreSQL Testcontainers = green
RabbitMQ Testcontainers = green
ArchUnit = green
```

Si Actions vuelve a terminar antes de ejecutar steps, ese estado se documenta como gate externo; no se declara falsamente CI verde.
