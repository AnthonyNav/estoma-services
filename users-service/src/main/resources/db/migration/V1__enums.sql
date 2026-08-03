-- Enumerated types shared by the domain (person_status) and the
-- transactional outbox (outbox_status).
--
-- Source: estomatologia-platform-docs
--   03-services/shared/users-service/data-model.md §4

CREATE TYPE person_status AS ENUM ('ACTIVE', 'INACTIVE');

CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');
