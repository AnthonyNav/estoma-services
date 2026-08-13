package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.domain.calendar.ServiceCalendar;
import com.estoma.washadministration.domain.calendar.ServiceCalendarStatus;
import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import com.estoma.washadministration.domain.shared.ResourceCode;
import com.estoma.washadministration.domain.shared.TimeZoneId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class CalendarRowMapper implements RowMapper<ServiceCalendar> {

  static final CalendarRowMapper INSTANCE = new CalendarRowMapper();

  private CalendarRowMapper() {}

  @Override
  public ServiceCalendar mapRow(ResultSet rs, int rowNum) throws SQLException {
    LocalDate effectiveUntil = rs.getObject("effective_until", LocalDate.class);
    return ServiceCalendar.reconstitute(
        rs.getObject("service_calendar_id", UUID.class),
        new ResourceCode(rs.getString("code")),
        new DisplayName(rs.getString("name")),
        new TimeZoneId(rs.getString("timezone")),
        new EffectivePeriod(rs.getObject("effective_from", LocalDate.class), effectiveUntil),
        ServiceCalendarStatus.valueOf(rs.getString("status")),
        rs.getInt("version"),
        rs.getObject("created_by_account_id", UUID.class),
        rs.getObject("updated_by_account_id", UUID.class),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}
