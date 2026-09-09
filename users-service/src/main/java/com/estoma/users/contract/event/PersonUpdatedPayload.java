package com.estoma.users.contract.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code PersonUpdated} event (events.md §3). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonUpdatedPayload(
    UUID personId,
    String firstName,
    String paternalSurname,
    String maternalSurname,
    String institutionalEmail,
    String phone,
    int version,
    Instant updatedAt) {}
