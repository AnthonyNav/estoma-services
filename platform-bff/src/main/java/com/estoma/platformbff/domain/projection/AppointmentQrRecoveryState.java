package com.estoma.platformbff.domain.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AppointmentQrRecoveryState(
    LocalDate serviceDate,
    long generation,
    long restartRevision,
    UUID recoveryJobId,
    int passNumber,
    int pageNumber,
    String pageToken,
    UUID currentQueryMessageId,
    String status,
    Instant startedAt,
    Instant updatedAt,
    Instant completedAt,
    String lastErrorCode) {}
