-- V11 predates the V12+ tables. Reapply current privileges and make
-- migration-user-created tables and sequences available to the runtime role.
GRANT USAGE ON SCHEMA public TO ${runtime_role};

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${runtime_role};

GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO ${runtime_role};

REVOKE ALL PRIVILEGES ON TABLE public.flyway_schema_history FROM ${runtime_role};

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${runtime_role};

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE ON SEQUENCES TO ${runtime_role};
