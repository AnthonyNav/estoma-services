-- Enumerated types shared by the tables that follow.
--
-- Source: communications-service/docs/system_contract.md ("Persistence
-- Boundary"), platform docs data-model.md §8.

CREATE TYPE communication_priority AS ENUM ('CRITICAL', 'NORMAL', 'INFORMATIONAL');

CREATE TYPE communication_status AS ENUM ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED');

CREATE TYPE inbox_message_status AS ENUM ('ACTIVE', 'EXPIRED');

CREATE TYPE email_content_mode AS ENUM ('GENERIC', 'TEMPLATE');

CREATE TYPE email_delivery_status AS ENUM ('PENDING', 'SENDING', 'ACCEPTED', 'DELIVERED', 'RETRY_SCHEDULED', 'FAILED');

CREATE TYPE email_attempt_status AS ENUM ('ACCEPTED', 'FAILED');

-- Used by recipient_projection's person_status/account_status columns.
CREATE TYPE projected_status AS ENUM ('ACTIVE', 'INACTIVE');

CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');
