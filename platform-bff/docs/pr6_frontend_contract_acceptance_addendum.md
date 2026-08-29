# Platform BFF — PR6 Frontend Contract Acceptance Addendum

**Ruta:** `platform-bff/docs/pr6_frontend_contract_acceptance_addendum.md`  
**Estado:** addendum obligatorio de PR6  
**Alcance:** Shared + Lavado Ultrasónico.

---

## 1. Motivo

La revisión previa a PR6 confirmó que el BFF ya tenía:

- semántica HTTP general;
- `202 + operationId + polling`;
- `Idempotency-Key`;
- `application/problem+json`;
- casos de uso de cliente de Lavado;
- contratos específicos de ingreso/reasignación/salida;
- un gate PR6 que exige OpenAPI sin drift.

Sin embargo faltaban dos cierres explícitos antes de implementar PR6:

1. un criterio global y verificable para declarar aceptado el contrato BFF ↔ Frontend;
2. una matriz canónica completa para `/api/v1/admin/**`, que el gate PR6 todavía pedía construir dentro del propio PR.

La fuente canónica de estas decisiones queda en `estomatologia-platform-docs`:

```text
03-services/edge/bff/frontend-contract-acceptance.md
03-services/edge/bff/admin-http-contract.md
```

Este addendum no redefine owners ni payloads de dominio; convierte esas decisiones en un gate ejecutable local para PR6.

---

## 2. Orden de precedencia

PR6 debe reconciliar, en este orden:

```text
owner canonical contract
→ estomatologia-platform-docs BFF contracts
→ frontend-contract-acceptance.md
→ admin-http-contract.md
→ platform-bff/docs/system_contract.md
→ platform-bff/docs/pr6_deployment_readiness_gate.md
→ runtime/OpenAPI/tests
```

Si el código actual contradice el contrato canónico, PR6 corrige el código o documenta primero la decisión; no conserva el drift por compatibilidad accidental.

---

## 3. OpenAPI como contrato ejecutable

PR6 debe crear y versionar:

```text
platform-bff/docs/openapi.yaml
```

Debe representar exactamente la superficie realmente expuesta de:

```text
auth
me
operations
mailbox
admin
wash
health público documentado, cuando aplique
```

`GET /api/v1/wash/appointments/availability` no aparece como funcional mientras siga `GATED_BY_APPOINTMENTS_OWNER_CONTRACT`.

El spec debe incluir como mínimo:

- method/path;
- auth scheme;
- path/query/header parameters;
- `Idempotency-Key` donde aplique;
- request/response schemas reales;
- `202 OperationAccepted` canónico;
- operation polling;
- `application/problem+json`;
- `503`/readiness cuando la vista dependa de proyecciones no confiables;
- content types;
- ejemplos sin secretos.

---

## 4. Gate provider BFF ↔ Front

Una ruta solo puede considerarse `PROVIDER_READY` cuando:

- está documentada canónicamente;
- existe en runtime;
- existe en OpenAPI;
- controller/DTO/OpenAPI no tienen drift;
- seguridad y headers obligatorios están probados;
- errores inmediatos siguen Problem Details;
- una escritura durable conserva `202 + polling + idempotencia`;
- una lectura fail-closed no oculta degradación con `null`/lista vacía falsa;
- no expone metadata wire interna;
- no filtra secretos;
- Docker smoke arranca la misma superficie.

PR6 debe llevar todo Shared + Lavado implementado a `PROVIDER_READY`.

La aceptación del consumidor (`estoma-app`) puede ocurrir en un PR posterior del frontend, pero una pantalla no se declara integrada hasta que sus mocks/adapters/tests consuman la misma revisión de OpenAPI.

---

## 5. Contract tests obligatorios en PR6

Agregar tests automáticos para:

1. inventario de controllers ↔ OpenAPI;
2. method/path exactos;
3. request schemas y campos requeridos;
4. response schemas y status codes;
5. security scheme;
6. `Idempotency-Key` en Commands durables;
7. `202` exacto: `operationId/status/pollPath/submittedAt`;
8. polling: `PENDING/SUCCEEDED/REJECTED/FAILED/EXPIRED`;
9. Problem Details mínimo: `type/title/status/code/detail/traceId`;
10. `503` para proyección obligatoria no confiable;
11. serialización de UUID/date-time/enums;
12. ausencia de campos RabbitMQ/aggregate metadata no públicos;
13. ausencia de secretos en examples/logs capturados;
14. examples/fixtures locales válidos contra OpenAPI;
15. Docker build/start smoke.

La librería concreta puede variar. La propiedad requerida es detectar drift automáticamente.

---

## 6. Superficie admin

PR6 debe implementar la matriz canónica de:

```text
estomatologia-platform-docs/03-services/edge/bff/admin-http-contract.md
```

Para cada ruta durable debe existir un mapping verificable:

```text
HTTP method + path
→ publicOperation
→ messageType
→ owner
```

Para Security RPC administrativo:

```text
HTTP method + path
→ publicOperation
→ RPC operation
→ authentication
```

No se agregan endpoints owner internos ni aliases alternativos.

---

## 7. Impacto en `estoma-app`

El frontend actual puede continuar con mocks mientras PR6 se implementa. El contrato de integración deja de depender de mocks manuales una vez exista `platform-bff/docs/openapi.yaml`.

En el PR de integración del frontend se debe:

- centralizar llamadas en una capa API/adapters;
- generar tipos/cliente desde OpenAPI o validar automáticamente tipos/fixtures contra el spec;
- validar mocks existentes contra la misma revisión del contrato;
- implementar el state machine de `202 → polling → terminal`;
- reutilizar `Idempotency-Key` al reintentar la misma intención incierta;
- distinguir `401/403/409/503` y Problem Details;
- no persistir/loggear secretos.

No se requiere bloquear PR6 hasta que toda la UI esté conectada; sí se requiere que el provider contract esté completo y estable.

---

## 8. PR6 readiness después de este addendum

Con este addendum y los dos contratos canónicos mencionados, no queda una decisión BFF-owned conocida que deba inventarse durante PR6 para:

```text
projection genesis/readiness
wash/student/home
wash/appointments/form-context
admin HTTP route inventory
BFF ↔ Front contract acceptance
OpenAPI/drift gate
hardening/E2E
```

El único gate funcional explícito que permanece fuera del BFF es:

```text
GET /api/v1/wash/appointments/availability
= GATED_BY_APPOINTMENTS_OWNER_CONTRACT
```

Otros cambios que surjan de E2E solo pueden ser correcciones de una regresión o restauración de un contrato ya canónico; una decisión owner nueva debe documentarse primero.

---

## 9. Definition of Done adicional de PR6

Además del `pr6_deployment_readiness_gate.md`, PR6 requiere:

- [ ] `platform-bff/docs/openapi.yaml` presente;
- [ ] inventario runtime ↔ OpenAPI sin drift;
- [ ] toda ruta Shared + Lavado implementada está `PROVIDER_READY`;
- [ ] matriz `/api/v1/admin/**` coincide con `admin-http-contract.md`;
- [ ] tests ruta → publicOperation → owner/RPC verdes;
- [ ] mocks/examples de backend validan contra OpenAPI;
- [ ] no aparece Availability como funcional mientras el gate owner siga abierto;
- [ ] el PR documenta el commit canónico de `estomatologia-platform-docs` usado como baseline.
