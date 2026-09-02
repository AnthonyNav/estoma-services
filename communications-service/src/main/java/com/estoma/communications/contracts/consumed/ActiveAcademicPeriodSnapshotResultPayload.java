package com.estoma.communications.contracts.consumed;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Academic's requester-scoped durable recovery response. */
public record ActiveAcademicPeriodSnapshotResultPayload(String status, Data data) {
  public record Data(
      UUID academicPeriodId,
      String code,
      String status,
      LocalDate startDate,
      LocalDate endDate,
      int sourceVersion,
      Instant snapshotGeneratedAt) {}
}
