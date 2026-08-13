package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.ServiceScheduleRepository;
import com.estoma.washadministration.domain.schedule.ServiceSchedule;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcServiceScheduleRepository implements ServiceScheduleRepository {

  private static final String COLUMNS =
      "service_schedule_id, service_calendar_id, day_of_week, opening_time, closing_time, "
          + "slot_duration_minutes, status, version, created_by_account_id, updated_by_account_id, "
          + "created_at, updated_at";
  private final JdbcClient jdbc;

  JdbcServiceScheduleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ServiceSchedule> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM service_schedules WHERE service_schedule_id = :id")
        .param("id", id)
        .query(ScheduleRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public List<ServiceSchedule> findActiveOverlapping(
      UUID calendarId, int dayOfWeek, LocalTime openingTime, LocalTime closingTime, UUID excludedId) {
    return jdbc.sql(
            """
            SELECT %s FROM service_schedules
             WHERE service_calendar_id = :calendarId
               AND day_of_week = :dayOfWeek
               AND status = 'ACTIVE'
               AND (CAST(:excludedId AS uuid) IS NULL OR service_schedule_id <> :excludedId)
               AND int4range(
                     (extract(epoch FROM opening_time) / 60)::integer,
                     (extract(epoch FROM closing_time) / 60)::integer,
                     '[)'
                   ) && int4range(
                     (extract(epoch FROM CAST(:openingTime AS time)) / 60)::integer,
                     (extract(epoch FROM CAST(:closingTime AS time)) / 60)::integer,
                     '[)'
                   )
            """.formatted(COLUMNS))
        .param("calendarId", calendarId)
        .param("dayOfWeek", dayOfWeek)
        .param("openingTime", openingTime)
        .param("closingTime", closingTime)
        .param("excludedId", excludedId)
        .query(ScheduleRowMapper.INSTANCE)
        .list();
  }

  @Override
  public long countActiveByCalendarId(UUID calendarId) {
    return jdbc.sql(
            "SELECT count(*) FROM service_schedules WHERE service_calendar_id = :calendarId AND status = 'ACTIVE'")
        .param("calendarId", calendarId)
        .query(Long.class)
        .single();
  }

  @Override
  public void insert(ServiceSchedule schedule) {
    jdbc.sql(
            """
            INSERT INTO service_schedules (
              service_schedule_id, service_calendar_id, day_of_week, opening_time, closing_time,
              slot_duration_minutes, status, version, created_by_account_id, updated_by_account_id,
              created_at, updated_at
            ) VALUES (
              :id, :calendarId, :dayOfWeek, :openingTime, :closingTime, :slotDuration,
              CAST(:status AS service_schedule_status), :version, :createdBy, :updatedBy, :createdAt, :updatedAt
            )
            """)
        .param("id", schedule.id())
        .param("calendarId", schedule.serviceCalendarId())
        .param("dayOfWeek", schedule.dayOfWeek())
        .param("openingTime", schedule.timeWindow().opening())
        .param("closingTime", schedule.timeWindow().closing())
        .param("slotDuration", schedule.timeWindow().slotDurationMinutes())
        .param("status", schedule.status().name())
        .param("version", schedule.version())
        .param("createdBy", schedule.createdByAccountId())
        .param("updatedBy", schedule.updatedByAccountId())
        .param("createdAt", Timestamps.toTimestamp(schedule.createdAt()))
        .param("updatedAt", Timestamps.toTimestamp(schedule.updatedAt()))
        .update();
  }

  @Override
  public int update(ServiceSchedule schedule, int expectedVersion) {
    OptimisticVersionGuard.requireNext(expectedVersion, schedule.version());
    return jdbc.sql(
            """
            UPDATE service_schedules SET
              day_of_week = :dayOfWeek, opening_time = :openingTime, closing_time = :closingTime,
              slot_duration_minutes = :slotDuration, status = CAST(:status AS service_schedule_status),
              version = version + 1, updated_by_account_id = :updatedBy, updated_at = :updatedAt
             WHERE service_schedule_id = :id AND version = :expectedVersion
            """)
        .param("id", schedule.id())
        .param("dayOfWeek", schedule.dayOfWeek())
        .param("openingTime", schedule.timeWindow().opening())
        .param("closingTime", schedule.timeWindow().closing())
        .param("slotDuration", schedule.timeWindow().slotDurationMinutes())
        .param("status", schedule.status().name())
        .param("updatedBy", schedule.updatedByAccountId())
        .param("updatedAt", Timestamps.toTimestamp(schedule.updatedAt()))
        .param("expectedVersion", expectedVersion)
        .update();
  }
}
