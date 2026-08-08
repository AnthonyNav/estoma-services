package com.estoma.authentication.contracts.consumed;

import java.util.UUID;

/**
 * Conceptual payload of {@code StudentNumberCorrected} as consumed by this
 * service (data-model.md §9.1, events.md §9): identifies the MATRICULA
 * account whose {@code loginIdentifier} must be synchronized, the expected
 * previous value, and the ordering version.
 */
public record StudentNumberCorrectedPayload(
    UUID accountId, UUID studentAcademicProfileId, int studentNumberVersion, String previousStudentNumber, String newStudentNumber) {}
