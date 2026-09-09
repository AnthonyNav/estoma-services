package com.estoma.users.contract.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code PersonCreated} event (events.md §2). */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
