# authentication-service Pending Decisions

## Status

The former Phase-3 cross-service contract questions are resolved by the canonical platform documentation and this PR3 integration.

Resolved in code:

- Users Person events use canonical `producer = users` / `users.person.*` routing.
- Academic coordinated account Commands use `producer = academic`, `SYSTEM/academic`, `importExecution`, stable `operationId` and `idempotencyKey`.
- Authorization projection recovery uses `platform.queries -> queries.authentication` with `GetAccountStatusSnapshot` and `ListAccountStatusSnapshotsPage`.
- Command Results route to the original requester and coordinated Academic terminal Results retain `importId`, `planItemId`, and `executionGeneration`.
- `PasswordChanged` integration with Communications is governed by the Communications owner contract rather than Authentication-local assumptions.

## Outside This Service

The following remain platform/infra ownership and do not change Authentication business behavior:

- secret-management implementation beyond the application contract;
- resource requests/limits and HPA policy;
- database/user provisioning;
- physical RabbitMQ provisioning in environments where application topology declaration is disabled;
- distributed E2E validation across Users, Academic, Authorization, BFF and Communications.

No open Authentication-local business decision blocks V1.
