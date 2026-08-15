package com.estoma.academic.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.persistence.OutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Exercises real topology, technical retry routing and confirmed relay publication without timing assertions. */
@SpringBootTest(properties = {
    "spring.task.scheduling.enabled=false",
    "estoma.messaging.topology-declare=true"
})
@Testcontainers
@Tag("integration")
class AcademicMessagingIT {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("academic_db")
      .withUsername("academic_service")
      .withPassword("academic_service");
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

  static {
    POSTGRES.start();
    RABBIT.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    r.add("spring.flyway.user", POSTGRES::getUsername);
    r.add("spring.flyway.password", POSTGRES::getPassword);
    r.add("spring.rabbitmq.host", RABBIT::getHost);
    r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
  }

  @Autowired RabbitAdmin admin;
  @Autowired RabbitTemplate rabbit;
  @Autowired EnvelopeCodec codec;
  @Autowired OutboxRepository outbox;
  @Autowired OutboxRelay relay;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;

  UUID administratorAccount = UUID.randomUUID();
  UUID administratorPerson = UUID.randomUUID();

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE outbox_messages,processed_messages,idempotency_records,student_import_validation_work,student_import_validation_rows,student_import_validation_errors,student_import_plan_item_rebases,student_import_plan_items,student_import_processes,student_course_sections,course_sections,student_academic_eligibilities,student_number_change_history,student_academic_profiles,academic_periods,person_status_projection,account_status_projection,authorization_context_projection,system_access_context_projection CASCADE").update();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',1,now())")
        .param("id", administratorPerson)
        .update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:id,:person,'USERNAME','ACTIVE',1,now())")
        .param("id", administratorAccount)
        .param("person", administratorPerson)
        .update();
    admin.purgeQueue(AcademicTopology.COMMAND_QUEUE);
    admin.purgeQueue(AcademicTopology.EVENT_QUEUE);
    admin.purgeQueue(AcademicTopology.RESULT_QUEUE);
    admin.purgeQueue("commands.academic.retry.5s");
    admin.purgeQueue("commands.academic.dlq");
  }

  @Test
  void declaresTopologyAndRetriesOnlyTechnicalCommandFailures() {
    assertThat(admin.getQueueProperties(AcademicTopology.COMMAND_QUEUE)).isNotNull();
    assertThat(admin.getQueueProperties(AcademicTopology.EVENT_QUEUE)).isNotNull();
    assertThat(admin.getQueueProperties("commands.academic.retry.5s")).isNotNull();
    rabbit.send(AcademicTopology.COMMANDS, "academic", new Message("not-json".getBytes()));
    assertThat(rabbit.receive("commands.academic.retry.5s", 5_000)).isNotNull();
  }

  @Test
  void relayMarksRequiredRoutePublishedOnlyAfterBrokerConfirmation() {
    Queue queue = new Queue("results.academic.messaging-it", false, true, true);
    admin.declareQueue(queue);
    admin.declareBinding(BindingBuilder.bind(queue)
        .to(new DirectExchange(AcademicTopology.RESULTS))
        .with("messaging-it"));
    UUID id = UUID.randomUUID();
    outbox.append(
        id,
        "CreateStudentAcademicProfileResult",
        "RESULT",
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "{\"status\":\"SUCCEEDED\"}",
        "{\"destination\":\"messaging-it\"}",
        Instant.now());
    relay.relay();
    assertThat(jdbc.sql("SELECT status::text FROM outbox_messages WHERE message_id=:id")
        .param("id", id)
        .query(String.class)
        .single()).isEqualTo("PUBLISHED");
    assertThat(rabbit.receive(queue.getName(), 5_000)).isNotNull();
  }

  @Test
  void relayPublishesPersonResolutionQueriesToUsers() {
    Queue queue = new Queue("queries.users.messaging-it", false, true, true);
    admin.declareQueue(queue);
    admin.declareBinding(BindingBuilder.bind(queue)
        .to(new DirectExchange(AcademicTopology.QUERIES))
        .with("users"));
    UUID messageId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID importId = UUID.randomUUID();
    outbox.append(messageId, "ResolvePersonByInstitutionalEmail", "QUERY", importId, 1, importId, null,
        "{\"institutionalEmail\":\"ada@estoma.mx\"}",
        "{\"operationId\":\"" + operationId + "\",\"actor\":{\"type\":\"SYSTEM\",\"service\":\"academic\"}}",
        Instant.now());

    relay.relay();

    Message queryMessage = rabbit.receive(queue.getName(), 5_000);
    assertThat(queryMessage).isNotNull();
    MessageEnvelope query = codec.decode(queryMessage.getBody());
    assertThat(query.messageKind()).isEqualTo("QUERY");
    assertThat(query.messageType()).isEqualTo("ResolvePersonByInstitutionalEmail");
    assertThat(query.operationId()).isEqualTo(operationId);
    assertThat(query.actor()).isEqualTo(new Actor("SYSTEM", null, "academic"));
    assertThat(query.payload().path("institutionalEmail").asString()).isEqualTo("ada@estoma.mx");
    admin.deleteQueue(queue.getName());
  }

  @Test
  void resultListenerUsesInboxThenCompletesTheCorrelatedValidationWork() {
    PendingResolution pending = pendingResolution("ada@estoma.mx");
    UUID resultId = UUID.randomUUID();
    var resultData = mapper.createObjectNode().put("outcome", "NOT_FOUND");
    var resultPayload = mapper.createObjectNode().put("status", "SUCCEEDED");
    resultPayload.set("data", resultData);
    MessageEnvelope result = new MessageEnvelope(resultId, "ResolvePersonByInstitutionalEmailResult", 1, "RESULT", "users",
        Instant.now(), pending.importId, pending.queryId, pending.operationId, null, null, null, new Actor("SYSTEM", null, "academic"),
        resultPayload);
    Message message = new Message(codec.encode(result));
    rabbit.send(AcademicTopology.RESULTS, "academic", message);
    rabbit.send(AcademicTopology.RESULTS, "academic", message);

    await().atMost(Duration.ofSeconds(10)).until(() ->
        count("SELECT count(*) FROM processed_messages WHERE consumer_name='academic.results' AND message_id=:id", resultId) == 1
            && "VALIDATED".equals(jdbc.sql("SELECT status::text FROM student_import_processes WHERE import_id=:id").param("id", pending.importId).query(String.class).single()));
    assertThat(jdbc.sql("SELECT result->>'outcome' FROM student_import_validation_work WHERE import_id=:id AND work_type='AUTHORITATIVE_PERSON_RESOLUTION'")
        .param("id", pending.importId).query(String.class).single()).isEqualTo("NOT_FOUND");
  }

  @Test
  void inactivePersonResolutionResultRejectsTheImport() {
    PendingResolution pending = pendingResolution("inactive@estoma.mx");
    UUID resultId = UUID.randomUUID();
    var data = mapper.createObjectNode().put("outcome", "INACTIVE_MATCH").put("personId", UUID.randomUUID().toString()).put("status", "INACTIVE");
    var payload = mapper.createObjectNode().put("status", "SUCCEEDED");
    payload.set("data", data);
    MessageEnvelope result = new MessageEnvelope(resultId, "ResolvePersonByInstitutionalEmailResult", 1, "RESULT", "users",
        Instant.now(), pending.importId, pending.queryId, pending.operationId, null, null, null, new Actor("SYSTEM", null, "academic"), payload);
    rabbit.send(AcademicTopology.RESULTS, "academic", new Message(codec.encode(result)));

    await().atMost(Duration.ofSeconds(10)).until(() -> "REJECTED".equals(jdbc.sql("SELECT status::text FROM student_import_processes WHERE import_id=:id")
        .param("id", pending.importId).query(String.class).single()));
    assertThat(value("SELECT failure_code FROM student_import_processes WHERE import_id=:id", pending.importId)).isEqualTo("IDENTITY_CONFLICT");
    assertThat(jdbc.sql("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=(SELECT academic_period_id FROM student_import_processes WHERE import_id=:id)")
        .param("id", pending.importId).query(String.class).single()).isEqualTo("IMPORT_FAILED");
  }

  @Test
  void duplicateCommandMessageIdIsAppliedExactlyOnceByTheAcademicCommandsInbox() {
    UUID profile = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:admin,'SUPERADMIN','ACTIVE',1,now()),(:student,'ALUMNO','ACTIVE',1,now())")
        .param("admin", administratorAccount)
        .param("student", account)
        .update();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',1,now())")
        .param("id", person)
        .update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:id,:person,'MATRICULA','ACTIVE',1,now())")
        .param("id", account)
        .param("person", person)
        .update();
    MessageEnvelope command = command(messageId, "CreateStudentAcademicProfile", mapper.createObjectNode()
        .put("studentAcademicProfileId", profile.toString())
        .put("personId", person.toString())
        .put("accountId", account.toString())
        .put("studentNumber", "st-duplicate")
        .put("currentSemester", 5)
        .put("academicStatus", "ACTIVE"));

    rabbit.send(AcademicTopology.COMMANDS, "academic", new Message(codec.encode(command)));
    rabbit.send(AcademicTopology.COMMANDS, "academic", new Message(codec.encode(command)));

    await().atMost(Duration.ofSeconds(10)).until(() ->
        count("SELECT count(*) FROM processed_messages WHERE consumer_name='academic.commands' AND message_id=:id", messageId) == 1
            && count("SELECT count(*) FROM student_academic_profiles WHERE student_academic_profile_id=:id", profile) == 1);
    await().atMost(Duration.ofSeconds(5)).until(() ->
        "0".equals(String.valueOf(admin.getQueueProperties(AcademicTopology.COMMAND_QUEUE).get("QUEUE_MESSAGE_COUNT"))));
    assertThat(count("SELECT count(*) FROM outbox_messages WHERE causation_id=:id AND message_kind='RESULT'", messageId)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM outbox_messages WHERE causation_id=:id AND message_kind='EVENT'", messageId)).isEqualTo(2);
  }

  @Test
  void pendingPeriodFunctionalRejectionIsAcknowledgedWithoutRetryOrDlqAndRelaysRejectedResult() {
    UUID period = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'SUPERADMIN','ACTIVE',1,now())")
        .param("id", administratorAccount)
        .update();
    jdbc.sql("INSERT INTO academic_periods (academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at) VALUES (:id,'2026-1','Spring',current_date,current_date,'ACTIVE','PENDING_IMPORT',1,now(),now())")
        .param("id", period)
        .update();
    Queue results = new Queue("results.academic.pending-period-" + UUID.randomUUID(), false, true, true);
    admin.declareQueue(results);
    admin.declareBinding(BindingBuilder.bind(results)
        .to(new DirectExchange(AcademicTopology.RESULTS))
        .with("bff"));
    try {
      MessageEnvelope command = command(messageId, "RegisterCourseSection", mapper.createObjectNode()
          .put("academicPeriodId", period.toString())
          .put("courseSectionId", UUID.randomUUID().toString())
          .put("nrc", "NRC-1")
          .put("name", "Clinical"));
      rabbit.send(AcademicTopology.COMMANDS, "academic", new Message(codec.encode(command)));

      await().atMost(Duration.ofSeconds(10)).until(() ->
          count("SELECT count(*) FROM outbox_messages WHERE causation_id=:id AND message_kind='RESULT'", messageId) == 1);
      relay.relay();
      Message resultMessage = rabbit.receive(results.getName(), 5_000);
      assertThat(resultMessage).isNotNull();
      MessageEnvelope result = codec.decode(resultMessage.getBody());
      assertThat(result.messageType()).isEqualTo("RegisterCourseSectionResult");
      assertThat(result.payload().path("status").asString()).isEqualTo("REJECTED");
      assertThat(result.payload().path("error").path("code").asString()).isEqualTo("ACADEMIC_PERIOD_NOT_READY");
      assertThat(count("SELECT count(*) FROM course_sections")).isZero();
      assertThat(rabbit.receive("commands.academic.retry.5s", 1_500)).isNull();
      assertThat(rabbit.receive("commands.academic.dlq", 1_500)).isNull();
    } finally {
      admin.deleteQueue(results.getName());
    }
  }

  @Test
  void lateExternalEventWithLowerSourceVersionDoesNotOverwriteProjection() {
    UUID person = UUID.randomUUID();
    UUID current = UUID.randomUUID();
    UUID late = UUID.randomUUID();
    rabbit.send(AcademicTopology.EVENTS, "users.person.created",
        new Message(codec.encode(event(current, "PersonCreated", person, "ACTIVE", 2))));
    await().atMost(Duration.ofSeconds(10)).until(() ->
        count("SELECT count(*) FROM person_status_projection WHERE person_id=:id AND status='ACTIVE' AND source_version=2", person) == 1);
    rabbit.send(AcademicTopology.EVENTS, "users.person.deactivated",
        new Message(codec.encode(event(late, "PersonDeactivated", person, "INACTIVE", 1))));
    await().atMost(Duration.ofSeconds(10)).until(() ->
        count("SELECT count(*) FROM processed_messages WHERE consumer_name='academic.events' AND message_id=:id", late) == 1);

    assertThat(jdbc.sql("SELECT status FROM person_status_projection WHERE person_id=:id")
        .param("id", person)
        .query(String.class)
        .single()).isEqualTo("ACTIVE");
    assertThat(jdbc.sql("SELECT source_version FROM person_status_projection WHERE person_id=:id")
        .param("id", person)
        .query(Integer.class)
        .single()).isEqualTo(2);
  }

  private MessageEnvelope command(UUID messageId, String type, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        messageId,
        type,
        1,
        "COMMAND",
        "bff",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        null,
        null,
        new Actor("ACCOUNT", administratorAccount, null),
        payload);
  }

  private PendingResolution pendingResolution(String email) {
    UUID importId = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    jdbc.sql("INSERT INTO academic_periods (academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at) VALUES (:id,:code,'Spring',current_date,current_date,'ACTIVE','PENDING_IMPORT',1,now(),now())")
        .param("id", period).param("code", "2026-result-" + importId).update();
    jdbc.sql("INSERT INTO student_import_processes (import_id,academic_period_id,academic_period_code,academic_period_version,import_mode,file_reference,file_checksum,status,started_by_account_id,authorized_by_account_id,process_version,execution_generation,started_at,created_at,updated_at) VALUES (:id,:period,:code,1,'FULL',:reference,'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef','VALIDATING',:actor,:actor,1,0,now(),now(),now())")
        .param("id", importId).param("period", period).param("code", "2026-result-" + importId).param("reference", UUID.randomUUID().toString()).param("actor", administratorAccount).update();
    jdbc.sql("INSERT INTO student_import_validation_rows (import_id,row_type,sequence,sheet_name,row_number,row_data,created_at) VALUES (:id,'STUDENT',1,'ALUMNOS',2,CAST(:row AS jsonb),now())")
        .param("id", importId).param("row", "{\"studentNumber\":\"ST-1\",\"firstName\":\"Ada\",\"paternalSurname\":\"Lovelace\",\"maternalSurname\":\"\",\"institutionalEmail\":\"" + email + "\",\"semester\":1}").update();
    jdbc.sql("INSERT INTO student_import_validation_work (import_id,work_id,work_type,work_key,status,current_message_id,current_operation_id,created_at,updated_at) VALUES (:id,:work,'LOCAL_FILE_ACCESS','file','SUCCEEDED',NULL,NULL,now(),now()),(:id,:resolution,'AUTHORITATIVE_PERSON_RESOLUTION',:email,'WAITING_RESULT',:message,:operation,now(),now())")
        .param("id", importId).param("work", UUID.randomUUID()).param("resolution", UUID.randomUUID()).param("email", email).param("message", queryId).param("operation", operationId).update();
    jdbc.sql("""
        INSERT INTO outbox_messages (
          message_id,message_type,message_version,message_kind,aggregate_id,aggregate_version,
          correlation_id,payload,metadata,status,attempts,occurred_at,available_at)
        VALUES (:message,'ResolvePersonByInstitutionalEmail',1,'QUERY',:import,1,
          :import,CAST(:payload AS jsonb),CAST(:metadata AS jsonb),'PENDING',0,now(),now())
        """).param("message", queryId).param("import", importId)
        .param("payload", mapper.writeValueAsString(Map.of("institutionalEmail", email)))
        .param("metadata", mapper.writeValueAsString(Map.of("operationId", operationId.toString())))
        .update();
    return new PendingResolution(importId, queryId, operationId);
  }

  private MessageEnvelope event(UUID messageId, String type, UUID person, String status, int version) {
    return new MessageEnvelope(
        messageId,
        type,
        1,
        "EVENT",
        "users",
        Instant.now(),
        UUID.randomUUID(),
        null,
        null,
        null,
        person,
        version,
        null,
        mapper.createObjectNode().put("personId", person.toString()).put("status", status));
  }

  private long count(String sql, UUID id) {
    JdbcClient.StatementSpec statement = jdbc.sql(sql);
    if (id != null) {
      statement.param("id", id);
    }
    return statement.query(Long.class).single();
  }

  private long count(String sql) {
    return count(sql, null);
  }

  private String value(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(String.class).single();
  }

  private record PendingResolution(UUID importId, UUID queryId, UUID operationId) {}
}
