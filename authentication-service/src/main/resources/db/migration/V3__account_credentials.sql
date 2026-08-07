-- account_credentials — 0..1 with accounts. The row does not exist until a
-- credential is configured through the ephemeral security RPC flow (never
-- through CreateAccount or any durable command).
--
-- Source: authentication-service/docs/system_contract.md, platform docs
-- data-model.md §6.

CREATE TABLE account_credentials (
    account_id            uuid PRIMARY KEY REFERENCES accounts (account_id),

    password_hash          varchar(500) NOT NULL,
    hash_algorithm           varchar(40) NOT NULL,
    hash_parameters            jsonb NOT NULL,

    password_changed_at          timestamptz NOT NULL
);
