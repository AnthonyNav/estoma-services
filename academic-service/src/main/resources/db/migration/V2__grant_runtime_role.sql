-- Flyway runs with the migration account. The runtime account can operate only its owned tables,
-- never alter schema or migrations. Keep this after every schema object migration.
GRANT USAGE ON SCHEMA public TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO ${runtime_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${runtime_role};
