GRANT USAGE ON SCHEMA public TO ${runtime_role};

GRANT SELECT, INSERT, UPDATE ON service_calendars, service_schedules,
    administrative_closures, cabins, tanks TO ${runtime_role};
GRANT SELECT, INSERT ON resource_priorities TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE ON authorization_role_projection,
    authorization_access_projection TO ${runtime_role};
GRANT SELECT, INSERT, DELETE ON processed_messages TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON command_idempotency_records,
    outbox_messages TO ${runtime_role};
