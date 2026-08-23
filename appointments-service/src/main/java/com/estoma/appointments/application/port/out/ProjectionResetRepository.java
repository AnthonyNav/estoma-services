package com.estoma.appointments.application.port.out;

/** Clears one owner projection exactly when starting a FULL authoritative rebuild. */
public interface ProjectionResetRepository {
  void reset(String projectionName);
}
