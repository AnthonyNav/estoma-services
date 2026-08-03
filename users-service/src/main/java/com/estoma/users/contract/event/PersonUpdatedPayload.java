package com.estoma.users.contract.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code PersonUpdated} event (events.md §3). */
public record PersonUpdatedPayload(
    UUID personId,
    String firstName,
    String paternalSurname,
    String maternalSurname,
    String institutionalEmail,
    String phone,
    int version,
    Instant updatedAt) {}
