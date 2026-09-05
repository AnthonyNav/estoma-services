package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.AppointmentAvailabilityPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAppointmentAvailabilityRepository implements AppointmentAvailabilityPort {
  private static final Duration CLAIM_DURATION = Duration.ofSeconds(30);
  private final JdbcClient jdbc;

  public JdbcAppointmentAvailabilityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Snapshot> findSnapshot(Context context) {
    return jdbc.sql("""
        SELECT * FROM appointment_availability_read_model
        WHERE context_id = :contextId
          AND student_account_id = :accountId
          AND service_date = :serviceDate
          AND appointment_type = :appointmentType
          AND instrument_count = :instrumentCount
          AND piece_type = :pieceType
          AND course_section_id = :courseSectionId
        """)
        .param("contextId", context.contextId())
        .param("accountId", context.studentAccountId())
        .param("serviceDate", context.serviceDate())
        .param("appointmentType", context.appointmentType())
        .param("instrumentCount", context.instrumentCount())
        .param("pieceType", context.pieceType())
        .param("courseSectionId", context.courseSectionId())
        .query((rs, row) -> snapshot(rs, context))
        .optional();
  }

  @Override
  public boolean enqueueRefresh(Refresh refresh, Instant replaceBefore) {
    Context context = refresh.context();
    return jdbc.sql("""
        INSERT INTO appointment_availability_refresh (
          context_id, student_account_id, service_date, appointment_type, instrument_count,
          piece_type, course_section_id, query_message_id, correlation_id, status,
          claim_token, claim_until, attempt_count, next_attempt_at, published_at,
          result_message_id, last_error_code, requested_at, updated_at)
        VALUES (:contextId, :accountId, :serviceDate, :appointmentType, :instrumentCount,
          :pieceType, :courseSectionId, :messageId, :correlationId, 'PENDING',
          NULL, NULL, 0, NULL, NULL, NULL, NULL, :requestedAt, :requestedAt)
        ON CONFLICT (context_id) DO UPDATE SET
          student_account_id = EXCLUDED.student_account_id,
          service_date = EXCLUDED.service_date,
          appointment_type = EXCLUDED.appointment_type,
          instrument_count = EXCLUDED.instrument_count,
          piece_type = EXCLUDED.piece_type,
          course_section_id = EXCLUDED.course_section_id,
          query_message_id = EXCLUDED.query_message_id,
          correlation_id = EXCLUDED.correlation_id,
          status = 'PENDING', claim_token = NULL, claim_until = NULL, attempt_count = 0,
          next_attempt_at = NULL, published_at = NULL, result_message_id = NULL,
          last_error_code = NULL, requested_at = EXCLUDED.requested_at,
          updated_at = EXCLUDED.updated_at
        WHERE appointment_availability_refresh.status = 'APPLIED'
           OR (appointment_availability_refresh.status IN
                 ('PENDING', 'PUBLISHED', 'FAILED', 'REJECTED')
               AND appointment_availability_refresh.requested_at <= :replaceBefore)
        """)
        .param("contextId", context.contextId())
        .param("accountId", context.studentAccountId())
        .param("serviceDate", context.serviceDate())
        .param("appointmentType", context.appointmentType())
        .param("instrumentCount", context.instrumentCount())
        .param("pieceType", context.pieceType())
        .param("courseSectionId", context.courseSectionId())
        .param("messageId", refresh.messageId())
        .param("correlationId", refresh.correlationId())
        .param("requestedAt", JdbcTimes.timestamp(refresh.requestedAt()))
        .param("replaceBefore", JdbcTimes.timestamp(replaceBefore))
        .update() == 1;
  }

  @Override
  public Optional<Refresh> findRefreshByMessageId(UUID messageId) {
    return jdbc.sql("SELECT * FROM appointment_availability_refresh WHERE query_message_id = :messageId")
        .param("messageId", messageId)
        .query((rs, row) -> refresh(rs))
        .optional();
  }

  @Override
  public Optional<Refresh> findRefresh(Context context) {
    return jdbc.sql("SELECT * FROM appointment_availability_refresh WHERE context_id = :contextId")
        .param("contextId", context.contextId())
        .query((rs, row) -> refresh(rs))
        .optional()
        .filter(refresh -> sameContext(refresh.context(), context));
  }

  @Override
  public Optional<Refresh> claimNext(Instant now) {
    UUID token = UUID.randomUUID();
    return jdbc.sql("""
        WITH candidate AS (
          SELECT context_id FROM appointment_availability_refresh
          WHERE ((status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
              OR (status = 'FAILED' AND next_attempt_at IS NOT NULL AND next_attempt_at <= :now))
            AND (claim_until IS NULL OR claim_until < :now)
          ORDER BY requested_at, context_id
          FOR UPDATE SKIP LOCKED
          LIMIT 1
        )
        UPDATE appointment_availability_refresh refresh
        SET claim_token = :claimToken, claim_until = :claimUntil,
            attempt_count = refresh.attempt_count + 1, updated_at = :now
        FROM candidate
        WHERE refresh.context_id = candidate.context_id
        RETURNING refresh.*
        """)
        .param("now", JdbcTimes.timestamp(now))
        .param("claimToken", token)
        .param("claimUntil", JdbcTimes.timestamp(now.plus(CLAIM_DURATION)))
        .query((rs, row) -> refresh(rs))
        .optional();
  }

  @Override
  public boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt) {
    return jdbc.sql("""
        UPDATE appointment_availability_refresh
        SET status = 'PUBLISHED', published_at = :publishedAt, claim_token = NULL,
            claim_until = NULL, next_attempt_at = NULL, last_error_code = NULL,
            updated_at = :publishedAt
        WHERE query_message_id = :messageId AND status IN ('PENDING', 'FAILED')
          AND claim_token = :claimToken
        """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("publishedAt", JdbcTimes.timestamp(publishedAt))
        .update() == 1;
  }

  @Override
  public boolean markFailed(UUID messageId, UUID claimToken, String errorCode, Instant nextAttemptAt) {
    Instant updatedAt = Instant.now();
    return jdbc.sql("""
        UPDATE appointment_availability_refresh
        SET status = 'FAILED', last_error_code = :errorCode, next_attempt_at = :nextAttemptAt,
            claim_token = NULL, claim_until = NULL, updated_at = :updatedAt
        WHERE query_message_id = :messageId AND status IN ('PENDING', 'FAILED')
          AND claim_token = :claimToken
        """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("errorCode", errorCode)
        .param("nextAttemptAt", JdbcTimes.timestamp(nextAttemptAt))
        .param("updatedAt", JdbcTimes.timestamp(updatedAt))
        .update() == 1;
  }

  @Override
  public void applySnapshot(Refresh expected, UUID resultMessageId, Snapshot snapshot,
      Instant appliedAt) {
    if (!sameContext(expected.context(), snapshot.requestContext())) {
      throw new IllegalArgumentException("availability result requestContext does not match refresh");
    }
    requireCurrentRefresh(expected, resultMessageId, "APPLIED", null, appliedAt);
    Context context = snapshot.requestContext();
    jdbc.sql("""
        INSERT INTO appointment_availability_read_model (
          context_id, student_account_id, service_date, appointment_type, instrument_count,
          piece_type, course_section_id, snapshot_id, query_message_id, correlation_id,
          snapshot_generated_at, fresh_until, can_schedule, blocking_reasons,
          daily_penalty_points, daily_completed_appointments,
          exceptional_authorization_required, exceptional_authorization_available,
          exceptional_authorization_id, updated_at)
        VALUES (:contextId, :accountId, :serviceDate, :appointmentType, :instrumentCount,
          :pieceType, :courseSectionId, :snapshotId, :queryMessageId, :correlationId,
          :generatedAt, :freshUntil, :canSchedule, :blockingReasons,
          :penaltyPoints, :completedAppointments, :authorizationRequired,
          :authorizationAvailable, :authorizationId, :updatedAt)
        ON CONFLICT (context_id) DO UPDATE SET
          student_account_id = EXCLUDED.student_account_id,
          service_date = EXCLUDED.service_date,
          appointment_type = EXCLUDED.appointment_type,
          instrument_count = EXCLUDED.instrument_count,
          piece_type = EXCLUDED.piece_type,
          course_section_id = EXCLUDED.course_section_id,
          snapshot_id = EXCLUDED.snapshot_id,
          query_message_id = EXCLUDED.query_message_id,
          correlation_id = EXCLUDED.correlation_id,
          snapshot_generated_at = EXCLUDED.snapshot_generated_at,
          fresh_until = EXCLUDED.fresh_until,
          can_schedule = EXCLUDED.can_schedule,
          blocking_reasons = EXCLUDED.blocking_reasons,
          daily_penalty_points = EXCLUDED.daily_penalty_points,
          daily_completed_appointments = EXCLUDED.daily_completed_appointments,
          exceptional_authorization_required = EXCLUDED.exceptional_authorization_required,
          exceptional_authorization_available = EXCLUDED.exceptional_authorization_available,
          exceptional_authorization_id = EXCLUDED.exceptional_authorization_id,
          updated_at = EXCLUDED.updated_at
        """)
        .param("contextId", context.contextId())
        .param("accountId", context.studentAccountId())
        .param("serviceDate", context.serviceDate())
        .param("appointmentType", context.appointmentType())
        .param("instrumentCount", context.instrumentCount())
        .param("pieceType", context.pieceType())
        .param("courseSectionId", context.courseSectionId())
        .param("snapshotId", snapshot.snapshotId())
        .param("queryMessageId", expected.messageId())
        .param("correlationId", expected.correlationId())
        .param("generatedAt", JdbcTimes.timestamp(snapshot.snapshotGeneratedAt()))
        .param("freshUntil", JdbcTimes.timestamp(snapshot.freshUntil()))
        .param("canSchedule", snapshot.canSchedule())
        .param("blockingReasons", snapshot.blockingReasons().toArray(String[]::new))
        .param("penaltyPoints", snapshot.dailyPenaltyPoints())
        .param("completedAppointments", snapshot.dailyCompletedAppointments())
        .param("authorizationRequired", snapshot.exceptionalAuthorizationRequired())
        .param("authorizationAvailable", snapshot.exceptionalAuthorizationAvailable())
        .param("authorizationId", snapshot.exceptionalAuthorizationId())
        .param("updatedAt", JdbcTimes.timestamp(appliedAt))
        .update();

    jdbc.sql("DELETE FROM appointment_availability_slot_read_model WHERE context_id = :contextId")
        .param("contextId", context.contextId()).update();
    for (AvailableTimeSlot slot : snapshot.availableTimeSlots()) {
      jdbc.sql("""
          INSERT INTO appointment_availability_slot_read_model (
            context_id, appointment_time_slot_id, starts_at, ends_at,
            available_capacity, booking_deadline_at)
          VALUES (:contextId, :slotId, :startsAt, :endsAt, :capacity, :bookingDeadlineAt)
          """)
          .param("contextId", context.contextId())
          .param("slotId", slot.appointmentTimeSlotId())
          .param("startsAt", JdbcTimes.timestamp(slot.startsAt()))
          .param("endsAt", JdbcTimes.timestamp(slot.endsAt()))
          .param("capacity", slot.availableCapacity())
          .param("bookingDeadlineAt", JdbcTimes.timestamp(slot.bookingDeadlineAt()))
          .update();
    }
  }

  @Override
  public void rejectRefresh(Refresh expected, UUID resultMessageId, String errorCode,
      Instant appliedAt) {
    requireCurrentRefresh(expected, resultMessageId, "REJECTED", errorCode, appliedAt);
  }

  private void requireCurrentRefresh(Refresh expected, UUID resultMessageId, String status,
      String errorCode, Instant appliedAt) {
    int updated = jdbc.sql("""
        UPDATE appointment_availability_refresh
        SET status = :status, result_message_id = :resultMessageId,
            last_error_code = :errorCode, updated_at = :updatedAt
        WHERE context_id = :contextId AND query_message_id = :queryMessageId
          AND correlation_id = :correlationId AND status IN ('PENDING', 'PUBLISHED')
        """)
        .param("status", status)
        .param("resultMessageId", resultMessageId)
        .param("errorCode", errorCode)
        .param("updatedAt", JdbcTimes.timestamp(appliedAt))
        .param("contextId", expected.context().contextId())
        .param("queryMessageId", expected.messageId())
        .param("correlationId", expected.correlationId())
        .update();
    if (updated != 1) throw new IllegalArgumentException("availability result is stale or unsolicited");
  }

  private Snapshot snapshot(ResultSet rs, Context context) throws SQLException {
    UUID contextId = context.contextId();
    List<AvailableTimeSlot> slots = jdbc.sql("""
        SELECT appointment_time_slot_id, starts_at, ends_at, available_capacity,
               booking_deadline_at
        FROM appointment_availability_slot_read_model
        WHERE context_id = :contextId
        ORDER BY starts_at, appointment_time_slot_id
        """)
        .param("contextId", contextId)
        .query((slotRs, row) -> new AvailableTimeSlot(
            slotRs.getObject("appointment_time_slot_id", UUID.class),
            JdbcTimes.instant(slotRs, "starts_at"), JdbcTimes.instant(slotRs, "ends_at"),
            slotRs.getInt("available_capacity"),
            JdbcTimes.instant(slotRs, "booking_deadline_at")))
        .list();
    String[] blockers = (String[]) rs.getArray("blocking_reasons").getArray();
    return new Snapshot(context, rs.getObject("snapshot_id", UUID.class),
        JdbcTimes.instant(rs, "snapshot_generated_at"), JdbcTimes.instant(rs, "fresh_until"),
        rs.getBoolean("can_schedule"), Arrays.asList(blockers),
        rs.getInt("daily_penalty_points"), rs.getInt("daily_completed_appointments"),
        rs.getBoolean("exceptional_authorization_required"),
        rs.getBoolean("exceptional_authorization_available"),
        rs.getObject("exceptional_authorization_id", UUID.class), slots);
  }

  private static Refresh refresh(ResultSet rs) throws SQLException {
    Context context = new Context(rs.getObject("student_account_id", UUID.class),
        rs.getObject("service_date", java.time.LocalDate.class), rs.getString("appointment_type"),
        rs.getInt("instrument_count"), rs.getString("piece_type"),
        rs.getObject("course_section_id", UUID.class));
    return new Refresh(context, rs.getObject("query_message_id", UUID.class),
        rs.getObject("correlation_id", UUID.class), rs.getString("status"),
        rs.getObject("claim_token", UUID.class), JdbcTimes.instant(rs, "claim_until"),
        rs.getInt("attempt_count"), JdbcTimes.instant(rs, "next_attempt_at"),
        JdbcTimes.instant(rs, "published_at"), rs.getString("last_error_code"),
        JdbcTimes.instant(rs, "requested_at"));
  }

  private static boolean sameContext(Context left, Context right) {
    return left.equals(right) && left.contextId().equals(right.contextId());
  }
}
