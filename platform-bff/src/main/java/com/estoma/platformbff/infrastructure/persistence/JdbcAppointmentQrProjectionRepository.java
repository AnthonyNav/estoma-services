package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.AppointmentQrProjectionPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Technical projection for opaque owner-issued QR references. */
@Repository
public class JdbcAppointmentQrProjectionRepository implements AppointmentQrProjectionPort {
  private final JdbcClient jdbc;

  public JdbcAppointmentQrProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void project(UUID appointmentId, String qrRepresentation, long appointmentVersion,
      Instant updatedAt) {
    upsert(appointmentId, qrRepresentation, sha256(qrRepresentation), appointmentVersion, updatedAt);
  }

  @Override
  public void invalidate(UUID appointmentId, long appointmentVersion, Instant updatedAt) {
    upsert(appointmentId, null, null, appointmentVersion, updatedAt);
  }

  private void upsert(UUID appointmentId, String qrRepresentation, byte[] qrLookupHash,
      long appointmentVersion, Instant updatedAt) {
    jdbc.sql("""
        INSERT INTO appointment_qr_projection (
          appointment_id, qr_representation, qr_lookup_hash, appointment_version, updated_at)
        VALUES (:appointmentId, :qrRepresentation, :qrLookupHash, :appointmentVersion, :updatedAt)
        ON CONFLICT (appointment_id) DO UPDATE SET
          qr_representation = EXCLUDED.qr_representation,
          qr_lookup_hash = EXCLUDED.qr_lookup_hash,
          appointment_version = EXCLUDED.appointment_version,
          updated_at = EXCLUDED.updated_at
        WHERE appointment_qr_projection.appointment_version < EXCLUDED.appointment_version
        """)
        .param("appointmentId", appointmentId)
        .param("qrRepresentation", qrRepresentation)
        .param("qrLookupHash", qrLookupHash)
        .param("appointmentVersion", appointmentVersion)
        .param("updatedAt", JdbcTimes.timestamp(updatedAt))
        .update();
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
