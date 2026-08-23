package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashAdministrationProjectionRepository implements WashAdministrationProjectionRepository {
  private static final String CLOSURE_COLUMNS = "closure_id,calendar_id,scope,cabin_id,tank_id,starts_at,ends_at,status,source_version,updated_at";
  private final JdbcClient jdbc;
  public JdbcWashAdministrationProjectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public void upsertServiceCalendar(ServiceCalendarProjection p) {
    jdbc.sql("INSERT INTO service_calendar_projection (calendar_id,code,timezone,effective_from,effective_until,status,source_version,updated_at) VALUES (:id,:code,:timezone,:from,:until,:status,:version,:updated) ON CONFLICT (calendar_id) DO UPDATE SET code=EXCLUDED.code,timezone=EXCLUDED.timezone,effective_from=EXCLUDED.effective_from,effective_until=EXCLUDED.effective_until,status=EXCLUDED.status,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE service_calendar_projection.source_version < EXCLUDED.source_version")
      .param("id",p.calendarId()).param("code",p.code()).param("timezone",p.timezone())
      .param("from",p.effectiveFrom()).param("until",p.effectiveUntil()).param("status",p.status())
      .param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
  }

  @Override public void upsertServiceSchedule(ServiceScheduleProjection p) {
    jdbc.sql("INSERT INTO service_schedule_projection (schedule_id,calendar_id,day_of_week,opening_time,closing_time,slot_duration_minutes,status,source_version,updated_at) VALUES (:id,:calendar,:day,:open,:close,:duration,:status,:version,:updated) ON CONFLICT (schedule_id) DO UPDATE SET calendar_id=EXCLUDED.calendar_id,day_of_week=EXCLUDED.day_of_week,opening_time=EXCLUDED.opening_time,closing_time=EXCLUDED.closing_time,slot_duration_minutes=EXCLUDED.slot_duration_minutes,status=EXCLUDED.status,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE service_schedule_projection.source_version < EXCLUDED.source_version")
      .param("id",p.scheduleId()).param("calendar",p.calendarId()).param("day",p.dayOfWeek())
      .param("open",p.openingTime()).param("close",p.closingTime()).param("duration",p.slotDurationMinutes())
      .param("status",p.status()).param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
  }

  @Override public void upsertAdministrativeClosure(AdministrativeClosureProjection p) {
    jdbc.sql("INSERT INTO administrative_closure_projection (closure_id,calendar_id,scope,cabin_id,tank_id,starts_at,ends_at,status,source_version,updated_at) VALUES (:id,:calendar,:scope,:cabin,:tank,:starts,:ends,:status,:version,:updated) ON CONFLICT (closure_id) DO UPDATE SET calendar_id=EXCLUDED.calendar_id,scope=EXCLUDED.scope,cabin_id=EXCLUDED.cabin_id,tank_id=EXCLUDED.tank_id,starts_at=EXCLUDED.starts_at,ends_at=EXCLUDED.ends_at,status=EXCLUDED.status,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE administrative_closure_projection.source_version < EXCLUDED.source_version")
      .param("id",p.closureId()).param("calendar",p.calendarId()).param("scope",p.scope())
      .param("cabin",p.cabinId()).param("tank",p.tankId()).param("starts",Timestamps.toTimestamp(p.startsAt()))
      .param("ends",Timestamps.toTimestamp(p.endsAt())).param("status",p.status())
      .param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
  }

  @Override public void upsertCabin(CabinProjection p) {
    jdbc.sql("INSERT INTO cabin_projection (cabin_id,cabin_type,administrative_status,source_version,updated_at) VALUES (:id,:type,:status,:version,:updated) ON CONFLICT (cabin_id) DO UPDATE SET cabin_type=EXCLUDED.cabin_type,administrative_status=EXCLUDED.administrative_status,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE cabin_projection.source_version < EXCLUDED.source_version")
      .param("id",p.cabinId()).param("type",p.cabinType()).param("status",p.administrativeStatus())
      .param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
  }

  @Override public void upsertTank(TankProjection p) {
    jdbc.sql("INSERT INTO tank_projection (tank_id,cabin_id,configured_capacity,administrative_status,source_version,updated_at) VALUES (:id,:cabin,:capacity,:status,:version,:updated) ON CONFLICT (tank_id) DO UPDATE SET cabin_id=EXCLUDED.cabin_id,configured_capacity=EXCLUDED.configured_capacity,administrative_status=EXCLUDED.administrative_status,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE tank_projection.source_version < EXCLUDED.source_version")
      .param("id",p.tankId()).param("cabin",p.cabinId()).param("capacity",p.configuredCapacity())
      .param("status",p.administrativeStatus()).param("version",p.sourceVersion())
      .param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
  }

  @Override public void upsertResourcePriority(ResourcePriorityProjection p) {
    jdbc.sql("INSERT INTO resource_priority_projection (resource_priority_id,appointment_type,cabin_type,usage_mode,priority_order,status,source_version,updated_at) VALUES (:id,:appointmentType,:cabinType,:usageMode,:priority,:status,:version,:updated) ON CONFLICT (resource_priority_id) DO UPDATE SET appointment_type=EXCLUDED.appointment_type,cabin_type=EXCLUDED.cabin_type,usage_mode=EXCLUDED.usage_mode,priority_order=EXCLUDED.priority_order,status=EXCLUDED.status,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE resource_priority_projection.source_version < EXCLUDED.source_version")
      .param("id",p.resourcePriorityId()).param("appointmentType",p.appointmentType())
      .param("cabinType",p.cabinType()).param("usageMode",p.usageMode()).param("priority",p.priorityOrder())
      .param("status",p.status()).param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
  }

  @Override public Optional<ServiceCalendarProjection> findCalendar(UUID id) {
    return jdbc.sql("SELECT calendar_id,code,timezone,effective_from,effective_until,status,source_version,updated_at FROM service_calendar_projection WHERE calendar_id=:id")
      .param("id",id).query((rs,row)->new ServiceCalendarProjection(rs.getObject("calendar_id",UUID.class),rs.getString("code"),rs.getString("timezone"),rs.getObject("effective_from",LocalDate.class),rs.getObject("effective_until",LocalDate.class),rs.getString("status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"))).optional();
  }

  @Override public Optional<ServiceScheduleProjection> findSchedule(UUID id) {
    return jdbc.sql("SELECT schedule_id,calendar_id,day_of_week,opening_time,closing_time,slot_duration_minutes,status,source_version,updated_at FROM service_schedule_projection WHERE schedule_id=:id")
      .param("id",id).query((rs,row)->new ServiceScheduleProjection(rs.getObject("schedule_id",UUID.class),rs.getObject("calendar_id",UUID.class),rs.getInt("day_of_week"),rs.getObject("opening_time",LocalTime.class),rs.getObject("closing_time",LocalTime.class),rs.getInt("slot_duration_minutes"),rs.getString("status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"))).optional();
  }

  @Override public Optional<AdministrativeClosureProjection> findClosure(UUID id) {
    return jdbc.sql("SELECT " + CLOSURE_COLUMNS + " FROM administrative_closure_projection WHERE closure_id=:id")
      .param("id", id).query(JdbcWashAdministrationProjectionRepository::mapClosure).optional();
  }

  @Override public List<AdministrativeClosureProjection> findClosuresOverlapping(UUID calendarId, Instant startsAt, Instant endsAt) {
    return jdbc.sql("SELECT " + CLOSURE_COLUMNS + " FROM administrative_closure_projection WHERE calendar_id=:calendar AND status='SCHEDULED' AND starts_at < :ends AND ends_at > :starts")
      .param("calendar",calendarId).param("starts",Timestamps.toTimestamp(startsAt)).param("ends",Timestamps.toTimestamp(endsAt))
      .query(JdbcWashAdministrationProjectionRepository::mapClosure).list();
  }

  @Override public List<CabinProjection> findCabins() {
    return jdbc.sql("SELECT cabin_id,cabin_type,administrative_status,source_version,updated_at FROM cabin_projection")
      .query((rs,row)->new CabinProjection(rs.getObject("cabin_id",UUID.class),rs.getString("cabin_type"),rs.getString("administrative_status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"))).list();
  }

  @Override public List<TankProjection> findTanks() {
    return jdbc.sql("SELECT tank_id,cabin_id,configured_capacity,administrative_status,source_version,updated_at FROM tank_projection")
      .query((rs,row)->new TankProjection(rs.getObject("tank_id",UUID.class),rs.getObject("cabin_id",UUID.class),rs.getInt("configured_capacity"),rs.getString("administrative_status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"))).list();
  }

  @Override public List<ResourcePriorityProjection> findActivePriorities() {
    return jdbc.sql("SELECT resource_priority_id,appointment_type,cabin_type,usage_mode,priority_order,status,source_version,updated_at FROM resource_priority_projection WHERE status='ACTIVE' ORDER BY priority_order")
      .query((rs,row)->new ResourcePriorityProjection(rs.getObject("resource_priority_id",UUID.class),rs.getString("appointment_type"),rs.getString("cabin_type"),rs.getString("usage_mode"),rs.getInt("priority_order"),rs.getString("status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"))).list();
  }

  @Override public boolean reliable() {
    return jdbc.sql("SELECT COALESCE(bool_and(reliable),true) FROM (SELECT reliable FROM service_calendar_projection UNION ALL SELECT reliable FROM service_schedule_projection UNION ALL SELECT reliable FROM administrative_closure_projection UNION ALL SELECT reliable FROM cabin_projection UNION ALL SELECT reliable FROM tank_projection UNION ALL SELECT reliable FROM resource_priority_projection) p")
      .query(Boolean.class).single();
  }

  private static AdministrativeClosureProjection mapClosure(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new AdministrativeClosureProjection(rs.getObject("closure_id",UUID.class),
        rs.getObject("calendar_id",UUID.class), rs.getString("scope"), rs.getObject("cabin_id",UUID.class),
        rs.getObject("tank_id",UUID.class), Timestamps.toInstant(rs,"starts_at"),
        Timestamps.toInstant(rs,"ends_at"), rs.getString("status"), rs.getLong("source_version"),
        Timestamps.toInstant(rs,"updated_at"));
  }
}
