package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.AuthorizationRecoveryPort;
import com.estoma.platformbff.application.port.AccountPresentationRecoveryPort;
import com.estoma.platformbff.application.port.PersonPresentationRecoveryPort;
import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.port.ProjectionReadModelPort;
import com.estoma.platformbff.application.port.SystemCatalogRecoveryPort;
import com.estoma.platformbff.application.projection.ProjectionReadModel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC adapter for the typed PR4 read models. Fencing is local to each row/stream. */
@Repository
public class JdbcProjectionReadModelRepository implements ProjectionReadModelPort, AuthorizationRecoveryPort,
    AccountPresentationRecoveryPort, PersonPresentationRecoveryPort, SystemCatalogRecoveryPort, ProjectionQueryPort {
  private final JdbcClient jdbc;

  public JdbcProjectionReadModelRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void upsert(ProjectionReadModel model) {
    switch (model) {
      case ProjectionReadModel.Person value -> person(value);
      case ProjectionReadModel.AccountPresentation value -> accountPresentation(value);
      case ProjectionReadModel.AuthorizationRole value -> role(value);
      case ProjectionReadModel.AuthorizationAccess value -> access(value);
      case ProjectionReadModel.SystemCatalog value -> systemCatalog(value);
      case ProjectionReadModel.AcademicPeriod value -> academicPeriod(value);
      case ProjectionReadModel.StudentProfile value -> studentProfile(value);
      case ProjectionReadModel.StudentEligibility value -> eligibility(value);
      case ProjectionReadModel.CourseSection value -> courseSection(value);
      case ProjectionReadModel.Enrollment value -> enrollment(value);
      case ProjectionReadModel.StudentImport value -> studentImport(value);
      case ProjectionReadModel.ValidationError value -> validationError(value);
      case ProjectionReadModel.MailboxMessage value -> mailboxMessage(value);
      case ProjectionReadModel.MailboxVisit value -> mailboxVisit(value);
      case ProjectionReadModel.WashCalendar value -> washCalendar(value);
      case ProjectionReadModel.WashSchedule value -> washSchedule(value);
      case ProjectionReadModel.AdministrativeClosure value -> closure(value);
      case ProjectionReadModel.Cabin value -> cabin(value);
      case ProjectionReadModel.Tank value -> tank(value);
      case ProjectionReadModel.AppointmentSlot value -> slot(value);
      case ProjectionReadModel.Appointment value -> appointment(value);
      case ProjectionReadModel.ExceptionalAuthorization value -> exceptional(value);
      case ProjectionReadModel.WashExecution value -> execution(value);
      case ProjectionReadModel.ResourceAssignment value -> assignment(value);
      case ProjectionReadModel.ResourceUnavailability value -> unavailability(value);
      case ProjectionReadModel.CleaningPeriod value -> cleaning(value);
      case ProjectionReadModel.Jornada value -> jornada(value);
      case ProjectionReadModel.Registro value -> registro(value);
      case ProjectionReadModel.TipoJornada value -> tipoJornada(value);
    }
  }

  @Override
  public void clearAuthorizationProjection() {
    jdbc.sql("DELETE FROM authorization_access_projection").update();
    jdbc.sql("DELETE FROM authorization_role_projection").update();
  }

  @Override
  public boolean hasAuthorizationState(java.util.UUID accountId) {
    return jdbc.sql("""
        SELECT EXISTS(
          SELECT 1 FROM authorization_role_projection WHERE account_id = :accountId
          UNION ALL
          SELECT 1 FROM authorization_access_projection WHERE account_id = :accountId)
        """).param("accountId", accountId).query(Boolean.class).single();
  }

  @Override
  public void upsertSnapshot(ProjectionReadModel.AuthorizationRole role,
      java.util.List<ProjectionReadModel.AuthorizationAccess> accesses) {
    if (role != null) role(role);
    for (var access : accesses) access(access);
  }

  @Override
  public void clearAccountPresentationProjection() {
    jdbc.sql("DELETE FROM account_presentation_read_model").update();
  }

  @Override
  public boolean hasAccountPresentation(UUID accountId) {
    return jdbc.sql("SELECT EXISTS(SELECT 1 FROM account_presentation_read_model WHERE account_id = :accountId)")
        .param("accountId", accountId).query(Boolean.class).single();
  }

  @Override
  public void upsertAccountPresentationSnapshot(ProjectionReadModel.AccountPresentation presentation) {
    accountPresentation(presentation);
  }

  @Override
  public void clearPersonPresentationProjection() {
    jdbc.sql("DELETE FROM person_presentation_read_model").update();
  }

  @Override
  public void upsertPersonPresentationSnapshot(ProjectionReadModel.Person presentation) {
    person(presentation);
  }

  @Override
  public void clearSystemCatalogProjection() {
    jdbc.sql("DELETE FROM authorization_system_catalog_read_model").update();
  }

  @Override
  public void markSystemCatalogUntrusted(String systemCode) {
    jdbc.sql("""
        UPDATE authorization_system_catalog_read_model
        SET trusted = FALSE
        WHERE system_code = :systemCode
        """).param("systemCode", systemCode).update();
  }

  @Override
  public void upsertSystemCatalogSnapshot(ProjectionReadModel.SystemCatalog catalog) {
    systemCatalog(catalog);
  }

  @Override
  public MailboxPage findVisibleMailboxMessages(UUID recipientAccountId, String systemCode, int limit,
      MailboxCursor cursor) {
    String cursorPredicate = cursor == null ? "" : """
          AND (created_at < :cursorCreatedAt
               OR (created_at = :cursorCreatedAt AND inbox_message_id > :cursorMessageId))
        """;
    var statement = jdbc.sql("""
        SELECT inbox_message_id, communication_id, system_code, communication_type, title, body,
               created_at, expires_at
        FROM mailbox_messages_read_model
        WHERE recipient_account_id = :accountId
          AND system_code = :systemCode
          AND communication_id IS NOT NULL
          AND expired_at IS NULL
        """ + cursorPredicate + """
        ORDER BY created_at DESC, inbox_message_id ASC
        LIMIT :limit
        """)
        .param("accountId", recipientAccountId).param("systemCode", systemCode)
        .param("limit", limit + 1);
    if (cursor != null) {
      statement.param("cursorCreatedAt", JdbcTimes.timestamp(cursor.createdAt()))
          .param("cursorMessageId", cursor.inboxMessageId());
    }
    List<MailboxMessage> rows = statement
        .query((rs, rowNum) -> new MailboxMessage(
            uuid(rs, "inbox_message_id"), uuid(rs, "communication_id"), rs.getString("system_code"),
            rs.getString("communication_type"), rs.getString("title"), rs.getString("body"),
            instant(rs, "created_at"), instant(rs, "expires_at")))
        .list();
    boolean hasMore = rows.size() > limit;
    List<MailboxMessage> items = hasMore ? rows.subList(0, limit) : rows;
    MailboxCursor next = hasMore && !items.isEmpty()
        ? new MailboxCursor(items.getLast().createdAt(), items.getLast().inboxMessageId()) : null;
    return new MailboxPage(List.copyOf(items), next);
  }

  @Override
  public MailboxSummary summarizeMailbox(UUID recipientAccountId, String systemCode) {
    return jdbc.sql("""
        SELECT :systemCode AS system_code,
               COUNT(m.inbox_message_id) AS message_count,
               COALESCE(SUM(CASE WHEN
                 (SELECT last_visited_at FROM mailbox_visits_read_model
                  WHERE account_id = :accountId AND system_code = :systemCode) IS NULL
                 OR m.created_at > (SELECT last_visited_at FROM mailbox_visits_read_model
                                    WHERE account_id = :accountId AND system_code = :systemCode)
                 THEN 1 ELSE 0 END), 0) AS new_message_count,
               (SELECT last_visited_at FROM mailbox_visits_read_model
                WHERE account_id = :accountId AND system_code = :systemCode) AS last_visited_at
        FROM mailbox_messages_read_model m
        WHERE m.recipient_account_id = :accountId
          AND m.system_code = :systemCode
          AND m.communication_id IS NOT NULL
          AND m.expired_at IS NULL
        """)
        .param("accountId", recipientAccountId).param("systemCode", systemCode)
        .query((rs, rowNum) -> new MailboxSummary(
            rs.getString("system_code"), rs.getLong("message_count"),
            rs.getLong("new_message_count"), instant(rs, "last_visited_at")))
        .single();
  }

  @Override
  public Optional<PersonPresentation> findPersonPresentation(UUID personId) {
    return jdbc.sql("""
        SELECT person_id, first_name, paternal_surname, maternal_surname, institutional_email, status
        FROM person_presentation_read_model WHERE person_id = :personId
        """).param("personId", personId)
        .query((rs, rowNum) -> new PersonPresentation(uuid(rs, "person_id"), rs.getString("first_name"),
            rs.getString("paternal_surname"), rs.getString("maternal_surname"),
            rs.getString("institutional_email"), rs.getString("status")))
        .optional();
  }

  @Override
  public Optional<AccountPresentation> findAccountPresentation(UUID accountId) {
    return jdbc.sql("""
        SELECT account_id, person_id, login_type, login_identifier, account_status
        FROM account_presentation_read_model WHERE account_id = :accountId
        """).param("accountId", accountId)
        .query((rs, rowNum) -> new AccountPresentation(uuid(rs, "account_id"), uuid(rs, "person_id"),
            rs.getString("login_type"), rs.getString("login_identifier"), rs.getString("account_status")))
        .optional();
  }

  @Override
  public List<String> findActiveSystemCandidates(UUID accountId) {
    return jdbc.sql("""
        SELECT catalog.system_code
        FROM authorization_access_projection access
        JOIN authorization_system_catalog_read_model catalog
          ON catalog.system_id = access.system_id AND catalog.system_code = access.system_code
        WHERE access.account_id = :accountId
          AND access.status = 'ACTIVE'
          AND catalog.status = 'ACTIVE'
          AND catalog.trusted = TRUE
        ORDER BY CASE catalog.system_code
          WHEN 'LAVADO_ULTRASONICO' THEN 1
          WHEN 'PRACTICAS_PROFESIONALES' THEN 2
          ELSE 3 END, catalog.system_code ASC
        """).param("accountId", accountId).query(String.class).list();
  }

  @Override
  public boolean hasEligibleAcademicSystem(UUID accountId, String systemCode) {
    return jdbc.sql("""
        SELECT EXISTS(
          SELECT 1
          FROM student_academic_profile_read_model profile
          JOIN student_academic_eligibility_read_model eligibility
            ON eligibility.student_academic_profile_id = profile.student_academic_profile_id
          WHERE profile.account_id = :accountId
            AND eligibility.system_code = :systemCode
            AND eligibility.eligible = TRUE
            AND eligibility.policy_version = '1')
        """).param("accountId", accountId).param("systemCode", systemCode)
        .query(Boolean.class).single();
  }

  @Override
  public Optional<String> findRoleCode(UUID accountId) {
    return jdbc.sql("""
        SELECT role_code FROM authorization_role_projection WHERE account_id = :accountId
        """)
        .param("accountId", accountId)
        .query(String.class)
        .optional();
  }

  @Override
  public boolean hasActiveSystemAccess(UUID accountId, String systemCode) {
    return jdbc.sql("""
        SELECT EXISTS(
          SELECT 1 FROM authorization_access_projection
          WHERE account_id = :accountId AND system_code = :systemCode AND status = 'ACTIVE')
        """)
        .param("accountId", accountId)
        .param("systemCode", systemCode)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<UUID> findAppointmentStudentAccountId(UUID appointmentId) {
    return jdbc.sql("""
        SELECT student_account_id FROM appointment_read_model WHERE appointment_id = :appointmentId
        """)
        .param("appointmentId", appointmentId)
        .query(UUID.class)
        .optional();
  }

  @Override
  public Optional<UUID> findWashExecutionStudentAccountId(UUID washExecutionId) {
    return jdbc.sql("""
        SELECT a.student_account_id
        FROM wash_execution_read_model e
        JOIN appointment_read_model a ON a.appointment_id = e.appointment_id
        WHERE e.wash_execution_id = :washExecutionId
        """)
        .param("washExecutionId", washExecutionId)
        .query(UUID.class)
        .optional();
  }

  @Override
  public List<PendingReassignment> findPendingReassignments() {
    return jdbc.sql("""
        SELECT e.wash_execution_id, e.status AS execution_status, e.authorized_at,
               e.execution_version,
               a.appointment_id, a.appointment_time_slot_id, a.appointment_type,
               a.status AS appointment_status, a.course_section_id,
               c.nrc AS course_nrc, c.name AS course_name,
               a.student_account_id,
               sp.person_id, p.first_name, p.paternal_surname, p.maternal_surname,
               sp.student_number, sp.current_semester, sp.academic_status,
               ra.resource_assignment_id, ra.assignment_type, ra.assigned_at,
               ra.cabin_id, cabin.code AS cabin_code, cabin.name AS cabin_name,
               ra.tank_id, tank.code AS tank_code, tank.name AS tank_name
        FROM wash_execution_read_model e
        LEFT JOIN appointment_read_model a ON a.appointment_id = e.appointment_id
        LEFT JOIN student_academic_profile_read_model sp ON sp.account_id = a.student_account_id
        LEFT JOIN person_presentation_read_model p ON p.person_id = sp.person_id
        LEFT JOIN course_section_read_model c ON c.course_section_id = a.course_section_id
        LEFT JOIN LATERAL (
          SELECT resource_assignment_id, assignment_type, assigned_at, cabin_id, tank_id
          FROM wash_resource_assignment_read_model
          WHERE wash_execution_id = e.wash_execution_id AND released_at IS NULL
          ORDER BY assigned_at DESC NULLS LAST, resource_assignment_id ASC
          LIMIT 1
        ) ra ON TRUE
        LEFT JOIN wash_cabin_read_model cabin ON cabin.cabin_id = ra.cabin_id
        LEFT JOIN wash_tank_read_model tank ON tank.tank_id = ra.tank_id
        WHERE e.status = 'PENDING_REASSIGNMENT'
        ORDER BY e.authorized_at ASC NULLS LAST, e.wash_execution_id ASC
        """)
        .query((rs, rowNum) -> pendingReassignment(rs))
        .list();
  }

  private static PendingReassignment pendingReassignment(ResultSet rs) throws SQLException {
    return new PendingReassignment(
        uuid(rs, "wash_execution_id"), rs.getString("execution_status"),
        instant(rs, "authorized_at"), rs.getLong("execution_version"),
        uuid(rs, "appointment_id"), uuid(rs, "appointment_time_slot_id"),
        rs.getString("appointment_type"), rs.getString("appointment_status"),
        uuid(rs, "course_section_id"), rs.getString("course_nrc"), rs.getString("course_name"),
        uuid(rs, "student_account_id"), uuid(rs, "person_id"), rs.getString("first_name"),
        rs.getString("paternal_surname"), rs.getString("maternal_surname"),
        rs.getString("student_number"), integer(rs, "current_semester"), rs.getString("academic_status"),
        uuid(rs, "resource_assignment_id"), rs.getString("assignment_type"), instant(rs, "assigned_at"),
        uuid(rs, "cabin_id"), rs.getString("cabin_code"), rs.getString("cabin_name"),
        uuid(rs, "tank_id"), rs.getString("tank_code"), rs.getString("tank_name"));
  }

  private static UUID uuid(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, UUID.class);
  }

  private static Integer integer(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, Integer.class);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return JdbcTimes.instant(rs, column);
  }

  private void person(ProjectionReadModel.Person v) {
    jdbc.sql("""
        INSERT INTO person_presentation_read_model
          (person_id, first_name, paternal_surname, maternal_surname, institutional_email,
           status, source_version, updated_at)
        VALUES (:id, :firstName, :paternalSurname, :maternalSurname, :email, :status, :version, :updatedAt)
        ON CONFLICT (person_id) DO UPDATE SET
          first_name = COALESCE(EXCLUDED.first_name, person_presentation_read_model.first_name),
          paternal_surname = COALESCE(EXCLUDED.paternal_surname, person_presentation_read_model.paternal_surname),
          maternal_surname = COALESCE(EXCLUDED.maternal_surname, person_presentation_read_model.maternal_surname),
          institutional_email = CASE WHEN :emailAuthoritative THEN EXCLUDED.institutional_email
                                     ELSE person_presentation_read_model.institutional_email END,
          status = COALESCE(EXCLUDED.status, person_presentation_read_model.status),
          source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE person_presentation_read_model.source_version <= EXCLUDED.source_version
        """)
        .param("id", v.personId()).param("firstName", v.firstName()).param("paternalSurname", v.paternalSurname())
        .param("maternalSurname", v.maternalSurname()).param("email", v.institutionalEmail())
        .param("emailAuthoritative", v.institutionalEmailAuthoritative()).param("status", v.status())
        .param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void accountPresentation(ProjectionReadModel.AccountPresentation v) {
    jdbc.sql("""
        INSERT INTO account_presentation_read_model
          (account_id, person_id, login_type, login_identifier, account_status, source_version, updated_at)
        VALUES (:accountId, :personId, :loginType, :loginIdentifier, :accountStatus, :version, :updatedAt)
        ON CONFLICT (account_id) DO UPDATE SET
          person_id = EXCLUDED.person_id, login_type = EXCLUDED.login_type,
          login_identifier = EXCLUDED.login_identifier, account_status = EXCLUDED.account_status,
          source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE account_presentation_read_model.source_version <= EXCLUDED.source_version
        """).param("accountId", v.accountId()).param("personId", v.personId())
        .param("loginType", v.loginType()).param("loginIdentifier", v.loginIdentifier())
        .param("accountStatus", v.accountStatus()).param("version", v.sourceVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void systemCatalog(ProjectionReadModel.SystemCatalog v) {
    jdbc.sql("""
        INSERT INTO authorization_system_catalog_read_model
          (system_id, system_code, status, source_version, trusted, updated_at)
        VALUES (:systemId, :systemCode, :status, :version, :trusted, :updatedAt)
        ON CONFLICT (system_code) DO UPDATE SET
          system_id = EXCLUDED.system_id, status = EXCLUDED.status,
          source_version = EXCLUDED.source_version, trusted = EXCLUDED.trusted,
          updated_at = EXCLUDED.updated_at
        WHERE authorization_system_catalog_read_model.source_version <= EXCLUDED.source_version
        """).param("systemId", v.systemId()).param("systemCode", v.systemCode()).param("status", v.status())
        .param("version", v.sourceVersion()).param("trusted", v.trusted())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void role(ProjectionReadModel.AuthorizationRole v) {
    jdbc.sql("""
        INSERT INTO authorization_role_projection(account_id, role_code, role_source_version, updated_at)
        VALUES (:accountId, :roleCode, :version, :updatedAt)
        ON CONFLICT (account_id) DO UPDATE SET role_code = EXCLUDED.role_code,
          role_source_version = EXCLUDED.role_source_version, updated_at = EXCLUDED.updated_at
        WHERE authorization_role_projection.role_source_version IS NULL
           OR authorization_role_projection.role_source_version <= EXCLUDED.role_source_version
        """)
        .param("accountId", v.accountId()).param("roleCode", v.roleCode()).param("version", v.sourceVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void access(ProjectionReadModel.AuthorizationAccess v) {
    jdbc.sql("""
        INSERT INTO authorization_access_projection
          (system_access_id, account_id, system_id, system_code, status, access_source_version, updated_at)
        VALUES (:accessId, :accountId, :systemId, :systemCode, :status, :version, :updatedAt)
        ON CONFLICT (account_id, system_code) DO UPDATE SET
          system_access_id = EXCLUDED.system_access_id, system_id = EXCLUDED.system_id,
          status = EXCLUDED.status, access_source_version = EXCLUDED.access_source_version,
          updated_at = EXCLUDED.updated_at
        WHERE authorization_access_projection.access_source_version <= EXCLUDED.access_source_version
        """)
        .param("accessId", v.systemAccessId()).param("accountId", v.accountId()).param("systemId", v.systemId())
        .param("systemCode", v.systemCode()).param("status", v.status()).param("version", v.sourceVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void academicPeriod(ProjectionReadModel.AcademicPeriod v) {
    jdbc.sql("""
        INSERT INTO academic_period_read_model
          (academic_period_id, code, name, start_date, end_date, status, academic_data_status, source_version, updated_at)
        VALUES (:id, :code, :name, :startDate, :endDate, :status, :dataStatus, :version, :updatedAt)
        ON CONFLICT (academic_period_id) DO UPDATE SET code = COALESCE(EXCLUDED.code, academic_period_read_model.code),
          name = COALESCE(EXCLUDED.name, academic_period_read_model.name), start_date = COALESCE(EXCLUDED.start_date, academic_period_read_model.start_date),
          end_date = COALESCE(EXCLUDED.end_date, academic_period_read_model.end_date), status = COALESCE(EXCLUDED.status, academic_period_read_model.status),
          academic_data_status = COALESCE(EXCLUDED.academic_data_status, academic_period_read_model.academic_data_status),
          source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE academic_period_read_model.source_version <= EXCLUDED.source_version
        """)
        .param("id", v.academicPeriodId()).param("code", v.code()).param("name", v.name()).param("startDate", v.startDate())
        .param("endDate", v.endDate()).param("status", v.status()).param("dataStatus", v.academicDataStatus())
        .param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void studentProfile(ProjectionReadModel.StudentProfile v) {
    jdbc.sql("""
        INSERT INTO student_academic_profile_read_model
          (student_academic_profile_id, person_id, account_id, student_number, current_semester, academic_status, source_version, updated_at)
        VALUES (:id, :personId, :accountId, :studentNumber, :semester, :status, :version, :updatedAt)
        ON CONFLICT (student_academic_profile_id) DO UPDATE SET person_id = COALESCE(EXCLUDED.person_id, student_academic_profile_read_model.person_id),
          account_id = EXCLUDED.account_id, student_number = COALESCE(EXCLUDED.student_number, student_academic_profile_read_model.student_number),
          current_semester = COALESCE(EXCLUDED.current_semester, student_academic_profile_read_model.current_semester),
          academic_status = COALESCE(EXCLUDED.academic_status, student_academic_profile_read_model.academic_status),
          source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE student_academic_profile_read_model.source_version <= EXCLUDED.source_version
        """)
        .param("id", v.studentAcademicProfileId()).param("personId", v.personId()).param("accountId", v.accountId())
        .param("studentNumber", v.studentNumber()).param("semester", v.currentSemester()).param("status", v.academicStatus())
        .param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void eligibility(ProjectionReadModel.StudentEligibility v) {
    jdbc.sql("""
        INSERT INTO student_academic_eligibility_read_model
          (eligibility_id, student_academic_profile_id, system_code, eligible, policy_version,
           eligibility_version, academic_profile_version, evaluated_at, updated_at)
        VALUES (:id, :profileId, :systemCode, :eligible, :policyVersion, :version, :profileVersion, :evaluatedAt, :updatedAt)
        ON CONFLICT (student_academic_profile_id, system_code) DO UPDATE SET
          eligibility_id = EXCLUDED.eligibility_id, eligible = EXCLUDED.eligible,
          policy_version = EXCLUDED.policy_version, eligibility_version = EXCLUDED.eligibility_version,
          academic_profile_version = EXCLUDED.academic_profile_version, evaluated_at = EXCLUDED.evaluated_at,
          updated_at = EXCLUDED.updated_at
        WHERE student_academic_eligibility_read_model.eligibility_version <= EXCLUDED.eligibility_version
        """)
        .param("id", v.eligibilityId()).param("profileId", v.studentAcademicProfileId()).param("systemCode", v.systemCode())
        .param("eligible", v.eligible()).param("policyVersion", v.policyVersion()).param("version", v.eligibilityVersion())
        .param("profileVersion", v.academicProfileVersion()).param("evaluatedAt", JdbcTimes.timestamp(v.evaluatedAt()))
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void courseSection(ProjectionReadModel.CourseSection v) {
    jdbc.sql("""
        INSERT INTO course_section_read_model
          (course_section_id, academic_period_id, nrc, name, status, source_version, updated_at)
        VALUES (:id, :periodId, :nrc, :name, :status, :version, :updatedAt)
        ON CONFLICT (course_section_id) DO UPDATE SET academic_period_id = EXCLUDED.academic_period_id,
          nrc = COALESCE(EXCLUDED.nrc, course_section_read_model.nrc), name = COALESCE(EXCLUDED.name, course_section_read_model.name),
          status = COALESCE(EXCLUDED.status, course_section_read_model.status), source_version = EXCLUDED.source_version,
          updated_at = EXCLUDED.updated_at
        WHERE course_section_read_model.source_version <= EXCLUDED.source_version
        """)
        .param("id", v.courseSectionId()).param("periodId", v.academicPeriodId()).param("nrc", v.nrc()).param("name", v.name())
        .param("status", v.status()).param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void enrollment(ProjectionReadModel.Enrollment v) {
    jdbc.sql("""
        INSERT INTO student_enrollment_read_model
          (student_academic_profile_id, account_id, course_section_id, academic_period_id, nrc, status, source_version, updated_at)
        VALUES (:profileId, :accountId, :sectionId, :periodId, :nrc, :status, :version, :updatedAt)
        ON CONFLICT (student_academic_profile_id, course_section_id) DO UPDATE SET status = EXCLUDED.status,
          nrc = COALESCE(EXCLUDED.nrc, student_enrollment_read_model.nrc), source_version = EXCLUDED.source_version,
          updated_at = EXCLUDED.updated_at
        WHERE student_enrollment_read_model.source_version <= EXCLUDED.source_version
        """)
        .param("profileId", v.studentAcademicProfileId()).param("accountId", v.accountId()).param("sectionId", v.courseSectionId())
        .param("periodId", v.academicPeriodId()).param("nrc", v.nrc()).param("status", v.status()).param("version", v.sourceVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void studentImport(ProjectionReadModel.StudentImport v) {
    jdbc.sql("""
        INSERT INTO student_import_read_model
          (import_id, academic_period_id, status, started_by_account_id, authorized_by_account_id, started_at, validated_at,
           application_started_at, completed_at, failed_at, summary, application_progress, validation_error_count,
           execution_generation, last_resumed_by_account_id, last_resumed_at, failed_step, failure_reason, recoverable,
           process_version, updated_at)
        VALUES (:id, :periodId, :status, :startedBy, :authorizedBy, :startedAt, :validatedAt, :applicationStartedAt,
          :completedAt, :failedAt, CAST(:summary AS jsonb), CAST(:progress AS jsonb), :errorCount, :executionGeneration,
          :resumedBy, :resumedAt, :failedStep, :failureReason, :recoverable, :version, :updatedAt)
        ON CONFLICT (import_id) DO UPDATE SET academic_period_id = COALESCE(EXCLUDED.academic_period_id, student_import_read_model.academic_period_id),
          status = COALESCE(EXCLUDED.status, student_import_read_model.status), started_by_account_id = COALESCE(EXCLUDED.started_by_account_id, student_import_read_model.started_by_account_id),
          authorized_by_account_id = COALESCE(EXCLUDED.authorized_by_account_id, student_import_read_model.authorized_by_account_id),
          started_at = COALESCE(EXCLUDED.started_at, student_import_read_model.started_at), validated_at = COALESCE(EXCLUDED.validated_at, student_import_read_model.validated_at),
          application_started_at = COALESCE(EXCLUDED.application_started_at, student_import_read_model.application_started_at), completed_at = COALESCE(EXCLUDED.completed_at, student_import_read_model.completed_at),
          failed_at = COALESCE(EXCLUDED.failed_at, student_import_read_model.failed_at), summary = COALESCE(EXCLUDED.summary, student_import_read_model.summary),
          application_progress = COALESCE(EXCLUDED.application_progress, student_import_read_model.application_progress), validation_error_count = EXCLUDED.validation_error_count,
          execution_generation = EXCLUDED.execution_generation, last_resumed_by_account_id = COALESCE(EXCLUDED.last_resumed_by_account_id, student_import_read_model.last_resumed_by_account_id),
          last_resumed_at = COALESCE(EXCLUDED.last_resumed_at, student_import_read_model.last_resumed_at), failed_step = COALESCE(EXCLUDED.failed_step, student_import_read_model.failed_step),
          failure_reason = COALESCE(EXCLUDED.failure_reason, student_import_read_model.failure_reason), recoverable = EXCLUDED.recoverable,
          process_version = EXCLUDED.process_version, updated_at = EXCLUDED.updated_at
        WHERE student_import_read_model.process_version <= EXCLUDED.process_version
        """)
        .param("id", v.importId()).param("periodId", v.academicPeriodId()).param("status", v.status()).param("startedBy", v.startedByAccountId())
        .param("authorizedBy", v.authorizedByAccountId()).param("startedAt", JdbcTimes.timestamp(v.startedAt())).param("validatedAt", JdbcTimes.timestamp(v.validatedAt()))
        .param("applicationStartedAt", JdbcTimes.timestamp(v.applicationStartedAt())).param("completedAt", JdbcTimes.timestamp(v.completedAt())).param("failedAt", JdbcTimes.timestamp(v.failedAt()))
        .param("summary", v.summary()).param("progress", v.applicationProgress()).param("errorCount", v.validationErrorCount()).param("executionGeneration", v.executionGeneration())
        .param("resumedBy", v.lastResumedByAccountId()).param("resumedAt", JdbcTimes.timestamp(v.lastResumedAt())).param("failedStep", v.failedStep())
        .param("failureReason", v.failureReason()).param("recoverable", v.recoverable()).param("version", v.processVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void validationError(ProjectionReadModel.ValidationError v) {
    jdbc.sql("""
        INSERT INTO student_import_validation_error_read_model
          (import_id, batch_number, batch_count, sequence, sheet, row_number, column_name, error_code, error_message, process_version, updated_at)
        VALUES (:importId, :batch, :batchCount, :sequence, :sheet, :rowNumber, :columnName, :errorCode, :message, :version, :updatedAt)
        ON CONFLICT (import_id, batch_number, sequence) DO UPDATE SET batch_count = EXCLUDED.batch_count,
          sheet = EXCLUDED.sheet, row_number = EXCLUDED.row_number, column_name = EXCLUDED.column_name,
          error_code = EXCLUDED.error_code, error_message = EXCLUDED.error_message, process_version = EXCLUDED.process_version,
          updated_at = EXCLUDED.updated_at
        WHERE student_import_validation_error_read_model.process_version <= EXCLUDED.process_version
        """)
        .param("importId", v.importId()).param("batch", v.batchNumber()).param("batchCount", v.batchCount()).param("sequence", v.sequence())
        .param("sheet", v.sheet()).param("rowNumber", v.row()).param("columnName", v.column()).param("errorCode", v.errorCode())
        .param("message", v.message()).param("version", v.processVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void mailboxMessage(ProjectionReadModel.MailboxMessage v) {
    if (v.createdAt() == null) {
      jdbc.sql("""
          INSERT INTO mailbox_messages_read_model(inbox_message_id, expired_at, updated_at)
          VALUES (:id, :expiredAt, :updatedAt)
          ON CONFLICT (inbox_message_id) DO UPDATE SET
            expired_at = CASE
              WHEN mailbox_messages_read_model.expired_at IS NULL THEN EXCLUDED.expired_at
              ELSE GREATEST(mailbox_messages_read_model.expired_at, EXCLUDED.expired_at)
            END,
            updated_at = GREATEST(mailbox_messages_read_model.updated_at, EXCLUDED.updated_at)
          """)
          .param("id", v.inboxMessageId())
          .param("expiredAt", JdbcTimes.timestamp(v.expiredAt()))
          .param("updatedAt", JdbcTimes.timestamp(v.updatedAt()))
          .update();
      return;
    }
    jdbc.sql("""
        INSERT INTO mailbox_messages_read_model
          (inbox_message_id, communication_id, recipient_account_id, system_code, communication_type,
           title, body, created_at, expires_at, expired_at, updated_at)
        VALUES (:id, :communicationId, :recipient, :systemCode, :type, :title, :body,
          :createdAt, :expiresAt, :expiredAt, :updatedAt)
        ON CONFLICT (inbox_message_id) DO UPDATE SET
          communication_id = COALESCE(mailbox_messages_read_model.communication_id, EXCLUDED.communication_id),
          recipient_account_id = COALESCE(mailbox_messages_read_model.recipient_account_id, EXCLUDED.recipient_account_id),
          system_code = COALESCE(mailbox_messages_read_model.system_code, EXCLUDED.system_code),
          communication_type = COALESCE(mailbox_messages_read_model.communication_type, EXCLUDED.communication_type),
          title = COALESCE(mailbox_messages_read_model.title, EXCLUDED.title),
          body = COALESCE(mailbox_messages_read_model.body, EXCLUDED.body),
          created_at = COALESCE(mailbox_messages_read_model.created_at, EXCLUDED.created_at),
          expires_at = COALESCE(mailbox_messages_read_model.expires_at, EXCLUDED.expires_at),
          expired_at = COALESCE(mailbox_messages_read_model.expired_at, EXCLUDED.expired_at),
          updated_at = GREATEST(mailbox_messages_read_model.updated_at, EXCLUDED.updated_at)
        """)
        .param("id", v.inboxMessageId()).param("communicationId", v.communicationId())
        .param("recipient", v.recipientAccountId()).param("systemCode", v.systemCode())
        .param("type", v.communicationType()).param("title", v.title()).param("body", v.body())
        .param("createdAt", JdbcTimes.timestamp(v.createdAt())).param("expiresAt", JdbcTimes.timestamp(v.expiresAt()))
        .param("expiredAt", JdbcTimes.timestamp(v.expiredAt())).param("updatedAt", JdbcTimes.timestamp(v.updatedAt()))
        .update();
  }

  private void mailboxVisit(ProjectionReadModel.MailboxVisit v) {
    jdbc.sql("""
        INSERT INTO mailbox_visits_read_model(account_id, system_code, last_visited_at, updated_at)
        VALUES (:accountId, :systemCode, :visitedAt, :updatedAt)
        ON CONFLICT (account_id, system_code) DO UPDATE SET
          last_visited_at = EXCLUDED.last_visited_at,
          updated_at = GREATEST(mailbox_visits_read_model.updated_at, EXCLUDED.updated_at)
        WHERE mailbox_visits_read_model.last_visited_at < EXCLUDED.last_visited_at
        """)
        .param("accountId", v.accountId()).param("systemCode", v.systemCode())
        .param("visitedAt", JdbcTimes.timestamp(v.lastVisitedAt())).param("updatedAt", JdbcTimes.timestamp(v.updatedAt()))
        .update();
  }

  private void washCalendar(ProjectionReadModel.WashCalendar v) {
    jdbc.sql("""
        INSERT INTO wash_service_calendar_read_model(calendar_id, code, name, timezone, effective_from, effective_until, status, source_version, updated_at)
        VALUES (:id, :code, :name, :timezone, :fromDate, :untilDate, :status, :version, :updatedAt)
        ON CONFLICT (calendar_id) DO UPDATE SET code = COALESCE(EXCLUDED.code, wash_service_calendar_read_model.code),
          name = COALESCE(EXCLUDED.name, wash_service_calendar_read_model.name), timezone = COALESCE(EXCLUDED.timezone, wash_service_calendar_read_model.timezone),
          effective_from = COALESCE(EXCLUDED.effective_from, wash_service_calendar_read_model.effective_from), effective_until = COALESCE(EXCLUDED.effective_until, wash_service_calendar_read_model.effective_until),
          status = COALESCE(EXCLUDED.status, wash_service_calendar_read_model.status), source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE wash_service_calendar_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.calendarId()).param("code", v.code()).param("name", v.name()).param("timezone", v.timezone())
        .param("fromDate", v.effectiveFrom()).param("untilDate", v.effectiveUntil()).param("status", v.status()).param("version", v.sourceVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void washSchedule(ProjectionReadModel.WashSchedule v) {
    jdbc.sql("""
        INSERT INTO wash_service_schedule_read_model(schedule_id, calendar_id, day_of_week, opening_time, closing_time, slot_duration_minutes, status, source_version, updated_at)
        VALUES (:id, :calendarId, :day, :opening, :closing, :duration, :status, :version, :updatedAt)
        ON CONFLICT (schedule_id) DO UPDATE SET calendar_id = COALESCE(EXCLUDED.calendar_id, wash_service_schedule_read_model.calendar_id),
          day_of_week = NULLIF(EXCLUDED.day_of_week, 0), opening_time = COALESCE(EXCLUDED.opening_time, wash_service_schedule_read_model.opening_time),
          closing_time = COALESCE(EXCLUDED.closing_time, wash_service_schedule_read_model.closing_time), slot_duration_minutes = NULLIF(EXCLUDED.slot_duration_minutes, 0),
          status = COALESCE(EXCLUDED.status, wash_service_schedule_read_model.status), source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE wash_service_schedule_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.scheduleId()).param("calendarId", v.calendarId()).param("day", v.dayOfWeek()).param("opening", v.openingTime())
        .param("closing", v.closingTime()).param("duration", v.slotDurationMinutes()).param("status", v.status()).param("version", v.sourceVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void closure(ProjectionReadModel.AdministrativeClosure v) {
    jdbc.sql("""
        INSERT INTO wash_administrative_closure_read_model(closure_id, calendar_id, scope, cabin_id, tank_id, starts_at, ends_at, reason, status, source_version, updated_at)
        VALUES (:id, :calendarId, :scope, :cabinId, :tankId, :startsAt, :endsAt, :reason, :status, :version, :updatedAt)
        ON CONFLICT (closure_id) DO UPDATE SET calendar_id = COALESCE(EXCLUDED.calendar_id, wash_administrative_closure_read_model.calendar_id),
          scope = COALESCE(EXCLUDED.scope, wash_administrative_closure_read_model.scope), cabin_id = COALESCE(EXCLUDED.cabin_id, wash_administrative_closure_read_model.cabin_id),
          tank_id = COALESCE(EXCLUDED.tank_id, wash_administrative_closure_read_model.tank_id), starts_at = COALESCE(EXCLUDED.starts_at, wash_administrative_closure_read_model.starts_at),
          ends_at = COALESCE(EXCLUDED.ends_at, wash_administrative_closure_read_model.ends_at), reason = COALESCE(EXCLUDED.reason, wash_administrative_closure_read_model.reason),
          status = COALESCE(EXCLUDED.status, wash_administrative_closure_read_model.status), source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE wash_administrative_closure_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.closureId()).param("calendarId", v.calendarId()).param("scope", v.scope()).param("cabinId", v.cabinId()).param("tankId", v.tankId())
        .param("startsAt", JdbcTimes.timestamp(v.startsAt())).param("endsAt", JdbcTimes.timestamp(v.endsAt())).param("reason", v.reason()).param("status", v.status()).param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void cabin(ProjectionReadModel.Cabin v) {
    jdbc.sql("""
        INSERT INTO wash_cabin_read_model(cabin_id, code, name, cabin_type, status, source_version, updated_at)
        VALUES (:id, :code, :name, :type, :status, :version, :updatedAt)
        ON CONFLICT (cabin_id) DO UPDATE SET code = COALESCE(EXCLUDED.code, wash_cabin_read_model.code), name = COALESCE(EXCLUDED.name, wash_cabin_read_model.name),
          cabin_type = COALESCE(EXCLUDED.cabin_type, wash_cabin_read_model.cabin_type), status = COALESCE(EXCLUDED.status, wash_cabin_read_model.status), source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE wash_cabin_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.cabinId()).param("code", v.code()).param("name", v.name()).param("type", v.cabinType()).param("status", v.status()).param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void tank(ProjectionReadModel.Tank v) {
    jdbc.sql("""
        INSERT INTO wash_tank_read_model(tank_id, cabin_id, code, name, configured_capacity, status, source_version, updated_at)
        VALUES (:id, :cabinId, :code, :name, :capacity, :status, :version, :updatedAt)
        ON CONFLICT (tank_id) DO UPDATE SET cabin_id = COALESCE(EXCLUDED.cabin_id, wash_tank_read_model.cabin_id), code = COALESCE(EXCLUDED.code, wash_tank_read_model.code),
          name = COALESCE(EXCLUDED.name, wash_tank_read_model.name), configured_capacity = CASE WHEN EXCLUDED.configured_capacity = 0 THEN wash_tank_read_model.configured_capacity ELSE EXCLUDED.configured_capacity END,
          status = COALESCE(EXCLUDED.status, wash_tank_read_model.status), source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE wash_tank_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.tankId()).param("cabinId", v.cabinId()).param("code", v.code()).param("name", v.name()).param("capacity", v.configuredCapacity()).param("status", v.status()).param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void slot(ProjectionReadModel.AppointmentSlot v) {
    jdbc.sql("""
        INSERT INTO appointment_time_slot_read_model
          (appointment_time_slot_id, calendar_id, source_schedule_id, service_date, starts_at, ends_at, timezone,
           booking_deadline_at, cancellation_deadline_at, arrival_tolerance_until, status, capacity_normal,
           capacity_journey, capacity_immunocompromised, slot_source_version, capacity_projection_version, updated_at)
        VALUES (:id, :calendarId, :scheduleId, :serviceDate, :startsAt, :endsAt, :timezone, :bookingDeadline,
          :cancellationDeadline, :arrivalTolerance, :status, :capacityNormal, :capacityJourney, :capacityImmunocompromised,
          :slotVersion, :capacityVersion, :updatedAt)
        ON CONFLICT (appointment_time_slot_id) DO UPDATE SET calendar_id = COALESCE(EXCLUDED.calendar_id, appointment_time_slot_read_model.calendar_id),
          source_schedule_id = COALESCE(EXCLUDED.source_schedule_id, appointment_time_slot_read_model.source_schedule_id), service_date = COALESCE(EXCLUDED.service_date, appointment_time_slot_read_model.service_date),
          starts_at = COALESCE(EXCLUDED.starts_at, appointment_time_slot_read_model.starts_at), ends_at = COALESCE(EXCLUDED.ends_at, appointment_time_slot_read_model.ends_at), timezone = COALESCE(EXCLUDED.timezone, appointment_time_slot_read_model.timezone),
          booking_deadline_at = COALESCE(EXCLUDED.booking_deadline_at, appointment_time_slot_read_model.booking_deadline_at), cancellation_deadline_at = COALESCE(EXCLUDED.cancellation_deadline_at, appointment_time_slot_read_model.cancellation_deadline_at),
          arrival_tolerance_until = COALESCE(EXCLUDED.arrival_tolerance_until, appointment_time_slot_read_model.arrival_tolerance_until), status = COALESCE(EXCLUDED.status, appointment_time_slot_read_model.status),
          capacity_normal = COALESCE(EXCLUDED.capacity_normal, appointment_time_slot_read_model.capacity_normal), capacity_journey = COALESCE(EXCLUDED.capacity_journey, appointment_time_slot_read_model.capacity_journey), capacity_immunocompromised = COALESCE(EXCLUDED.capacity_immunocompromised, appointment_time_slot_read_model.capacity_immunocompromised),
          slot_source_version = CASE WHEN EXCLUDED.slot_source_version = 0 THEN appointment_time_slot_read_model.slot_source_version ELSE GREATEST(appointment_time_slot_read_model.slot_source_version, EXCLUDED.slot_source_version) END,
          capacity_projection_version = GREATEST(appointment_time_slot_read_model.capacity_projection_version, EXCLUDED.capacity_projection_version), updated_at = EXCLUDED.updated_at
        WHERE appointment_time_slot_read_model.slot_source_version <= EXCLUDED.slot_source_version
           OR appointment_time_slot_read_model.capacity_projection_version <= EXCLUDED.capacity_projection_version
        """)
        .param("id", v.appointmentTimeSlotId()).param("calendarId", v.calendarId()).param("scheduleId", v.sourceScheduleId()).param("serviceDate", v.serviceDate())
        .param("startsAt", JdbcTimes.timestamp(v.startsAt())).param("endsAt", JdbcTimes.timestamp(v.endsAt())).param("timezone", v.timezone()).param("bookingDeadline", JdbcTimes.timestamp(v.bookingDeadlineAt()))
        .param("cancellationDeadline", JdbcTimes.timestamp(v.cancellationDeadlineAt())).param("arrivalTolerance", JdbcTimes.timestamp(v.arrivalToleranceUntil())).param("status", v.status())
        .param("capacityNormal", v.capacityNormal()).param("capacityJourney", v.capacityJourney()).param("capacityImmunocompromised", v.capacityImmunocompromised()).param("slotVersion", v.slotSourceVersion()).param("capacityVersion", v.capacityProjectionVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void appointment(ProjectionReadModel.Appointment v) {
    jdbc.sql("""
        INSERT INTO appointment_read_model(appointment_id, appointment_time_slot_id, student_account_id, appointment_type, instrument_count, piece_type, course_section_id, exceptional_authorization_id, exceptional_authorization_context_known, status, cancellation_type, source_version, updated_at)
        VALUES (:id, :slotId, :accountId, :type, :instrumentCount, :pieceType, :sectionId, :exceptionalAuthorizationId, COALESCE(:exceptionalContextKnown, FALSE), :status, :cancellationType, :version, :updatedAt)
        ON CONFLICT (appointment_id) DO UPDATE SET appointment_time_slot_id = COALESCE(EXCLUDED.appointment_time_slot_id, appointment_read_model.appointment_time_slot_id), student_account_id = COALESCE(EXCLUDED.student_account_id, appointment_read_model.student_account_id),
          appointment_type = COALESCE(EXCLUDED.appointment_type, appointment_read_model.appointment_type), instrument_count = COALESCE(EXCLUDED.instrument_count, appointment_read_model.instrument_count),
          piece_type = COALESCE(EXCLUDED.piece_type, appointment_read_model.piece_type), course_section_id = COALESCE(EXCLUDED.course_section_id, appointment_read_model.course_section_id), exceptional_authorization_id = COALESCE(EXCLUDED.exceptional_authorization_id, appointment_read_model.exceptional_authorization_id), exceptional_authorization_context_known = COALESCE(:exceptionalContextKnown, appointment_read_model.exceptional_authorization_context_known), status = EXCLUDED.status,
          cancellation_type = COALESCE(EXCLUDED.cancellation_type, appointment_read_model.cancellation_type), source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE appointment_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.appointmentId()).param("slotId", v.appointmentTimeSlotId()).param("accountId", v.studentAccountId()).param("type", v.appointmentType()).param("instrumentCount", v.instrumentCount()).param("pieceType", v.pieceType()).param("sectionId", v.courseSectionId()).param("exceptionalAuthorizationId", v.exceptionalAuthorizationId()).param("exceptionalContextKnown", v.exceptionalAuthorizationContextKnown()).param("status", v.status()).param("cancellationType", v.cancellationType()).param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void exceptional(ProjectionReadModel.ExceptionalAuthorization v) {
    for (UUID predecessor : v.supersededAuthorizationIds()) {
      jdbc.sql("""
          INSERT INTO exceptional_authorization_supersession_read_model(authorization_id, successor_authorization_id)
          VALUES (:id, :successor) ON CONFLICT (authorization_id) DO NOTHING
          """).param("id", predecessor).param("successor", v.authorizationId()).update();
    }
    jdbc.sql("""
        INSERT INTO exceptional_appointment_authorization_read_model(authorization_id, student_account_id, service_date, status, authorized_by_account_id, reason, source_version, updated_at, expires_at, policy_context_known)
        VALUES (:id, :accountId, :serviceDate, :status, :authorizedBy, :reason, :version, :updatedAt, :expiresAt, :known)
        ON CONFLICT (authorization_id) DO UPDATE SET student_account_id = EXCLUDED.student_account_id, service_date = COALESCE(EXCLUDED.service_date, exceptional_appointment_authorization_read_model.service_date), status = EXCLUDED.status, authorized_by_account_id = COALESCE(EXCLUDED.authorized_by_account_id, exceptional_appointment_authorization_read_model.authorized_by_account_id), reason = COALESCE(EXCLUDED.reason, exceptional_appointment_authorization_read_model.reason), source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE exceptional_appointment_authorization_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.authorizationId()).param("accountId", v.studentAccountId()).param("serviceDate", v.serviceDate()).param("status", v.status()).param("authorizedBy", v.authorizedByAccountId()).param("reason", v.reason()).param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).param("expiresAt", JdbcTimes.timestamp(v.expiresAt())).param("known", v.policyContextKnown()).update();
    // A replayed grant can enrich a cancellation-first row without reverting its newer status.
    if (v.policyContextKnown()) jdbc.sql("""
        UPDATE exceptional_appointment_authorization_read_model
        SET expires_at = :expiresAt, policy_context_known = TRUE,
            authorized_by_account_id = COALESCE(authorized_by_account_id, :authorizedBy),
            reason = COALESCE(reason, :reason)
        WHERE authorization_id = :id
        """).param("id", v.authorizationId()).param("expiresAt", JdbcTimes.timestamp(v.expiresAt()))
        .param("authorizedBy", v.authorizedByAccountId()).param("reason", v.reason()).update();
  }

  private void execution(ProjectionReadModel.WashExecution v) {
    if ("COMPLETED".equals(v.status()) || "CANCELLED".equals(v.status())) {
      lockWashExecution(v.washExecutionId());
    }
    jdbc.sql("""
        INSERT INTO wash_execution_read_model(wash_execution_id, appointment_id, appointment_type, status, arrived_at, authorized_at, started_at, exit_submitted_at, completed_at, cancelled_at, cancellation_type, rejection_reason, execution_version, updated_at)
        VALUES (:id, :appointmentId, :appointmentType, :status, :arrivedAt, :authorizedAt, :startedAt, :exitSubmittedAt, :completedAt, :cancelledAt, :cancellationType, :rejectionReason, :version, :updatedAt)
        ON CONFLICT (wash_execution_id) DO UPDATE SET appointment_id = COALESCE(EXCLUDED.appointment_id, wash_execution_read_model.appointment_id), appointment_type = COALESCE(EXCLUDED.appointment_type, wash_execution_read_model.appointment_type), status = EXCLUDED.status,
          arrived_at = COALESCE(EXCLUDED.arrived_at, wash_execution_read_model.arrived_at), authorized_at = COALESCE(EXCLUDED.authorized_at, wash_execution_read_model.authorized_at), started_at = COALESCE(EXCLUDED.started_at, wash_execution_read_model.started_at), exit_submitted_at = COALESCE(EXCLUDED.exit_submitted_at, wash_execution_read_model.exit_submitted_at), completed_at = COALESCE(EXCLUDED.completed_at, wash_execution_read_model.completed_at), cancelled_at = COALESCE(EXCLUDED.cancelled_at, wash_execution_read_model.cancelled_at), cancellation_type = COALESCE(EXCLUDED.cancellation_type, wash_execution_read_model.cancellation_type), rejection_reason = COALESCE(EXCLUDED.rejection_reason, wash_execution_read_model.rejection_reason), execution_version = EXCLUDED.execution_version, updated_at = EXCLUDED.updated_at
        WHERE wash_execution_read_model.execution_version <= EXCLUDED.execution_version
        """).param("id", v.washExecutionId()).param("appointmentId", v.appointmentId()).param("appointmentType", v.appointmentType()).param("status", v.status()).param("arrivedAt", JdbcTimes.timestamp(v.arrivedAt())).param("authorizedAt", JdbcTimes.timestamp(v.authorizedAt())).param("startedAt", JdbcTimes.timestamp(v.startedAt())).param("exitSubmittedAt", JdbcTimes.timestamp(v.exitSubmittedAt())).param("completedAt", JdbcTimes.timestamp(v.completedAt())).param("cancelledAt", JdbcTimes.timestamp(v.cancelledAt())).param("cancellationType", v.cancellationType()).param("rejectionReason", v.rejectionReason()).param("version", v.executionVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void assignment(ProjectionReadModel.ResourceAssignment v) {
    lockWashExecution(v.washExecutionId());
    jdbc.sql("""
        INSERT INTO wash_resource_assignment_read_model(resource_assignment_id, wash_execution_id, appointment_id, appointment_time_slot_id, cabin_id, tank_id, assignment_type, assigned_at, released_at, source_message_id, source_version, updated_at)
        SELECT :id, :executionId, :appointmentId, :slotId, :cabinId, :tankId, :type, :assignedAt,
          COALESCE(:releasedAt, CASE
            WHEN e.status = 'COMPLETED' AND e.execution_version >= :version THEN e.completed_at
            WHEN e.status = 'CANCELLED' AND e.execution_version >= :version THEN e.cancelled_at
            ELSE NULL
          END),
          :messageId, :version, :updatedAt
        FROM (SELECT 1) seed
        LEFT JOIN wash_execution_read_model e ON e.wash_execution_id = :executionId
        ON CONFLICT (resource_assignment_id) DO UPDATE SET wash_execution_id = EXCLUDED.wash_execution_id, appointment_id = COALESCE(EXCLUDED.appointment_id, wash_resource_assignment_read_model.appointment_id), appointment_time_slot_id = COALESCE(EXCLUDED.appointment_time_slot_id, wash_resource_assignment_read_model.appointment_time_slot_id), cabin_id = COALESCE(EXCLUDED.cabin_id, wash_resource_assignment_read_model.cabin_id), tank_id = COALESCE(EXCLUDED.tank_id, wash_resource_assignment_read_model.tank_id), assignment_type = COALESCE(EXCLUDED.assignment_type, wash_resource_assignment_read_model.assignment_type), assigned_at = COALESCE(EXCLUDED.assigned_at, wash_resource_assignment_read_model.assigned_at), released_at = COALESCE(EXCLUDED.released_at, wash_resource_assignment_read_model.released_at), source_message_id = EXCLUDED.source_message_id, source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE wash_resource_assignment_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.resourceAssignmentId()).param("executionId", v.washExecutionId()).param("appointmentId", v.appointmentId()).param("slotId", v.appointmentTimeSlotId()).param("cabinId", v.cabinId()).param("tankId", v.tankId()).param("type", v.assignmentType()).param("assignedAt", JdbcTimes.timestamp(v.assignedAt())).param("releasedAt", JdbcTimes.timestamp(v.releasedAt())).param("messageId", v.sourceMessageId()).param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void lockWashExecution(UUID washExecutionId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:executionId AS TEXT), 0))")
        .param("executionId", washExecutionId)
        .query()
        .singleRow();
  }

  private void unavailability(ProjectionReadModel.ResourceUnavailability v) {
    jdbc.sql("""
        INSERT INTO wash_operational_resource_unavailability_read_model(resource_unavailability_id, resource_type, resource_id, status, reason, source_version, updated_at)
        VALUES (:id, :type, :resourceId, :status, :reason, :version, :updatedAt)
        ON CONFLICT (resource_unavailability_id) DO UPDATE SET resource_type = EXCLUDED.resource_type, resource_id = EXCLUDED.resource_id, status = EXCLUDED.status, reason = COALESCE(EXCLUDED.reason, wash_operational_resource_unavailability_read_model.reason), source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE wash_operational_resource_unavailability_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.resourceUnavailabilityId()).param("type", v.resourceType()).param("resourceId", v.resourceId()).param("status", v.status()).param("reason", v.reason()).param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void cleaning(ProjectionReadModel.CleaningPeriod v) {
    jdbc.sql("""
        INSERT INTO wash_cleaning_period_read_model(cleaning_period_id, wash_execution_id, resource_assignment_id, cabin_id, tank_id, starts_at, expected_ends_at, completed_at, status, source_version, updated_at)
        VALUES (:id, :executionId, :assignmentId, :cabinId, :tankId, :startsAt, :expectedEndsAt, :completedAt, :status, :version, :updatedAt)
        ON CONFLICT (cleaning_period_id) DO UPDATE SET wash_execution_id = EXCLUDED.wash_execution_id, resource_assignment_id = COALESCE(EXCLUDED.resource_assignment_id, wash_cleaning_period_read_model.resource_assignment_id), cabin_id = COALESCE(EXCLUDED.cabin_id, wash_cleaning_period_read_model.cabin_id), tank_id = COALESCE(EXCLUDED.tank_id, wash_cleaning_period_read_model.tank_id), starts_at = COALESCE(EXCLUDED.starts_at, wash_cleaning_period_read_model.starts_at), expected_ends_at = COALESCE(EXCLUDED.expected_ends_at, wash_cleaning_period_read_model.expected_ends_at), completed_at = COALESCE(EXCLUDED.completed_at, wash_cleaning_period_read_model.completed_at), status = EXCLUDED.status, source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE wash_cleaning_period_read_model.source_version <= EXCLUDED.source_version
        """).param("id", v.cleaningPeriodId()).param("executionId", v.washExecutionId()).param("assignmentId", v.resourceAssignmentId()).param("cabinId", v.cabinId()).param("tankId", v.tankId()).param("startsAt", JdbcTimes.timestamp(v.startsAt())).param("expectedEndsAt", JdbcTimes.timestamp(v.expectedEndsAt())).param("completedAt", JdbcTimes.timestamp(v.completedAt())).param("status", v.status()).param("version", v.sourceVersion()).param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  private void jornada(ProjectionReadModel.Jornada v) {
    jdbc.sql("""
        INSERT INTO jornada_read_model
          (jornada_id, tipo_jornada_id, nombre, fecha, hora_inicio, hora_fin, lugar, cupo_total,
           fecha_limite_documentos, descripcion, estado, motivo_cancelacion, source_version, updated_at)
        VALUES (:id, :tipoJornadaId, :nombre, :fecha, :horaInicio, :horaFin, :lugar, :cupoTotal,
          :fechaLimite, :descripcion, :estado, :motivoCancelacion, :version, :updatedAt)
        ON CONFLICT (jornada_id) DO UPDATE SET
          tipo_jornada_id = COALESCE(EXCLUDED.tipo_jornada_id, jornada_read_model.tipo_jornada_id),
          nombre = COALESCE(EXCLUDED.nombre, jornada_read_model.nombre),
          fecha = COALESCE(EXCLUDED.fecha, jornada_read_model.fecha),
          hora_inicio = COALESCE(EXCLUDED.hora_inicio, jornada_read_model.hora_inicio),
          hora_fin = COALESCE(EXCLUDED.hora_fin, jornada_read_model.hora_fin),
          lugar = COALESCE(EXCLUDED.lugar, jornada_read_model.lugar),
          cupo_total = COALESCE(EXCLUDED.cupo_total, jornada_read_model.cupo_total),
          fecha_limite_documentos = COALESCE(EXCLUDED.fecha_limite_documentos, jornada_read_model.fecha_limite_documentos),
          descripcion = COALESCE(EXCLUDED.descripcion, jornada_read_model.descripcion),
          motivo_cancelacion = COALESCE(EXCLUDED.motivo_cancelacion, jornada_read_model.motivo_cancelacion),
          estado = EXCLUDED.estado, source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE jornada_read_model.source_version <= EXCLUDED.source_version
        """)
        .param("id", v.jornadaId()).param("tipoJornadaId", v.tipoJornadaId()).param("nombre", v.nombre())
        .param("fecha", v.fecha()).param("horaInicio", v.horaInicio()).param("horaFin", v.horaFin())
        .param("lugar", v.lugar()).param("cupoTotal", v.cupoTotal()).param("fechaLimite", v.fechaLimiteDocumentos())
        .param("descripcion", v.descripcion()).param("estado", v.estado())
        .param("motivoCancelacion", v.motivoCancelacion()).param("version", v.sourceVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  @Override
  public List<ProjectionReadModel.Jornada> findJornadas() {
    return jdbc.sql("""
        SELECT jornada_id, tipo_jornada_id, nombre, fecha, hora_inicio, hora_fin, lugar, cupo_total,
               fecha_limite_documentos, descripcion, estado, motivo_cancelacion, source_version, updated_at
        FROM jornada_read_model
        WHERE estado = 'PUBLICADA'
        ORDER BY fecha ASC, jornada_id ASC
        """)
        .query((rs, rowNum) -> new ProjectionReadModel.Jornada(
            uuid(rs, "jornada_id"), uuid(rs, "tipo_jornada_id"), rs.getString("nombre"),
            rs.getObject("fecha", java.time.LocalDate.class), rs.getObject("hora_inicio", java.time.LocalTime.class),
            rs.getObject("hora_fin", java.time.LocalTime.class), rs.getString("lugar"),
            integer(rs, "cupo_total"), rs.getObject("fecha_limite_documentos", java.time.LocalDate.class),
            rs.getString("descripcion"), rs.getString("estado"), rs.getString("motivo_cancelacion"),
            rs.getLong("source_version"), instant(rs, "updated_at")))
        .list();
  }

  private void registro(ProjectionReadModel.Registro v) {
    jdbc.sql("""
        INSERT INTO registro_read_model
          (registro_id, jornada_id, alumno_account_id, estado, motivo_rechazo, source_version, updated_at)
        VALUES (:id, :jornadaId, :alumnoAccountId, :estado, :motivoRechazo, :version, :updatedAt)
        ON CONFLICT (registro_id) DO UPDATE SET
          jornada_id = COALESCE(EXCLUDED.jornada_id, registro_read_model.jornada_id),
          alumno_account_id = COALESCE(EXCLUDED.alumno_account_id, registro_read_model.alumno_account_id),
          estado = EXCLUDED.estado,
          motivo_rechazo = COALESCE(EXCLUDED.motivo_rechazo, registro_read_model.motivo_rechazo),
          source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE registro_read_model.source_version <= EXCLUDED.source_version
        """)
        .param("id", v.registroId()).param("jornadaId", v.jornadaId())
        .param("alumnoAccountId", v.alumnoAccountId()).param("estado", v.estado())
        .param("motivoRechazo", v.motivoRechazo()).param("version", v.sourceVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  @Override
  public List<ProjectionReadModel.Registro> findRegistrosByAlumno(UUID alumnoAccountId) {
    return jdbc.sql("""
        SELECT registro_id, jornada_id, alumno_account_id, estado, motivo_rechazo, source_version, updated_at
        FROM registro_read_model
        WHERE alumno_account_id = :alumnoAccountId
        ORDER BY updated_at DESC
        """)
        .param("alumnoAccountId", alumnoAccountId)
        .query((rs, rowNum) -> mapRegistro(rs))
        .list();
  }

  @Override
  public List<ProjectionReadModel.Registro> findRegistrosByJornada(UUID jornadaId) {
    return jdbc.sql("""
        SELECT registro_id, jornada_id, alumno_account_id, estado, motivo_rechazo, source_version, updated_at
        FROM registro_read_model
        WHERE jornada_id = :jornadaId
        ORDER BY updated_at ASC
        """)
        .param("jornadaId", jornadaId)
        .query((rs, rowNum) -> mapRegistro(rs))
        .list();
  }

  @Override
  public Optional<UUID> findRegistroAlumnoAccountId(UUID registroId) {
    return jdbc.sql("SELECT alumno_account_id FROM registro_read_model WHERE registro_id = :registroId")
        .param("registroId", registroId)
        .query(UUID.class)
        .optional();
  }

  @Override
  public Optional<Integer> findCurrentSemester(UUID accountId) {
    return jdbc.sql("""
        SELECT current_semester FROM student_academic_profile_read_model
        WHERE account_id = :accountId
        """)
        .param("accountId", accountId)
        .query(Integer.class)
        .optional();
  }

  private static ProjectionReadModel.Registro mapRegistro(ResultSet rs) throws SQLException {
    return new ProjectionReadModel.Registro(
        uuid(rs, "registro_id"), uuid(rs, "jornada_id"), uuid(rs, "alumno_account_id"),
        rs.getString("estado"), rs.getString("motivo_rechazo"), rs.getLong("source_version"),
        instant(rs, "updated_at"));
  }

  private void tipoJornada(ProjectionReadModel.TipoJornada v) {
    jdbc.sql("""
        INSERT INTO tipo_jornada_read_model
          (tipo_jornada_id, nombre, semestres_permitidos, source_version, updated_at)
        VALUES (:id, :nombre, CAST(:semestres AS jsonb), :version, :updatedAt)
        ON CONFLICT (tipo_jornada_id) DO UPDATE SET
          nombre = COALESCE(EXCLUDED.nombre, tipo_jornada_read_model.nombre),
          semestres_permitidos = COALESCE(EXCLUDED.semestres_permitidos, tipo_jornada_read_model.semestres_permitidos),
          source_version = EXCLUDED.source_version, updated_at = EXCLUDED.updated_at
        WHERE tipo_jornada_read_model.source_version <= EXCLUDED.source_version
        """)
        .param("id", v.tipoJornadaId()).param("nombre", v.nombre())
        .param("semestres", writeIntArray(v.semestresPermitidos())).param("version", v.sourceVersion())
        .param("updatedAt", JdbcTimes.timestamp(v.updatedAt())).update();
  }

  @Override
  public List<ProjectionReadModel.TipoJornada> findTiposJornada() {
    return jdbc.sql("""
        SELECT tipo_jornada_id, nombre, semestres_permitidos, source_version, updated_at
        FROM tipo_jornada_read_model
        ORDER BY nombre ASC
        """)
        .query((rs, rowNum) -> new ProjectionReadModel.TipoJornada(
            uuid(rs, "tipo_jornada_id"), rs.getString("nombre"),
            readIntArray(rs.getString("semestres_permitidos")), rs.getLong("source_version"),
            instant(rs, "updated_at")))
        .list();
  }

  private static String writeIntArray(List<Integer> values) {
    return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static List<Integer> readIntArray(String json) {
    String trimmed = json.strip().replaceAll("^\\[|]$", "");
    if (trimmed.isBlank()) return List.of();
    return java.util.Arrays.stream(trimmed.split(","))
        .map(String::trim).map(Integer::parseInt).toList();
  }
}
