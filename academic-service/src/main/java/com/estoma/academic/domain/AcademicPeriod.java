package com.estoma.academic.domain;

import java.time.LocalDate;
import java.util.UUID;

public record AcademicPeriod(UUID id, String code, LocalDate startDate, LocalDate endDate, Status status, DataStatus dataStatus, int version) {
  public enum Status { ACTIVE, CLOSED }
  public enum DataStatus { PENDING_IMPORT, IMPORTING, READY, IMPORT_FAILED }

  public AcademicPeriod {
    if (id == null || code == null || code.isBlank() || startDate == null || endDate == null || endDate.isBefore(startDate)
        || status == null || dataStatus == null || version < 1) throw new IllegalArgumentException("invalid academic period");
    if (status == Status.CLOSED && dataStatus == DataStatus.IMPORTING) throw new IllegalArgumentException("closed period cannot import");
  }

  public void requireReadyForTeaching() {
    if (status == Status.CLOSED) throw new IllegalStateException("academic period closed");
    if (dataStatus != DataStatus.READY) throw new IllegalStateException("academic period not ready");
  }
}
