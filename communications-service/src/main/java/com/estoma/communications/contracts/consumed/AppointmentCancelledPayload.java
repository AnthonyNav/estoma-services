package com.estoma.communications.contracts.consumed;

import java.util.UUID;

/**
 * Conceptual payload for {@code AppointmentCancelled}, as published by
 * {@code appointments-service} (events.md §5.1). Only {@code
 * cancellationType = CLINIC} and {@code cancellationSubreason =
 * CAPACITY_LOSS} trigger the {@code AppointmentCancelledAdapter}; every
 * other combination is ignored. Not yet formalized against the actual
 * payload {@code appointments-service} publishes (execution_plan.md
 * Phase 3).
 */
public record AppointmentCancelledPayload(UUID appointmentId, UUID studentAccountId, String cancellationType, String cancellationSubreason) {}
