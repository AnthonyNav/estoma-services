package com.estoma.communications.contracts.consumed;

import java.time.LocalDate;
import java.util.UUID;

/** Minimal owner fact required to retain Wash inbox messages by academic period. */
public record AcademicPeriodActivatedPayload(
    UUID academicPeriodId, String code, String status, LocalDate startDate, LocalDate endDate) {}
