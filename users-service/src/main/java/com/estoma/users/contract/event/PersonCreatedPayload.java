package com.estoma.users.contract.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code PersonCreated} event (events.md §2). */
public record PersonCreatedPayload(
    UUID personId,
    String firstName,
    String paternalSurname,
    String maternalSurname,
    String institutionalEmail,
    String phone,
    String status,
    int version,
    Instant createdAt) {}
