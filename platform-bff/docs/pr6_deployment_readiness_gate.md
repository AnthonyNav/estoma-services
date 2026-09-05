# Platform BFF — PR6 Deployment Readiness Gate

**Ruta:** `platform-bff/docs/pr6_deployment_readiness_gate.md`  
**Estado:** gate documental para el último PR de implementación del BFF V1  
**Objetivo:** después del PR descrito aquí, `platform-bff` no debe tener trabajo funcional pendiente dentro de `estoma-services`; lo restante debe ser exclusivamente despliegue/integración en `estoma-infra`.

---

## 1. Alcance de cierre

Este gate cierra el BFF para el alcance actualmente priorizado:

```text
servicios compartidos
+ Lavado Ultrasónico
```

`Prácticas/Jornadas` no forma parte de este gate. Cualquier PR de Jornadas que exista en paralelo debe mantenerse aislado y reconciliarse por separado; no debe ampliar silenciosamente el alcance del PR6.

La fuente canónica continúa siendo `AnthonyNav/estomatologia-platform-docs`. Este documento no autoriza al BFF a inventar reglas o contratos owner.

---

## 2. Baseline que ya se considera cerrado

PR1–PR5 ya dejaron implementado en `develop`, entre otros:

- PostgreSQL + Flyway + JdbcClient;
- arquitectura hexagonal protegida con ArchUnit;
- `client_operations`, idempotencia, Transactional Outbox y Consumer Inbox;
- RabbitMQ Command/Result/Event runtime;
- Security RPC hacia Authentication;
- JWT local, revocación y refresh single-flight;
- recovery owner-driven de Authorization;
- read models tipados de Shared/Lavado;
- Authentication HTTP edge;
- Mailbox HTTP API;
- escrituras de Appointments/Wash Execution;
- composición de `pending-reassignments`;
- `202 Accepted` canónico;
- `application/problem+json` canónico;
- fencing owner-native de Communications y Wash Execution.

No se reimplementan estas piezas en PR6 salvo para corregir una regresión demostrada por las pruebas finales.

---

## 3. Gaps que PR6 debe cerrar

El siguiente PR de implementación será **un único PR6 de deployment readiness** y debe cerrar todos los puntos siguientes.

### 3.1 Projection bootstrap/readiness no-Authorization

Hoy `V3__pr4_projection_read_models.sql` crea:

```text
person
revocation
authorization
academic
communications
wash-administration
appointments
wash-execution
```

como `BOOTSTRAPPING`.

Authorization sí tiene recovery explícito; el resto no tiene un FULL snapshot contractual para BFF. No se permite solucionar esto marcando `READY` al recibir un Event cualquiera ni reutilizando Queries owner diseñadas para otro consumidor.

La semántica V1 queda cerrada así:

#### Genesis baseline

Para las proyecciones sin recovery owner, el primer despliegue puede establecer una **genesis baseline explícita** porque la documentación canónica ya define que:

- `bff-db` existe desde el despliegue inicial;
- RabbitMQ no es Event Store;
- después del primer despliegue la continuidad depende de backup/PITR de `bff-db` + Events incrementales;
- un FULL rebuild futuro exige primero un Query owner contractual.

PR6 debe implementar un mecanismo one-shot y durable equivalente a:

```text
explicit genesis mode
+ durable genesis marker
+ safety validation
→ non-owner-recoverable projections READY desde baseline cero
```

Requisitos obligatorios:

1. el modo genesis está desactivado por defecto;
2. requiere opt-in explícito de configuración de despliegue;
3. solo puede ejecutarse si no existe marker previo;
4. solo puede ejecutarse con las proyecciones objetivo todavía en `BOOTSTRAPPING` generación inicial;
5. debe fallar startup si detecta estado incompatible que haría inseguro asumir baseline cero;
6. la transición a `READY` y el marker se persisten transaccionalmente;
7. no se vuelve a ejecutar en restart normal;
8. una pérdida posterior de `bff-db` **no** habilita auto-bootstrap: exige restore/PITR;
9. Authorization continúa usando su recovery owner explícito;
10. no se crean snapshot Queries nuevas desde el BFF.

La preparación de DB/colas y el orden “BFF listo antes de tráfico owner” será responsabilidad de `estoma-infra` después de PR6.

### 3.2 Lecturas de Alumno de Lavado que faltan

La interfaz canónica V1 incluye y el `WashController` actual todavía no expone:

```text
GET /api/v1/wash/student/home
GET /api/v1/wash/appointments/form-context
```

PR6 debe implementarlas exclusivamente con read models locales.

#### `GET /api/v1/wash/student/home`

Debe:

- exigir actor `ALUMNO` con acceso activo a `LAVADO_ULTRASONICO`;
- usar proyecciones `person + academic + appointments + wash-execution` necesarias;
- devolver indisponibilidad controlada si una proyección requerida no es confiable;
- seleccionar como máximo la cita primaria según el contrato canónico;
- componer `qrUsageContext`; no persistir una segunda identidad QR;
- no llamar owners por HTTP/gRPC/Query por request de pantalla.

#### `GET /api/v1/wash/appointments/form-context`

Debe:

- derivar el alumno desde el JWT;
- componer nombre, matrícula y semestre;
- devolver únicamente Course Sections activas/usables del periodo activo e inscripciones vigentes;
- no aceptar NRC arbitrario desde el cliente;
- fallar cerrado cuando el contexto académico no sea confiable.

Los DTO exactos deben seguir `estomatologia-platform-docs/03-services/edge/bff/interfaces.md` y los casos `WASH-STU-01` / `WASH-STU-02`.

### 3.3 Superficie `/api/v1/admin/**`

PR6 debe cerrar la superficie administrativa para **Commands BFF ya implementados y owner-ready**.

Debe existir un inventario explícito HTTP → intención owner para las capacidades aplicables de:

```text
Users
Authentication
Authorization
Academic
Communications
Wash Administration
```

Reglas:

- un endpoint de escritura = exactamente una intención owner;
- no exponer `/commands` genérico;
- no convertir alta de personal en saga escondida;
- workflows multi-owner siguen siendo pasos independientes observables por `operationId`;
- DTO HTTP orientado a cliente y DTO wire owner permanecen separados;
- `Idempotency-Key` obligatorio para Commands durables;
- actor/rol/acceso se validan localmente y el owner revalida funcionalmente;
- no exponer Commands owner internos;
- operaciones que produzcan secretos mediante Security RPC no pasan por Outbox ni persisten el secreto.

El PR debe incluir una tabla/matriz de rutas final y pruebas que demuestren `ruta → publicOperation → messageType → owner`.

### 3.4 OpenAPI completo

PR6 debe agregar una representación OpenAPI V1 machine-readable de toda la superficie realmente expuesta.

Debe cubrir como mínimo:

```text
auth
me
operations
mailbox
admin
wash
health público documentado cuando aplique
```

Requisitos:

- requests/responses reales, no DTOs conceptuales obsoletos;
- `202` con `operationId/status/pollPath/submittedAt`;
- `application/problem+json` con `type/title/status/code/detail/traceId`;
- auth/JWT correctamente declarado;
- `Idempotency-Key` donde corresponde;
- ningún endpoint inexistente debe aparecer como funcional;
- ningún endpoint funcional puede quedar fuera del OpenAPI;
- prueba automática de drift entre el inventario HTTP soportado y el spec.

`GET /api/v1/wash/appointments/availability` forma parte de la superficie ejecutable mediante el
contrato owner V1 y debe permanecer sincronizado con OpenAPI.

### 3.5 Hardening operativo y E2E

PR6 debe ser el gate ejecutable final, no solamente más unit tests.

Debe probar con PostgreSQL y RabbitMQ reales mediante Testcontainers:

```text
HTTP
→ BFF
→ DB local / Outbox
→ RabbitMQ
→ owner fixture o owner real compatible
→ Result/Event
→ BFF Inbox / projection
→ polling/read API
```

Cobertura mínima obligatoria:

1. login + JWT local;
2. refresh concurrente/single-flight;
3. password recovery sin persistir secretos;
4. Command durable + Result + polling;
5. idempotencia HTTP concurrente;
6. Mailbox read + visit;
7. genesis baseline segura;
8. restart conservando projection readiness;
9. rechazo de genesis sobre estado incompatible;
10. Authorization recovery y fail-closed;
11. `wash/student/home`;
12. `wash/appointments/form-context`;
13. Schedule/Cancel Appointment wire;
14. flujo de supervisión de Wash Execution;
15. pending reassignment composition;
16. Outbox retry/quarantine;
17. malformed/untrusted Result/Event → aislamiento;
18. no secretos en logs capturados por tests;
19. OpenAPI sin drift;
20. Docker image build/start smoke.

Availability debe incorporarse al E2E coordinado con el owner: miss/stale `503 + Retry-After`,
Query/Result correlacionados y posterior hit fresco `200`.

---

## 4. Availability — decisión de cierre

```text
GET /api/v1/wash/appointments/availability
```

dejó de estar bloqueado porque Appointments publicó:

```text
GetAppointmentAvailability v1
GetAppointmentAvailabilityResult v1
```

Motivo:

- Appointments es owner del matching/disponibilidad;
- el BFF no puede copiar `AppointmentCapacityMatcher`;
- el BFF no puede aproximar `capacity - demand`;
- el BFF consume exclusivamente el Query owner publicado;
- Queries de recovery existentes no se reutilizan como API de disponibilidad.

El adapter incorporado es cache-aside asíncrono y no duplica el matching. La evidencia detallada está
en [`appointment_availability_gate_resolution.md`](appointment_availability_gate_resolution.md).

---

## 5. Cambios permitidos en PR6

Scope principal:

```text
platform-bff/**
```

Se permiten cambios mínimos en otro servicio únicamente cuando:

- restauran un contrato owner **ya canónico**;
- son imprescindibles para ejecutar el E2E del contrato existente;
- no agregan una decisión de dominio nueva.

Una decisión owner nueva requiere primero documentación canónica; no se esconde dentro de PR6.

`estoma-infra` queda explícitamente fuera de PR6.

---

## 6. Definition of Done del último PR de implementación

PR6 solo puede considerarse terminado cuando:

- [ ] todas las rutas actuales de Authentication/Mailbox/Wash siguen compatibles;
- [ ] `wash/student/home` está implementado;
- [ ] `wash/appointments/form-context` está implementado;
- [ ] `/api/v1/admin/**` tiene superficie cerrada para el alcance owner-ready;
- [ ] la genesis baseline evita `BOOTSTRAPPING` permanente sin fabricar snapshot state;
- [ ] restart normal no reejecuta genesis;
- [ ] pérdida de DB no auto-promueve proyecciones a `READY`;
- [ ] Authorization recovery continúa owner-driven;
- [ ] OpenAPI coincide exactamente con controllers/DTOs;
- [ ] Availability sigue ausente si el owner gate continúa abierto;
- [ ] `./mvnw -B clean verify` es verde;
- [ ] PostgreSQL Testcontainers es verde;
- [ ] RabbitMQ Testcontainers es verde;
- [ ] ArchUnit es verde;
- [ ] Docker image build smoke es verde;
- [ ] restart/recovery tests son verdes;
- [ ] no aparecen secretos en DB/logs/traces de las pruebas;
- [ ] no existen endpoints placeholder que aparenten capacidades no implementadas;
- [ ] no existen llamadas HTTP/gRPC BFF → microservicios;
- [ ] no existe una saga/process manager escondida en BFF.

---

## 7. Estado esperado después del merge de PR6

Después de PR6, dentro de `estoma-services`:

```text
BFF Shared + Lavado functional implementation = COMPLETE
BFF public contract                           = COMPLETE
BFF executable verification                   = COMPLETE
BFF container                                 = READY
```

Lo único pendiente debe vivir en `estoma-infra`:

```text
PostgreSQL database/role/secret provisioning
RabbitMQ vhost/users/permissions/topology
BFF queue/bindings before owner traffic
JWK/secret/config mounting
initial genesis flag for first deployment only
Kubernetes Deployment/Service
Ingress/TLS
startup/liveness/readiness probes
resource requests/limits
replica/HPA policy
NetworkPolicy
backup + PITR for bff-db
observability dashboards/alerts
GitOps environment overlays
rollout + deployment smoke tests
```

Ninguno de estos puntos debe requerir modificar reglas de negocio, HTTP contracts o runtime funcional del BFF.

---

## 8. Forma recomendada del PR siguiente

```text
Title:
feat(platform-bff): complete V1 deployment readiness

Base:
develop

Scope:
platform-bff/**
+ only canonical owner fixes strictly required by E2E

No infra manifests.
No Practices/Jornadas scope expansion.
```

El PR debe comenzar revalidando este gate contra el `develop` vigente para evitar reintroducir contratos ya corregidos por PR1–PR5.
