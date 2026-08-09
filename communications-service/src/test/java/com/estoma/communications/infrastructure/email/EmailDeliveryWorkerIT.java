package com.estoma.communications.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.EmailProviderPort;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationStatus;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Verifies system_contract.md's "Email Delivery Operational Policy": the
 * provider is called with no database transaction open, and the resulting
 * state transitions (delivery status, attempt row, parent communication
 * status, derived events) are recorded correctly.
 *
 * <p>The scheduled trigger is pushed far out via {@code
 * @TestPropertySource} so tests call {@link EmailDeliveryWorker#pollAndProcess()}
 * directly and deterministically, instead of racing the real schedule.
 */
@TestPropertySource(properties = {
    "estoma.communications.email.worker.poll-interval-ms=3600000",
    "estoma.communications.email.worker.sweeper-interval-ms=3600000",
    "estoma.communications.email.worker.enabled=false",
    "estoma.communications.email.worker.batch-size=10000",
    "estoma.communications.email.templates=WELCOME"
})
class EmailDeliveryWorkerIT extends AbstractPostgresIT {

  @Autowired private EmailDeliveryWorker worker;
  @Autowired private EmailDeliveryRepository emailDeliveryRepository;
  @Autowired private CommunicationRepository communicationRepository;
  @Autowired private RecordingEmailProviderPort recordingProvider;
  @Autowired private JdbcClient jdbc;
  @Autowired @Qualifier("emailProviderExecutor") private ThreadPoolTaskExecutor providerExecutor;

  @BeforeEach
  void isolateDeliveryData() {
    jdbc.sql("DELETE FROM email_delivery_administrative_actions").update();
    jdbc.sql("DELETE FROM email_delivery_attempts").update();
    jdbc.sql("DELETE FROM email_deliveries").update();
    jdbc.sql("DELETE FROM inbox_messages").update();
    jdbc.sql("DELETE FROM communications").update();
    jdbc.sql("DELETE FROM outbox_messages").update();
    recordingProvider.calls.set(0);
    recordingProvider.lastRequest.set(null);
    recordingProvider.calledWithTransactionActive.set(false);
  }

  @Test
  void acceptedDeliveryCompletesTheCommunication() {
    UUID communicationId = insertCommunication();
    EmailDelivery delivery = insertPendingDelivery(communicationId);
    recordingProvider.nextResult.set(new EmailProviderPort.EmailSendResult.Accepted("stub", "provider-msg-1"));

    worker.pollAndProcess();

    assertThat(emailDeliveryRepository.findById(delivery.emailDeliveryId()).orElseThrow().status()).isEqualTo(EmailDeliveryStatus.ACCEPTED);
    assertThat(communicationRepository.findById(communicationId).orElseThrow().status()).isEqualTo(CommunicationStatus.COMPLETED);
    assertThat(recordingProvider.calledWithTransactionActive.get()).isFalse();
  }

  @Test
  void failedDeliveryBelowMaxAttemptsIsScheduledForRetry() {
    UUID communicationId = insertCommunication();
    EmailDelivery delivery = insertPendingDelivery(communicationId);
    recordingProvider.nextResult.set(new EmailProviderPort.EmailSendResult.Failed("stub", "PROVIDER_TIMEOUT", "timed out"));

    worker.pollAndProcess();

    EmailDelivery found = emailDeliveryRepository.findById(delivery.emailDeliveryId()).orElseThrow();
    assertThat(found.status()).isEqualTo(EmailDeliveryStatus.RETRY_SCHEDULED);
    assertThat(found.nextAttemptAt()).isNotNull();
    assertThat(communicationRepository.findById(communicationId).orElseThrow().status()).isEqualTo(CommunicationStatus.PROCESSING);
  }

  @Test
  void neverCallsTheProviderWithAnOpenTransaction() {
    UUID communicationId = insertCommunication();
    insertPendingDelivery(communicationId);
    recordingProvider.nextResult.set(new EmailProviderPort.EmailSendResult.Accepted("stub", "provider-msg-2"));

    worker.pollAndProcess();

    assertThat(recordingProvider.calledWithTransactionActive.get()).isFalse();
  }

  @Test
  void templateWithoutRendererFailsBeforeProviderAttempt() {
    UUID communicationId = insertCommunication();
    EmailDelivery delivery = EmailDelivery.template(UUID.randomUUID(), communicationId, "alumno@estoma.edu", "Alumno",
        "Estoma", "no-reply@estoma.edu", null, "WELCOME", "{\"name\":\"Alumno\"}", Instant.now());
    emailDeliveryRepository.insert(delivery);
    worker.pollAndProcess();

    EmailDelivery failed = emailDeliveryRepository.findById(delivery.emailDeliveryId()).orElseThrow();
    assertThat(failed.status()).isEqualTo(EmailDeliveryStatus.FAILED);
    assertThat(failed.attemptCount()).isZero();
    assertThat(recordingProvider.lastRequest.get()).isNull();
  }

  @Test
  void retriesExactlySixPhysicalCallsUsingTheDocumentedBackoffThenFailsTerminally() {
    UUID communicationId = insertCommunication();
    EmailDelivery delivery = insertPendingDelivery(communicationId);
    recordingProvider.nextResult.set(new EmailProviderPort.EmailSendResult.Failed("ignored", "PROVIDER_TIMEOUT", "timed out"));
    Duration[] gaps = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(24)};

    for (int call = 0; call < 6; call++) {
      Instant before = Instant.now();
      worker.pollAndProcess();
      EmailDelivery found = emailDeliveryRepository.findById(delivery.emailDeliveryId()).orElseThrow();
      assertThat(found.attemptCount()).isEqualTo(call + 1);
      assertThat(found.cycleAttemptCount()).isEqualTo(call + 1);
      if (call < 5) {
        assertThat(found.status()).isEqualTo(EmailDeliveryStatus.RETRY_SCHEDULED);
        assertThat(found.nextAttemptAt()).isBetween(before.plus(gaps[call]).minusSeconds(1), Instant.now().plus(gaps[call]).plusSeconds(1));
        jdbc.sql("UPDATE email_deliveries SET next_attempt_at = now() WHERE email_delivery_id = :id")
            .param("id", delivery.emailDeliveryId()).update();
      } else {
        assertThat(found.status()).isEqualTo(EmailDeliveryStatus.FAILED);
      }
    }

    assertThat(recordingProvider.calls.get()).isEqualTo(6);
    assertThat(jdbc.sql("SELECT count(*) FROM email_delivery_attempts WHERE email_delivery_id = :id")
        .param("id", delivery.emailDeliveryId()).query(Long.class).single()).isEqualTo(6);
    assertThat(jdbc.sql("SELECT count(*) FROM email_delivery_attempts WHERE email_delivery_id = :id AND provider = 'unknown'")
        .param("id", delivery.emailDeliveryId()).query(Long.class).single()).isZero();
  }

  @Test
  @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
  void executorRejectionRequeuesTheSamePreparedAttemptWithoutCountingACall() {
    UUID communicationId = insertCommunication();
    EmailDelivery delivery = insertPendingDelivery(communicationId);
    providerExecutor.shutdown();

    worker.pollAndProcess();

    EmailDelivery found = emailDeliveryRepository.findById(delivery.emailDeliveryId()).orElseThrow();
    assertThat(found.status()).isEqualTo(EmailDeliveryStatus.PENDING);
    assertThat(found.attemptCount()).isZero();
    assertThat(found.cycleAttemptCount()).isZero();
    assertThat(found.claimToken()).isNull();
    assertThat(jdbc.sql("SELECT status::text FROM email_delivery_attempts WHERE email_delivery_id = :id")
        .param("id", delivery.emailDeliveryId()).query(String.class).single()).isEqualTo("READY_TO_SEND");
    assertThat(jdbc.sql("SELECT claim_token FROM email_delivery_attempts WHERE email_delivery_id = :id")
        .param("id", delivery.emailDeliveryId()).query(UUID.class).optional()).isEmpty();
    assertThat(jdbc.sql("SELECT count(*) FROM email_delivery_attempts WHERE email_delivery_id = :id AND provider = 'unknown'")
        .param("id", delivery.emailDeliveryId()).query(Long.class).single()).isZero();
  }

  private UUID insertCommunication() {
    Communication communication =
        Communication.requested(
            UUID.randomUUID(), UUID.randomUUID(), "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL,
            "appointments-service", "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, java.util.List.of("EMAIL"),
            UUID.randomUUID(), UUID.randomUUID(), "fingerprint", UUID.randomUUID(), null, Instant.now());
    communicationRepository.insert(communication);
    return communication.communicationId();
  }

  private EmailDelivery insertPendingDelivery(UUID communicationId) {
    EmailDelivery delivery =
        EmailDelivery.generic(
            UUID.randomUUID(), communicationId, "alumno@estoma.edu", "Alumno", "Estoma", "no-reply@estoma.edu", null, "Cita cancelada",
            "Tu cita fue cancelada.", null, Instant.now());
    emailDeliveryRepository.insert(delivery);
    return delivery;
  }

  @TestConfiguration
  static class RecordingProviderConfig {

    @Bean
    @Primary
    RecordingEmailProviderPort recordingEmailProviderPort() {
      return new RecordingEmailProviderPort();
    }
  }

  static class RecordingEmailProviderPort implements EmailProviderPort {

    final AtomicReference<EmailSendResult> nextResult = new AtomicReference<>();
    final AtomicBoolean calledWithTransactionActive = new AtomicBoolean(false);
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<EmailSendRequest> lastRequest = new AtomicReference<>();

    @Override
    public EmailSendResult send(EmailSendRequest request) {
      calls.incrementAndGet();
      lastRequest.set(request);
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        calledWithTransactionActive.set(true);
      }
      return nextResult.get();
    }
  }
}
