-- Enumerated types for accounts, sessions, the student-number sync
-- projection, the security RPC idempotency ledger, and the transactional
-- outbox.
--
-- Source: authentication-service/docs/system_contract.md, and the
-- platform docs (03-services/shared/authentication-service/data-model.md §4).

CREATE TYPE login_type AS ENUM ('MATRICULA', 'USERNAME');

CREATE TYPE account_status AS ENUM ('ACTIVE', 'INACTIVE');

CREATE TYPE person_status AS ENUM ('ACTIVE', 'INACTIVE');

CREATE TYPE student_number_sync_status AS ENUM ('PENDING', 'SYNCHRONIZED', 'FAILED');

CREATE TYPE security_rpc_idempotency_status AS ENUM ('PROCESSING', 'COMPLETED', 'FAILED');

CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');
