package com.estoma.academic.importing;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.domain.StudentAcademicProfile;
import com.estoma.academic.messaging.EnvelopeCodec;
import com.estoma.academic.persistence.OutboxRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Performs only validations that Academic can decide locally and persists the remaining work. */
@Component
public class StudentImportValidationWorker {
  private static final Logger log = LoggerFactory.getLogger(StudentImportValidationWorker.class);
  private static final String LOCAL_FILE_ACCESS = "LOCAL_FILE_ACCESS";
  private static final String PERSON_RESOLUTION = "AUTHORITATIVE_PERSON_RESOLUTION";
  private static final Actor SYSTEM_ACTOR = new Actor("SYSTEM", null, "academic");

  private final JdbcClient jdbc;
  private final Optional<StudentImportFileStore> fileStore;
  private final StreamingImportWorkbookParser parser;
  private final StudentImportProperties properties;
  private final OutboxRepository outbox;
  private final EnvelopeCodec codec;

  public StudentImportValidationWorker(
      JdbcClient jdbc,
      Optional<StudentImportFileStore> fileStore,
      StreamingImportWorkbookParser parser,
      StudentImportProperties properties,
      OutboxRepository outbox,
      EnvelopeCodec codec) {
    this.jdbc = jdbc;
    this.fileStore = fileStore;
    this.parser = parser;
    this.properties = properties;
    this.outbox = outbox;
    this.codec = codec;
  }

  @Scheduled(fixedDelayString = "${estoma.student-import.validation-delay:5s}")
  public void scheduledValidation() {
    validatePendingImports();
  }

  @Transactional
  public void validatePendingImports() {
    Instant now = Instant.now();
    retryDuePersonResolutions(now);
    if (fileStore.isEmpty()) return;
    ImportProcess process = nextImport();
    if (process == null) return;

    try {
      ImportWorkbook workbook = readWorkbook(process);
      persistRows(process.importId, workbook, now);
      List<ValidationError> errors = validate(process, workbook);
      if (!errors.isEmpty()) {
        persistErrors(process.importId, errors, now);
        reject(process, errors, now);
        return;
      }
      markLocalFileAccessSucceeded(process.importId, now);
      persistPersonResolutionWork(process, now);
    } catch (InvalidImportWorkbookException ex) {
      persistErrors(process.importId, List.of(new ValidationError(
          ex.code(), null, null, null, "The import workbook is invalid")), now);
      reject(process, List.of(new ValidationError(
          ex.code(), null, null, null, "The import workbook is invalid")), now);
    } catch (IOException ex) {
      recordFileAccessFailure(process.importId, now);
    }
  }

  private ImportProcess nextImport() {
    return jdbc.sql("""
        SELECT p.import_id,p.academic_period_id,p.file_reference,p.file_checksum,p.process_version,
               period.academic_data_status::text,period.version
        FROM student_import_processes p
        JOIN academic_periods period ON period.academic_period_id=p.academic_period_id
        WHERE p.status='VALIDATING'
          AND NOT EXISTS (
            SELECT 1 FROM student_import_validation_work work
            WHERE work.import_id=p.import_id AND work.work_type=:personResolution)
          AND NOT EXISTS (
            SELECT 1 FROM student_import_validation_work work
            WHERE work.import_id=p.import_id AND work.work_type=:localFileAccess
              AND work.status='PENDING' AND work.next_attempt_at > now())
        ORDER BY p.started_at
        FOR UPDATE OF p SKIP LOCKED
        LIMIT 1
        """)
        .param("personResolution", PERSON_RESOLUTION)
        .param("localFileAccess", LOCAL_FILE_ACCESS)
        .query((rs, n) -> new ImportProcess(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
            rs.getInt(5), rs.getString(6), rs.getInt(7)))
        .optional().orElse(null);
  }

  private ImportWorkbook readWorkbook(ImportProcess process) throws IOException {
    UUID reference;
    try {
      reference = UUID.fromString(process.fileReference);
    } catch (IllegalArgumentException ex) {
      throw new InvalidImportWorkbookException("INVALID_IMPORT_FILE_REFERENCE");
    }
    try (InputStream source = fileStore.orElseThrow().open(reference);
         BoundedDigestInputStream input = new BoundedDigestInputStream(source, properties.maxObjectBytes())) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      input.transferTo(bytes);
      String actual = HexFormat.of().formatHex(input.digest());
      if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          process.fileChecksum.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_FILE_CHECKSUM");
      }
      return parser.parse(new ByteArrayInputStream(bytes.toByteArray()));
    }
  }

  private void persistRows(UUID importId, ImportWorkbook workbook, Instant now) {
    jdbc.sql("DELETE FROM student_import_validation_rows WHERE import_id=:id").param("id", importId).update();
    int sequence = 1;
    for (ImportWorkbook.CourseSection row : workbook.courseSections()) {
      insertRow(importId, "NRC", sequence++, "NRC", row.rowNumber(),
          map("nrc", nfc(row.nrc()), "name", nfc(row.name())), now);
    }
    sequence = 1;
    for (ImportWorkbook.Student row : workbook.students()) {
      insertRow(importId, "STUDENT", sequence++, "ALUMNOS", row.rowNumber(), map(
          "studentNumber", nfc(row.studentNumber()), "firstName", nfc(row.firstName()),
          "paternalSurname", nfc(row.paternalSurname()), "maternalSurname", nfc(row.maternalSurname()),
          "institutionalEmail", nfc(row.institutionalEmail()).toLowerCase(Locale.ROOT),
          "semester", row.semester()), now);
    }
    sequence = 1;
    for (ImportWorkbook.Enrollment row : workbook.enrollments()) {
      insertRow(importId, "ENROLLMENT", sequence++, "INSCRIPCIONES", row.rowNumber(),
          map("studentNumber", nfc(row.studentNumber()), "nrc", nfc(row.nrc())), now);
    }
  }

  private void insertRow(
      UUID importId,
      String type,
      int sequence,
      String sheet,
      int rowNumber,
      Map<String, Object> row,
      Instant now) {
    jdbc.sql("""
        INSERT INTO student_import_validation_rows (import_id,row_type,sequence,sheet_name,row_number,row_data,created_at)
        VALUES (:id,:type,:sequence,:sheet,:rowNumber,CAST(:row AS jsonb),:now)
        """).param("id", importId).param("type", type).param("sequence", sequence).param("sheet", sheet)
        .param("rowNumber", rowNumber).param("row", codec.json(row))
        .param("now", java.sql.Timestamp.from(now)).update();
  }

  private List<ValidationError> validate(ImportProcess process, ImportWorkbook workbook) {
    List<ValidationError> errors = new ArrayList<>();
    Map<String, ImportWorkbook.CourseSection> nrcs = new HashMap<>();
    for (ImportWorkbook.CourseSection row : workbook.courseSections()) {
      String nrc = nfc(row.nrc());
      if (nrcs.putIfAbsent(nrc, row) != null) {
        errors.add(error("DUPLICATE_NRC", "NRC", row.rowNumber(), "nrc",
            "NRC is repeated in the workbook"));
      }
    }
    Map<String, String> currentNames = jdbc.sql(
            "SELECT nrc,name FROM course_sections WHERE academic_period_id=:period")
        .param("period", process.periodId)
        .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).list()
        .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    for (ImportWorkbook.CourseSection row : workbook.courseSections()) {
      String existing = currentNames.get(nfc(row.nrc()));
      if (existing != null && !nfc(existing).equals(nfc(row.name()))) {
        errors.add(error("COURSE_SECTION_NAME_CONFLICT", "NRC", row.rowNumber(), "nombre_materia",
            "NRC conflicts with the course-section name in this academic period"));
      }
    }
    Map<String, ImportWorkbook.Student> students = new HashMap<>();
    for (ImportWorkbook.Student row : workbook.students()) {
      String number = StudentAcademicProfile.normalizedStudentNumber(nfc(row.studentNumber()));
      if (students.putIfAbsent(number, row) != null) {
        errors.add(error("DUPLICATE_STUDENT_NUMBER", "ALUMNOS", row.rowNumber(), "matricula",
            "Student number is repeated in the workbook"));
      }
    }
    Map<String, ImportWorkbook.Enrollment> enrollments = new HashMap<>();
    for (ImportWorkbook.Enrollment row : workbook.enrollments()) {
      String student = StudentAcademicProfile.normalizedStudentNumber(nfc(row.studentNumber()));
      String nrc = nfc(row.nrc());
      if (!students.containsKey(student)) {
        errors.add(error("ENROLLMENT_STUDENT_NOT_FOUND", "INSCRIPCIONES", row.rowNumber(), "matricula",
            "Enrollment references a student absent from ALUMNOS"));
      }
      if (!nrcs.containsKey(nrc)) {
        errors.add(error("ENROLLMENT_NRC_NOT_FOUND", "INSCRIPCIONES", row.rowNumber(), "nrc",
            "Enrollment references an NRC absent from NRC"));
      }
      if (enrollments.putIfAbsent(student + "\u0000" + nrc, row) != null) {
        errors.add(error("DUPLICATE_ENROLLMENT", "INSCRIPCIONES", row.rowNumber(), null,
            "Student enrollment is repeated in the workbook"));
      }
    }
    return errors;
  }

  private void persistErrors(UUID importId, List<ValidationError> errors, Instant now) {
    jdbc.sql("DELETE FROM student_import_validation_errors WHERE import_id=:id").param("id", importId).update();
    for (int i = 0; i < errors.size(); i++) {
      ValidationError error = errors.get(i);
      jdbc.sql("""
          INSERT INTO student_import_validation_errors (import_id,sequence,error_code,sheet_name,row_number,column_name,message,created_at)
          VALUES (:id,:sequence,:code,:sheet,:row,:column,:message,:now)
          """).param("id", importId).param("sequence", i + 1).param("code", error.code)
          .param("sheet", error.sheet).param("row", error.row).param("column", error.column)
          .param("message", error.message).param("now", java.sql.Timestamp.from(now)).update();
    }
  }

  private void reject(ImportProcess process, List<ValidationError> errors, Instant now) {
    ValidationError first = errors.getFirst();
    int version = process.version + 1;
    jdbc.sql("""
        UPDATE student_import_processes
        SET status='REJECTED',process_version=:version,failed_step='VALIDATION',failure_code=:code,
            failure_reason=:reason,recoverable=false,failed_at=:now,updated_at=:now
        WHERE import_id=:id AND status='VALIDATING' AND process_version=:expected
        """).param("version", version).param("code", first.code).param("reason", first.message)
        .param("now", java.sql.Timestamp.from(now)).param("id", process.importId)
        .param("expected", process.version).update();
    append("StudentImportRejected", process.importId, version, map(
        "importId", process.importId, "academicPeriodId", process.periodId, "failureCode", first.code,
        "errorCount", errors.size(), "rejectedAt", now), now);
    appendErrorBatches(process.importId, version, errors, now);
    if ("PENDING_IMPORT".equals(process.periodDataStatus)) {
      jdbc.sql("""
          UPDATE academic_periods SET academic_data_status='IMPORT_FAILED',version=version+1,updated_at=:now
          WHERE academic_period_id=:id AND academic_data_status='PENDING_IMPORT' AND version=:expected
          """).param("id", process.periodId).param("expected", process.periodVersion)
          .param("now", java.sql.Timestamp.from(now)).update();
      append("AcademicPeriodDataStatusChanged", process.periodId, process.periodVersion + 1, map(
          "academicPeriodId", process.periodId, "previousAcademicDataStatus", "PENDING_IMPORT",
          "academicDataStatus", "IMPORT_FAILED", "reason", "IMPORT_VALIDATION_REJECTED", "changedAt", now), now);
    }
  }

  private void appendErrorBatches(UUID importId, int version, List<ValidationError> errors, Instant now) {
    int batchSize = properties.validationErrorBatchSize();
    int batchCount = (errors.size() + batchSize - 1) / batchSize;
    for (int batch = 0; batch < batchCount; batch++) {
      List<Map<String, Object>> details = new ArrayList<>();
      for (int i = batch * batchSize; i < Math.min(errors.size(), (batch + 1) * batchSize); i++) {
        ValidationError error = errors.get(i);
        details.add(map("sequence", i + 1, "sheet", error.sheet, "row", error.row,
            "column", error.column, "errorCode", error.code, "message", error.message));
      }
      append("StudentImportValidationErrorsAvailable", importId, version, map(
          "importId", importId, "academicPeriodId", periodId(importId),
          "batchNumber", batch + 1, "batchCount", batchCount,
          "errorCount", errors.size(), "errors", details, "availableAt", now), now);
    }
  }

  private void markLocalFileAccessSucceeded(UUID importId, Instant now) {
    jdbc.sql("""
        INSERT INTO student_import_validation_work (import_id,work_id,work_type,work_key,status,attempts,created_at,updated_at)
        VALUES (:importId,:workId,:type,'file','SUCCEEDED',1,:now,:now)
        ON CONFLICT (import_id,work_type,work_key) DO UPDATE
        SET status='SUCCEEDED',next_attempt_at=NULL,last_error_code=NULL,last_error_at=NULL,updated_at=EXCLUDED.updated_at
        """).param("importId", importId).param("workId", UUID.randomUUID()).param("type", LOCAL_FILE_ACCESS)
        .param("now", java.sql.Timestamp.from(now)).update();
  }

  private void persistPersonResolutionWork(ImportProcess process, Instant now) {
    List<String> emails = jdbc.sql("""
        SELECT DISTINCT lower(row_data->>'institutionalEmail')
        FROM student_import_validation_rows
        WHERE import_id=:importId AND row_type='STUDENT'
        ORDER BY lower(row_data->>'institutionalEmail')
        """).param("importId", process.importId).query(String.class).list();
    for (String email : emails) {
      UUID messageId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();
      int inserted = jdbc.sql("""
          INSERT INTO student_import_validation_work
            (import_id,work_id,work_type,work_key,status,current_message_id,current_operation_id,
             attempts,next_attempt_at,created_at,updated_at)
          VALUES (:importId,:workId,:type,:email,'WAITING_RESULT',:messageId,:operationId,
                  1,:next,:now,:now)
          ON CONFLICT (import_id,work_type,work_key) DO NOTHING
          """).param("importId", process.importId).param("workId", UUID.randomUUID())
          .param("type", PERSON_RESOLUTION).param("email", email).param("messageId", messageId)
          .param("operationId", operationId)
          .param("next", java.sql.Timestamp.from(now.plusSeconds(validationRetryDelay(1))))
          .param("now", java.sql.Timestamp.from(now)).update();
      if (inserted == 1) {
        appendPersonResolutionQuery(process.importId, process.version, email, messageId, operationId, now);
      }
    }
  }

  private void retryDuePersonResolutions(Instant now) {
    List<ResolutionRetry> due = jdbc.sql("""
        SELECT work.import_id,work.work_id,work.work_key,work.current_operation_id,work.attempts,p.process_version
        FROM student_import_validation_work work
        JOIN student_import_processes p ON p.import_id=work.import_id
        WHERE work.work_type=:type AND work.status='WAITING_RESULT' AND p.status='VALIDATING'
          AND (work.next_attempt_at IS NULL OR work.next_attempt_at <= :now)
        ORDER BY work.updated_at,work.work_id
        FOR UPDATE OF work SKIP LOCKED
        LIMIT 200
        """).param("type", PERSON_RESOLUTION).param("now", java.sql.Timestamp.from(now))
        .query((rs, n) -> new ResolutionRetry(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getObject(4, UUID.class), rs.getInt(5), rs.getInt(6)))
        .list();
    for (ResolutionRetry retry : due) {
      UUID messageId = UUID.randomUUID();
      int attempts = retry.attempts + 1;
      Instant next = now.plusSeconds(validationRetryDelay(attempts));
      int changed = jdbc.sql("""
          UPDATE student_import_validation_work
          SET current_message_id=:message,attempts=:attempts,next_attempt_at=:next,
              last_error_code='PERSON_RESOLUTION_RESULT_TIMEOUT',last_error_at=:now,updated_at=:now
          WHERE import_id=:import AND work_id=:work AND status='WAITING_RESULT'
            AND current_operation_id=:operation
          """).param("message", messageId).param("attempts", attempts)
          .param("next", java.sql.Timestamp.from(next)).param("now", java.sql.Timestamp.from(now))
          .param("import", retry.importId).param("work", retry.workId).param("operation", retry.operationId)
          .update();
      if (changed == 1) {
        appendPersonResolutionQuery(
            retry.importId, retry.processVersion, retry.email, messageId, retry.operationId, now);
        alertIfRepeated("person-resolution", retry.importId, attempts);
      }
    }
  }

  private void appendPersonResolutionQuery(
      UUID importId,
      int processVersion,
      String email,
      UUID messageId,
      UUID operationId,
      Instant now) {
    outbox.append(messageId, "ResolvePersonByInstitutionalEmail", "QUERY", importId, processVersion,
        importId, null, codec.json(map("institutionalEmail", email)),
         codec.json(map("destination", "users", "operationId", operationId, "actor", SYSTEM_ACTOR)), now);
  }

  private void recordFileAccessFailure(UUID importId, Instant now) {
    int previousAttempts = jdbc.sql("""
        SELECT attempts FROM student_import_validation_work
        WHERE import_id=:id AND work_type=:type AND work_key='file'
        """).param("id", importId).param("type", LOCAL_FILE_ACCESS)
        .query(Integer.class).optional().orElse(0);
    int attempts = previousAttempts + 1;
    Instant next = now.plusSeconds(validationRetryDelay(attempts));
    jdbc.sql("""
        INSERT INTO student_import_validation_work
          (import_id,work_id,work_type,work_key,status,attempts,next_attempt_at,
           last_error_code,last_error_at,created_at,updated_at)
        VALUES (:importId,:workId,:type,'file','PENDING',:attempts,:next,
                'IMPORT_FILE_UNAVAILABLE',:now,:now,:now)
        ON CONFLICT (import_id,work_type,work_key) DO UPDATE
        SET status='PENDING',attempts=:attempts,next_attempt_at=:next,
            last_error_code='IMPORT_FILE_UNAVAILABLE',last_error_at=:now,updated_at=:now
        """).param("importId", importId).param("workId", UUID.randomUUID()).param("type", LOCAL_FILE_ACCESS)
        .param("attempts", attempts).param("next", java.sql.Timestamp.from(next))
        .param("now", java.sql.Timestamp.from(now)).update();
    alertIfRepeated("file-access", importId, attempts);
  }

  private void alertIfRepeated(String operation, UUID importId, int attempts) {
    if (attempts >= 8) {
      log.error("student import validation requires operational attention importId={} operation={} attempts={}",
          importId, operation, attempts);
    }
  }

  private static long validationRetryDelay(int attempts) {
    return switch (attempts) {
      case 1 -> 5;
      case 2 -> 30;
      case 3 -> 300;
      default -> 900;
    };
  }

  private void append(String type, UUID aggregateId, int version, Map<String, Object> payload, Instant now) {
    outbox.append(UUID.randomUUID(), type, "EVENT", aggregateId, version, aggregateId, null,
        codec.json(payload), codec.json(map("actor", SYSTEM_ACTOR)), now);
  }

  private UUID periodId(UUID importId) {
    return jdbc.sql("SELECT academic_period_id FROM student_import_processes WHERE import_id=:id")
        .param("id", importId).query(UUID.class).single();
  }

  private static ValidationError error(String code, String sheet, int row, String column, String message) {
    return new ValidationError(code, sheet, row, column, message);
  }

  private static String nfc(String value) {
    return java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFC);
  }

  private static Map<String, Object> map(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
    return result;
  }

  private record ImportProcess(
      UUID importId,
      UUID periodId,
      String fileReference,
      String fileChecksum,
      int version,
      String periodDataStatus,
      int periodVersion) {}
  private record ResolutionRetry(
      UUID importId, UUID workId, String email, UUID operationId, int attempts, int processVersion) {}
  private record ValidationError(String code, String sheet, Integer row, String column, String message) {}

  private static final class BoundedDigestInputStream extends FilterInputStream {
    private final MessageDigest digest;
    private final int maximum;
    private int count;

    BoundedDigestInputStream(InputStream input, int maximum) {
      super(input);
      this.maximum = maximum;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value >= 0) accept(new byte[] {(byte) value}, 0, 1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = in.read(bytes, offset, length);
      if (read > 0) accept(bytes, offset, read);
      return read;
    }

    byte[] digest() {
      return digest.digest();
    }

    private void accept(byte[] bytes, int offset, int length) {
      count += length;
      if (count > maximum) throw new InvalidImportWorkbookException("INVALID_IMPORT_FILE_SIZE");
      digest.update(bytes, offset, length);
    }
  }
}
