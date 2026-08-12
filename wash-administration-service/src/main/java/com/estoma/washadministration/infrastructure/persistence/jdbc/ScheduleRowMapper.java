package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.domain.schedule.ServiceSchedule;
import com.estoma.washadministration.domain.schedule.ServiceScheduleStatus;
import com.estoma.washadministration.domain.shared.TimeWindow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class ScheduleRowMapper implements RowMapper<ServiceSchedule> {

  static final ScheduleRowMapper INSTANCE = new ScheduleRowMapper();

  private ScheduleRowMapper() {}

  @Override
  public ServiceSchedule mapRow(ResultSet rs, int rowNum) throws SQLException {
    return ServiceSchedule.reconstitute(
        rs.getObject("service_schedule_id", UUID.class),
        rs.getObject("service_calendar_id", UUID.class),
        rs.getInt("day_of_week"),
        new TimeWindow(
            rs.getObject("opening_time", LocalTime.class),
            rs.getObject("closing_time", LocalTime.class),
            rs.getInt("slot_duration_minutes")),
        ServiceScheduleStatus.valueOf(rs.getString("status")),
        rs.getInt("version"),
        rs.getObject("created_by_account_id", UUID.class),
        rs.getObject("updated_by_account_id", UUID.class),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}
