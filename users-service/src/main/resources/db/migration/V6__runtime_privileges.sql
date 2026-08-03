-- Grants the runtime application role the privileges it needs, run by the
-- migration role (which may have broader privileges, e.g. DDL). This is
-- what satisfies the platform rule that migration accounts must be
-- separate from execution accounts:
--
--   estomatologia-platform-docs 02-architecture/security-observability.md:235
--   "Las cuentas de migración podrán tener permisos superiores, pero
--    deberán estar separadas de las cuentas de ejecución."
--
-- ${runtime_role} is a Flyway placeholder (spring.flyway.placeholders.
-- runtime_role). In dev/test it defaults to the same user Flyway itself
-- connects as (see application.yml / application-test.yml), making this a
-- harmless self-grant. In staging/prod estoma-infra provisions a distinct,
-- least-privilege runtime role and passes its name here.

GRANT USAGE ON SCHEMA public TO ${runtime_role};

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${runtime_role};

GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO ${runtime_role};
