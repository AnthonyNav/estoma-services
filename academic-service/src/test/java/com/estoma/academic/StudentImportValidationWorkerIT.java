package com.estoma.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.academic.application.AcademicCommandDispatcher;
import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.importing.StudentImportFileStore;
import com.estoma.academic.importing.StudentImportValidationWorker;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "estoma.messaging.listeners-enabled=false",
    "estoma.messaging.topology-declare=false",
    "spring.task.scheduling.enabled=false"
})
@Testcontainers
@Tag("integration")
class StudentImportValidationWorkerIT {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("academic_db")
      .withUsername("academic_service")
      .withPassword("academic_service");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired JdbcClient jdbc;
  @Autowired AcademicCommandDispatcher dispatcher;
  @Autowired StudentImportValidationWorker worker;
  @Autowired ObjectMapper mapper;

  static final TestFileStore FILES = new TestFileStore();

  UUID admin = UUID.randomUUID();
  UUID adminPerson = UUID.randomUUID();
  UUID period = UUID.randomUUID();

  @BeforeEach
  void reset() {
    FILES.files.clear();
    jdbc.sql("TRUNCATE TABLE outbox_messages,processed_messages,idempotency_records,student_import_validation_work,student_import_validation_rows,student_import_validation_errors,student_import_plan_item_rebases,student_import_plan_items,student_import_processes,student_course_sections,course_sections,student_academic_eligibilities,student_number_change_history,student_academic_profiles,academic_periods,person_status_projection,account_status_projection,authorization_context_projection CASCADE").update();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',1,now())")
        .param("id", adminPerson).update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:id,:person,'USERNAME','ACTIVE',1,now())")
        .param("id", admin).param("person", adminPerson).update();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'SUPERADMIN','ACTIVE',1,now())")
        .param("id", admin).update();
    jdbc.sql("INSERT INTO academic_periods (academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at) VALUES (:id,'2026-1','Spring',current_date,current_date,'ACTIVE','PENDING_IMPORT',1,now(),now())")
        .param("id", period).update();
  }

  @Test
  void persistsLocallyValidatedRowsAndOneDurablePersonResolutionQueryPerEmail() throws Exception {
    byte[] workbook = workbook("Clinical");
    UUID importId = start(workbook, false).importId();

    worker.validatePendingImports();

    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId)).isEqualTo("VALIDATING");
    assertThat(count("student_import_validation_rows", importId)).isEqualTo(3);
    assertThat(count("student_import_validation_errors", importId)).isZero();
    assertThat(value("SELECT status::text FROM student_import_validation_work WHERE import_id=:id AND work_type='LOCAL_FILE_ACCESS'", importId)).isEqualTo("SUCCEEDED");
    assertThat(value("SELECT status::text FROM student_import_validation_work WHERE import_id=:id AND work_type='AUTHORITATIVE_PERSON_RESOLUTION'", importId)).isEqualTo("WAITING_RESULT");
    assertThat(value("SELECT work_key FROM student_import_validation_work WHERE import_id=:id AND work_type='AUTHORITATIVE_PERSON_RESOLUTION'", importId)).isEqualTo("ada@estoma.mx");
    assertThat(jdbc.sql("SELECT message_kind FROM outbox_messages WHERE message_type='ResolvePersonByInstitutionalEmail'")
        .query(String.class).single()).isEqualTo("QUERY");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='StudentImportRejected'").query(Long.class).single()).isZero();
  }

  @Test
  void rejectsConflictingNrcAndMarksFirstImportFailedWithLifecycleEvents() throws Exception {
    jdbc.sql("INSERT INTO course_sections (course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at) VALUES (:id,:period,'NRC-1','Existing name','ACTIVE',1,now(),now())")
        .param("id", UUID.randomUUID()).param("period", period).update();
    UUID importId = start(workbook("Different name"), false).importId();

    worker.validatePendingImports();

    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId)).isEqualTo("REJECTED");
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id", period)).isEqualTo("IMPORT_FAILED");
    assertThat(value("SELECT error_code FROM student_import_validation_errors WHERE import_id=:id", importId)).isEqualTo("COURSE_SECTION_NAME_CONFLICT");
    assertThat(jdbc.sql("SELECT message_type FROM outbox_messages WHERE message_type IN ('StudentImportRejected','StudentImportValidationErrorsAvailable','AcademicPeriodDataStatusChanged')")
        .query(String.class).list()).contains("StudentImportRejected", "StudentImportValidationErrorsAvailable", "AcademicPeriodDataStatusChanged");
    var batch = mapper.readTree(jdbc.sql("SELECT payload::text FROM outbox_messages WHERE message_type='StudentImportValidationErrorsAvailable' AND aggregate_id=:id")
        .param("id", importId).query(String.class).single());
    assertThat(batch.path("importId").asString()).isEqualTo(importId.toString());
    assertThat(batch.path("academicPeriodId").asString()).isEqualTo(period.toString());
    assertThat(batch.path("batchNumber").asInt()).isEqualTo(1);
    assertThat(batch.path("batchCount").asInt()).isEqualTo(1);
    assertThat(batch.path("errorCount").asInt()).isEqualTo(1);
    assertThat(batch.path("availableAt").isTextual()).isTrue();
    var error = batch.path("errors").get(0);
    assertThat(error.path("sequence").asInt()).isEqualTo(1);
    assertThat(error.path("sheet").asString()).isEqualTo("NRC");
    assertThat(error.path("row").asInt()).isEqualTo(2);
    assertThat(error.path("column").asString()).isEqualTo("nombre_materia");
    assertThat(error.path("errorCode").asString()).isEqualTo("COURSE_SECTION_NAME_CONFLICT");
    assertThat(error.path("message").asString()).isNotBlank();
    assertThat(error.has("sheetName")).isFalse();
    assertThat(error.has("rowNumber")).isFalse();
    assertThat(error.has("columnName")).isFalse();
  }

  @Test
  void rejectedReimportPreservesReadyPeriod() throws Exception {
    jdbc.sql("UPDATE academic_periods SET academic_data_status='READY' WHERE academic_period_id=:id").param("id", period).update();
    jdbc.sql("INSERT INTO course_sections (course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at) VALUES (:id,:period,'NRC-1','Existing name','ACTIVE',1,now(),now())")
        .param("id", UUID.randomUUID()).param("period", period).update();
    UUID importId = start(workbook("Different name"), true).importId();

    worker.validatePendingImports();

    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId)).isEqualTo("REJECTED");
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id", period)).isEqualTo("READY");
  }

  @Test
  void rejectsAChangedStoredObjectByChecksumAndFailsTheFirstImport() throws Exception {
    StartedImport started = start(workbook("Clinical"), false);
    FILES.files.put(started.reference(), "replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    worker.validatePendingImports();

    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", started.importId())).isEqualTo("REJECTED");
    assertThat(value("SELECT error_code FROM student_import_validation_errors WHERE import_id=:id", started.importId())).isEqualTo("INVALID_IMPORT_FILE_CHECKSUM");
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id", period)).isEqualTo("IMPORT_FAILED");
  }

  private StartedImport start(byte[] workbook, boolean confirmationAcknowledged) throws Exception {
    UUID importId = UUID.randomUUID();
    UUID reference = UUID.randomUUID();
    FILES.files.put(reference, workbook);
    var payload = mapper.createObjectNode()
        .put("importId", importId.toString())
        .put("fileReference", reference.toString())
        .put("fileChecksum", sha256(workbook))
        .put("requestedByAccountId", admin.toString());
    if (confirmationAcknowledged) payload.put("confirmationAcknowledged", true);
    assertThat(dispatcher.dispatch(command(payload)).status()).isEqualTo("SUCCEEDED");
    return new StartedImport(importId, reference);
  }

  private byte[] workbook(String courseName) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var nrc = workbook.createSheet("NRC");
      row(nrc, "nrc", "nombre_materia");
      row(nrc, "NRC-1", courseName);
      var students = workbook.createSheet("ALUMNOS");
      row(students, "matricula", "nombres", "apellido_paterno", "apellido_materno", "correo_institucional", "semestre");
      var student = students.createRow(1);
      student.createCell(0).setCellValue("ST-1");
      student.createCell(1).setCellValue("Ada");
      student.createCell(2).setCellValue("Lovelace");
      student.createCell(3).setCellValue("");
      student.createCell(4).setCellValue("ada@estoma.mx");
      student.createCell(5).setCellValue(4);
      var enrollments = workbook.createSheet("INSCRIPCIONES");
      row(enrollments, "matricula", "nrc");
      row(enrollments, "ST-1", "NRC-1");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static void row(org.apache.poi.ss.usermodel.Sheet sheet, String... values) {
    var row = sheet.createRow(sheet.getLastRowNum() == 0 && sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1);
    for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
  }

  private String value(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(String.class).single();
  }

  private long count(String table, UUID importId) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE import_id=:id").param("id", importId).query(Long.class).single();
  }

  private MessageEnvelope command(tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(UUID.randomUUID(), "StartStudentImport", 1, "COMMAND", "bff", Instant.now(), UUID.randomUUID(), null,
        UUID.randomUUID(), UUID.randomUUID().toString(), null, null, new Actor("ACCOUNT", admin, null), payload);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record StartedImport(UUID importId, UUID reference) {}

  @TestConfiguration
  static class FileStoreConfiguration {
    @Bean StudentImportFileStore studentImportFileStore() { return FILES; }
  }

  static final class TestFileStore implements StudentImportFileStore {
    final Map<UUID, byte[]> files = new ConcurrentHashMap<>();
    @Override public ByteArrayInputStream open(UUID fileReference) throws IOException {
      byte[] bytes = files.get(fileReference);
      if (bytes == null) throw new IOException("missing");
      return new ByteArrayInputStream(bytes);
    }
  }
}
