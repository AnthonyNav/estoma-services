package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.ServiceCalendarRepository;
import com.estoma.washadministration.domain.calendar.ServiceCalendar;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcServiceCalendarRepository implements ServiceCalendarRepository {

  private static final String COLUMNS =
      "service_calendar_id, code, name, timezone, effective_from, effective_until, status, version, "
          + "created_by_account_id, updated_by_account_id, created_at, updated_at";
  private final JdbcClient jdbc;

  JdbcServiceCalendarRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ServiceCalendar> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM service_calendars WHERE service_calendar_id = :id")
        .param("id", id)
        .query(CalendarRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<ServiceCalendar> findByCode(String code) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM service_calendars WHERE code = :code")
        .param("code", code)
        .query(CalendarRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public List<ServiceCalendar> findActiveOverlapping(EffectivePeriod period, UUID excludedId) {
    return jdbc.sql(
            """
            SELECT %s FROM service_calendars
             WHERE status = 'ACTIVE'
               AND service_calendar_id <> COALESCE(:excludedId, '00000000-0000-0000-0000-000000000000'::uuid)
               AND daterange(effective_from, effective_until, '[]')
                   && daterange(CAST(:effectiveFrom AS date), CAST(:effectiveUntil AS date), '[]')
            """.formatted(COLUMNS))
        .param("excludedId", excludedId == null ? UUID.randomUUID() : excludedId)
        .param("effectiveFrom", period.from())
        .param("effectiveUntil", period.until())
        .query(CalendarRowMapper.INSTANCE)
        .list();
  }

  @Override
  public void insert(ServiceCalendar calendar) {
    jdbc.sql(
            """
            INSERT INTO service_calendars (
              service_calendar_id, code, name, timezone, effective_from, effective_until,
              status, version, created_by_account_id, updated_by_account_id, created_at, updated_at
            ) VALUES (
              :id, :code, :name, :timezone, :effectiveFrom, :effectiveUntil,
              CAST(:status AS service_calendar_status), :version, :createdBy, :updatedBy, :createdAt, :updatedAt
            )
            """)
        .param("id", calendar.id())
        .param("code", calendar.code().value())
        .param("name", calendar.name().value())
        .param("timezone", calendar.timezone().value())
        .param("effectiveFrom", calendar.effectivePeriod().from())
        .param("effectiveUntil", calendar.effectivePeriod().until())
        .param("status", calendar.status().name())
        .param("version", calendar.version())
        .param("createdBy", calendar.createdByAccountId())
        .param("updatedBy", calendar.updatedByAccountId())
        .param("createdAt", Timestamps.toTimestamp(calendar.createdAt()))
        .param("updatedAt", Timestamps.toTimestamp(calendar.updatedAt()))
        .update();
  }

  @Override
  public int update(ServiceCalendar calendar, int expectedVersion) {
    return jdbc.sql(
            """
            UPDATE service_calendars SET
              name = :name, timezone = :timezone, effective_from = :effectiveFrom,
              effective_until = :effectiveUntil, status = CAST(:status AS service_calendar_status),
              version = :newVersion, updated_by_account_id = :updatedBy, updated_at = :updatedAt
             WHERE service_calendar_id = :id AND version = :expectedVersion
            """)
        .param("id", calendar.id())
        .param("name", calendar.name().value())
        .param("timezone", calendar.timezone().value())
        .param("effectiveFrom", calendar.effectivePeriod().from())
        .param("effectiveUntil", calendar.effectivePeriod().until())
        .param("status", calendar.status().name())
        .param("newVersion", calendar.version())
        .param("updatedBy", calendar.updatedByAccountId())
        .param("updatedAt", Timestamps.toTimestamp(calendar.updatedAt()))
        .param("expectedVersion", expectedVersion)
        .update();
  }
}
