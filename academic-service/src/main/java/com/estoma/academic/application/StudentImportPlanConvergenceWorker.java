package com.estoma.academic.application;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-attempts plan construction after authoritative Person-resolution Results have completed.
 *
 * <p>The Result and the corresponding Users Event are independent RabbitMQ deliveries and may
 * arrive in either order. A FOUND Result is enough to establish identity, but UPDATE_PERSON also
 * needs the local Person projection's source version. When the Result arrives first,
 * {@link StudentImportPlanBuilder#buildIfReady(UUID)} deliberately returns false; this worker
 * retries until the projection converges instead of freezing an unversioned command.
 */
@Component
public class StudentImportPlanConvergenceWorker {

  private final JdbcClient jdbc;
  private final StudentImportPlanBuilder planBuilder;

  public StudentImportPlanConvergenceWorker(JdbcClient jdbc, StudentImportPlanBuilder planBuilder) {
    this.jdbc = jdbc;
    this.planBuilder = planBuilder;
  }

  @Scheduled(fixedDelayString = "${estoma.student-import.plan-convergence-delay:5s}")
  public void convergeReadyPlans() {
    List<UUID> imports = jdbc.sql("""
        SELECT p.import_id
        FROM student_import_processes p
        WHERE p.status='VALIDATING'
          AND EXISTS (
            SELECT 1 FROM student_import_validation_work w
            WHERE w.import_id=p.import_id
              AND w.work_type='LOCAL_FILE_ACCESS'
              AND w.status='SUCCEEDED')
          AND EXISTS (
            SELECT 1 FROM student_import_validation_work w
            WHERE w.import_id=p.import_id
              AND w.work_type='AUTHORITATIVE_PERSON_RESOLUTION')
          AND NOT EXISTS (
            SELECT 1 FROM student_import_validation_work w
            WHERE w.import_id=p.import_id
              AND w.work_type='AUTHORITATIVE_PERSON_RESOLUTION'
              AND w.status <> 'SUCCEEDED')
        ORDER BY p.started_at
        """).query(UUID.class).list();

    for (UUID importId : imports) {
      planBuilder.buildIfReady(importId);
    }
  }
}
