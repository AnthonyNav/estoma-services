package com.estoma.communications.contracts.consumed;

import java.util.UUID;

/**
 * Conceptual payload shared by {@code PersonCreated}/{@code
 * PersonUpdated}/{@code PersonActivated}/{@code PersonDeactivated}, as
 * assumed by this service's own {@code data-model.md} (§3, "recipient
 * data"). Not yet formalized against what {@code users-service} actually
 * publishes (execution_plan.md Phase 3). Carries no account_id: a Person
 * event updates every existing {@code recipient_projection} row for this
 * {@code personId}, it does not create one.
 */
public record PersonProjectionEventPayload(UUID personId, String displayName, String institutionalEmail, String status, int sourceVersion) {}
