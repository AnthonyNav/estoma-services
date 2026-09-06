package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.projection.AppointmentQrRecoveryState;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentQrRecoveryRepository {
  void lock(LocalDate serviceDate);

  long nextGeneration();

  void insert(AppointmentQrRecoveryState state);

  void update(AppointmentQrRecoveryState state);

  void replace(long expectedGeneration, AppointmentQrRecoveryState state);

  Optional<AppointmentQrRecoveryState> find(LocalDate serviceDate);

  Optional<AppointmentQrRecoveryState> findByJobId(UUID recoveryJobId);

  boolean hasPageToken(UUID recoveryJobId, int passNumber, String pageToken);

  boolean recordPageToken(UUID recoveryJobId, int passNumber, String pageToken);
}
