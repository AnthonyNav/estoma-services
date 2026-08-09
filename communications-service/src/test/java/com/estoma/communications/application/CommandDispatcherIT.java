package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.InboxMessageRepository;
import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.EmailAttemptStatus;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.contracts.CommunicationStatus;
import com.estoma.communications.contracts.Actor;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.command.ExpireInboxMessagesCommand;
import com.estoma.communications.contracts.command.RegisterInboxVisitCommand;
import com.estoma.communications.contracts.command.RequestCommunicationCommand;
import com.estoma.communications.contracts.command.RetryEmailDeliveryCommand;
import com.estoma.communications.contracts.command.ResolveIndeterminateEmailDeliveryCommand;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import com.estoma.communications.domain.inbox.InboxMessage;
import com.estoma.communications.infrastructure.observability.ConsumerNames;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import com.estoma.communications.testsupport.Envelopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

@TestPropertySource(properties = "estoma.communications.admin.system-allowlist=bff")
class CommandDispatcherIT extends AbstractPostgresIT {

  @Autowired private CommandDispatcher dispatcher;
  @Autowired private RecipientProjectionRepository recipientProjectionRepository;
  @Autowired private CommunicationRepository communicationRepository;
  @Autowired private EmailDeliveryRepository emailDeliveryRepository;
  @Autowired private EmailDeliveryAttemptRepository emailDeliveryAttemptRepository;
  @Autowired private InboxMessageRepository inboxMessageRepository;
  @Autowired private JdbcClient jdbc;

  @Test
  void requestCommunicationWithInboxOnlyCompletesSynchronously() {
    UUID accountId = activeRecipient(null);

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(
                CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
                new RequestCommunicationCommand(
                    accountId, "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL, "appointments-service",
                    "AppointmentCancelled", "appt-" + UUID.randomUUID(), null,
                    new RequestCommunicationCommand.InboxContent("Cita cancelada", "Tu cita fue cancelada por la clínica.", null), null)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
  }

  @Test
  void requestCommunicationWithEmailStaysProcessingUntilTheWorkerActsOnIt() {
    UUID accountId = activeRecipient("alumno@estoma.edu");

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(
                CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
                new RequestCommunicationCommand(
                    accountId, "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL, "appointments-service",
                    "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, null,
                    new RequestCommunicationCommand.EmailContent(
                        EmailContentMode.GENERIC, null, "Cita cancelada", "Tu cita fue cancelada por la clínica.", null, null, null))));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
  }

  @Test
  void requestCommunicationRejectsAnEmailWithoutAContentModeDurably() {
    UUID accountId = activeRecipient("alumno@estoma.edu");
    MessageEnvelope envelope =
        Envelopes.command(
            CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
            new RequestCommunicationCommand(
                accountId, "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL, "appointments-service",
                "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, null,
                new RequestCommunicationCommand.EmailContent(
                    null, null, "Cita cancelada", "Tu cita fue cancelada por la clínica.", null, null, null)));

    assertInvalidEmailRequestIsDurablyRejected(envelope);
  }

  @Test
  void requestCommunicationRejectsMalformedEmailTemplateDataDurably() {
    UUID accountId = activeRecipient("alumno@estoma.edu");
    MessageEnvelope envelope =
        Envelopes.command(
            CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
            new RequestCommunicationCommand(
                accountId, "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL, "appointments-service",
                "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, null,
                new RequestCommunicationCommand.EmailContent(
                    EmailContentMode.TEMPLATE, null, null, null, null, "appointment-cancelled", "{malformed")));

    assertInvalidEmailRequestIsDurablyRejected(envelope);
  }

  @Test
  void requestCommunicationRejectsUnknownRecipient() {
    var outcome =
        dispatcher.dispatch(
            Envelopes.command(
                CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
                new RequestCommunicationCommand(
                    UUID.randomUUID(), "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL, "appointments-service",
                    "AppointmentCancelled", "appt-" + UUID.randomUUID(), null,
                    new RequestCommunicationCommand.InboxContent("Cita cancelada", "Tu cita fue cancelada.", null), null)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.RECIPIENT_NOT_FOUND);
  }

  @Test
  void requestCommunicationRejectsWhenNoChannelSelected() {
    UUID accountId = activeRecipient(null);

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(
                CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
                new RequestCommunicationCommand(
                    accountId, "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL, "appointments-service",
                    "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, null, null)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.NO_CHANNEL_SELECTED);
  }

  @Test
  void duplicateIdempotencyKeyIsRejectedNotDuplicated() {
    UUID accountId = activeRecipient(null);
    String sourceReferenceId = "appt-" + UUID.randomUUID();
    RequestCommunicationCommand command =
        new RequestCommunicationCommand(
            accountId, "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL, "appointments-service", "AppointmentCancelled",
            sourceReferenceId, null, new RequestCommunicationCommand.InboxContent("Cita cancelada", "Tu cita fue cancelada.", null), null);
     UUID key = UUID.randomUUID();
     dispatcher.dispatch(Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(), command, UUID.randomUUID(), key));
     long inboxBefore = tableCount("inbox_messages");
     long emailBefore = tableCount("email_deliveries");
     long domainEventsBefore = domainEventCount();

     var outcome = dispatcher.dispatch(Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(), command, UUID.randomUUID(), key));

      assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
      assertThat(tableCount("inbox_messages")).isEqualTo(inboxBefore);
      assertThat(tableCount("email_deliveries")).isEqualTo(emailBefore);
      assertThat(domainEventCount()).isEqualTo(domainEventsBefore);
  }

  @Test
  void changedRequestWithTheSameIdempotencyKeyIsDurablyRejected() {
    UUID accountId = activeRecipient(null);
    UUID key = UUID.randomUUID();
    String sourceReference = "appt-" + UUID.randomUUID();
    RequestCommunicationCommand original = inboxCommand(accountId, sourceReference, CommunicationPriority.NORMAL);
    dispatcher.dispatch(Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(), original, UUID.randomUUID(), key));
    MessageEnvelope changed = Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
        inboxCommand(accountId, sourceReference, CommunicationPriority.CRITICAL), UUID.randomUUID(), key);

    var outcome = dispatcher.dispatch(changed);

    assertThat(outcome).isEqualTo(new CommandOutcome.Rejected(RejectionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST));
    assertRejectedResult(changed.messageId(), RejectionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
  }

  @Test
  void sameFunctionalOriginWithAnotherKeyIsDurablyRejected() {
    UUID accountId = activeRecipient(null);
    String sourceReference = "appt-" + UUID.randomUUID();
    dispatcher.dispatch(Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
        inboxCommand(accountId, sourceReference, CommunicationPriority.NORMAL), UUID.randomUUID(), UUID.randomUUID()));
    MessageEnvelope duplicate = Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
        inboxCommand(accountId, sourceReference, CommunicationPriority.NORMAL), UUID.randomUUID(), UUID.randomUUID());

    var outcome = dispatcher.dispatch(duplicate);

    assertThat(outcome).isEqualTo(new CommandOutcome.Rejected(RejectionCode.COMMUNICATION_ALREADY_EXISTS));
    assertRejectedResult(duplicate.messageId(), RejectionCode.COMMUNICATION_ALREADY_EXISTS);
  }

  @Test
  void unauthorizedAdministrativeCommandIsConsumedAndProducesARejectedResult() {
    MessageEnvelope permittedShape = Envelopes.command(CommunicationsCommand.RETRY_EMAIL_DELIVERY.wireName(),
        new RetryEmailDeliveryCommand(UUID.randomUUID(), "operator retry"));
    MessageEnvelope unauthorized = new MessageEnvelope(permittedShape.messageId(), permittedShape.messageType(), permittedShape.messageVersion(),
        permittedShape.messageKind(), "untrusted", permittedShape.occurredAt(), permittedShape.correlationId(), permittedShape.causationId(),
        permittedShape.operationId(), permittedShape.idempotencyKey(), permittedShape.aggregateId(), permittedShape.aggregateVersion(),
        Actor.system("untrusted"), permittedShape.payload());

    var outcome = dispatcher.dispatch(unauthorized);

    assertThat(outcome).isEqualTo(new CommandOutcome.Rejected(RejectionCode.ACTOR_NOT_AUTHORIZED));
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE consumer_name = 'communications-service.commands' AND message_id = :id")
        .param("id", unauthorized.messageId()).query(Long.class).single()).isEqualTo(1);
    assertRejectedResult(unauthorized.messageId(), RejectionCode.ACTOR_NOT_AUTHORIZED);
  }

  @Test
  void redeliveringTheSameMessageIdIsADuplicate() {
    UUID accountId = activeRecipient(null);
    UUID messageId = UUID.randomUUID();
    var envelope =
        Envelopes.command(
            CommunicationsCommand.REQUEST_COMMUNICATION.wireName(),
            new RequestCommunicationCommand(
                accountId, "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL, "appointments-service",
                "AppointmentCancelled", "appt-" + UUID.randomUUID(), null,
                new RequestCommunicationCommand.InboxContent("Cita cancelada", "Tu cita fue cancelada.", null), null),
            messageId);

    var first = dispatcher.dispatch(envelope);
    var second = dispatcher.dispatch(envelope);

    assertThat(first).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(second).isInstanceOf(CommandOutcome.Duplicate.class);
  }

  @Test
  void registerInboxVisitSucceeds() {
    var outcome =
        dispatcher.dispatch(
            Envelopes.command(
                CommunicationsCommand.REGISTER_INBOX_VISIT.wireName(), new RegisterInboxVisitCommand(UUID.randomUUID(), "wash-ultrasonico", Instant.now())));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
  }

  @Test
  void retryEmailDeliveryRejectsUnknownDelivery() {
    var outcome =
        dispatcher.dispatch(
            Envelopes.command(CommunicationsCommand.RETRY_EMAIL_DELIVERY.wireName(), new RetryEmailDeliveryCommand(UUID.randomUUID(), "operator retry")));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.EMAIL_DELIVERY_NOT_FOUND);
  }

  @Test
  void retryEmailDeliverySucceedsForAFailedDelivery() {
    UUID emailDeliveryId = insertFailedEmailDelivery();

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(CommunicationsCommand.RETRY_EMAIL_DELIVERY.wireName(), new RetryEmailDeliveryCommand(emailDeliveryId, "operator retry")));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    EmailDelivery retried = emailDeliveryRepository.findById(emailDeliveryId).orElseThrow();
    assertThat(retried.status()).isEqualTo(EmailDeliveryStatus.PENDING);
    assertThat(retried.retryCycle()).isEqualTo(2);
    assertThat(communicationRepository.findById(retried.communicationId()).orElseThrow().status()).isEqualTo(CommunicationStatus.PROCESSING);
    assertThat(administrativeActionCount(emailDeliveryId)).isEqualTo(1);
  }

  @Test
  void resolveAcceptedUpdatesTheIndeterminateAttemptAndBackfillsLegacyCorrelation() {
    UUID emailDeliveryId = insertIndeterminateEmailDelivery(true);
    var envelope = Envelopes.command(
        CommunicationsCommand.RESOLVE_INDETERMINATE_EMAIL_DELIVERY.wireName(),
        new ResolveIndeterminateEmailDeliveryCommand(emailDeliveryId,
            ResolveIndeterminateEmailDeliveryCommand.Resolution.RECONCILED_ACCEPTED, false, "provider confirmed"));

    var outcome = dispatcher.dispatch(envelope);

    EmailDelivery delivery = emailDeliveryRepository.findById(emailDeliveryId).orElseThrow();
    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(delivery.status()).isEqualTo(EmailDeliveryStatus.ACCEPTED);
    assertThat(communicationRepository.findById(delivery.communicationId()).orElseThrow().status()).isEqualTo(CommunicationStatus.COMPLETED);
    assertThat(communicationRepository.findById(delivery.communicationId()).orElseThrow().correlationId()).isEqualTo(envelope.correlationId());
    assertThat(attemptStatus(emailDeliveryId)).isEqualTo(EmailAttemptStatus.ACCEPTED.name());
    assertThat(attemptProviderMessageId(emailDeliveryId)).isEqualTo("provider-message-id");
    assertThat(administrativeActionCount(emailDeliveryId)).isEqualTo(1);
  }

  @Test
  void resolveFailedUpdatesTheIndeterminateAttemptWhenItExists() {
    UUID emailDeliveryId = insertIndeterminateEmailDelivery(false);

    var outcome = dispatcher.dispatch(Envelopes.command(
        CommunicationsCommand.RESOLVE_INDETERMINATE_EMAIL_DELIVERY.wireName(),
        new ResolveIndeterminateEmailDeliveryCommand(emailDeliveryId,
            ResolveIndeterminateEmailDeliveryCommand.Resolution.RECONCILED_FAILED, false, "provider rejected")));

    EmailDelivery delivery = emailDeliveryRepository.findById(emailDeliveryId).orElseThrow();
    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(delivery.status()).isEqualTo(EmailDeliveryStatus.FAILED);
    assertThat(communicationRepository.findById(delivery.communicationId()).orElseThrow().status()).isEqualTo(CommunicationStatus.FAILED);
    assertThat(attemptStatus(emailDeliveryId)).isEqualTo(EmailAttemptStatus.FAILED.name());
    assertThat(administrativeActionCount(emailDeliveryId)).isEqualTo(1);
  }

  @Test
  void resolveResendDoesNotRequireAnAttemptAndRecordsTheRiskyAction() {
    UUID emailDeliveryId = insertIndeterminateEmailDelivery(false, false);

    var outcome = dispatcher.dispatch(Envelopes.command(
        CommunicationsCommand.RESOLVE_INDETERMINATE_EMAIL_DELIVERY.wireName(),
        new ResolveIndeterminateEmailDeliveryCommand(emailDeliveryId,
            ResolveIndeterminateEmailDeliveryCommand.Resolution.RESEND_AUTHORIZED, true, "resend approved")));

    EmailDelivery delivery = emailDeliveryRepository.findById(emailDeliveryId).orElseThrow();
    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(delivery.status()).isEqualTo(EmailDeliveryStatus.PENDING);
    assertThat(delivery.retryCycle()).isEqualTo(2);
    assertThat(communicationRepository.findById(delivery.communicationId()).orElseThrow().status()).isEqualTo(CommunicationStatus.PROCESSING);
    assertThat(administrativeActionCount(emailDeliveryId)).isEqualTo(1);
  }

  @Test
  void resolveAcceptedRejectsALegacyDeliveryWithoutAnIndeterminateAttemptAndDoesNotAuditIt() {
    UUID emailDeliveryId = insertIndeterminateEmailDelivery(false, false);

    var outcome = dispatcher.dispatch(Envelopes.command(
        CommunicationsCommand.RESOLVE_INDETERMINATE_EMAIL_DELIVERY.wireName(),
        new ResolveIndeterminateEmailDeliveryCommand(emailDeliveryId,
            ResolveIndeterminateEmailDeliveryCommand.Resolution.RECONCILED_ACCEPTED, false, "no provider record")));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.EMAIL_DELIVERY_NOT_INDETERMINATE);
    assertThat(emailDeliveryRepository.findById(emailDeliveryId).orElseThrow().status()).isEqualTo(EmailDeliveryStatus.INDETERMINATE);
    assertThat(administrativeActionCount(emailDeliveryId)).isZero();
  }

  @Test
  void expireInboxMessagesRejectsAnInvalidBatchSize() {
    var outcome =
        dispatcher.dispatch(Envelopes.command(CommunicationsCommand.EXPIRE_INBOX_MESSAGES.wireName(), new ExpireInboxMessagesCommand(Instant.now(), 0)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.INVALID_BATCH_SIZE);
  }

  @Test
  void expireInboxMessagesExpiresDueMessages() {
    insertExpiredInboxMessage();

    var outcome = dispatcher.dispatch(Envelopes.command(CommunicationsCommand.EXPIRE_INBOX_MESSAGES.wireName(), new ExpireInboxMessagesCommand(Instant.now(), 10)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
  }

  private UUID insertFailedEmailDelivery() {
    UUID communicationId = insertCommunication();
    EmailDelivery delivery =
        EmailDelivery.generic(
            UUID.randomUUID(), communicationId, "alumno@estoma.edu", "Alumno", "Estoma", "no-reply@estoma.edu", null, "Cita cancelada",
            "Tu cita fue cancelada.", null, Instant.now());
    emailDeliveryRepository.insert(delivery);
    EmailDelivery failed = delivery.claimedForSending(Instant.now()).failedTerminal("PROVIDER_REJECTED", "rejected", Instant.now());
    emailDeliveryRepository.update(failed);
    communicationRepository.update(communicationRepository.findById(communicationId).orElseThrow().failed(Instant.now()));
    return delivery.emailDeliveryId();
  }

  private UUID insertIndeterminateEmailDelivery(boolean legacyCommunication) {
    return insertIndeterminateEmailDelivery(legacyCommunication, true);
  }

  private UUID insertIndeterminateEmailDelivery(boolean legacyCommunication, boolean withAttempt) {
    UUID communicationId = legacyCommunication ? insertLegacyCommunication() : insertCommunication();
    Instant now = Instant.now();
    EmailDelivery delivery = EmailDelivery.generic(
        UUID.randomUUID(), communicationId, "alumno@estoma.edu", "Alumno", "Estoma", "no-reply@estoma.edu", null,
        "Cita cancelada", "Tu cita fue cancelada.", null, now);
    emailDeliveryRepository.insert(delivery);
    emailDeliveryRepository.update(delivery.claimedForSending(now).indeterminate("PROVIDER_UNKNOWN", "outcome unknown", now));
    if (withAttempt) {
      emailDeliveryAttemptRepository.insert(EmailDeliveryAttempt.reconstitute(
          UUID.randomUUID(), delivery.emailDeliveryId(), 1, 1, 1, null, "stub", EmailContentMode.GENERIC,
          EmailAttemptStatus.INDETERMINATE, "provider-message-id", "PROVIDER_UNKNOWN", "outcome unknown", now, now, now));
    }
    return delivery.emailDeliveryId();
  }

  private String attemptStatus(UUID emailDeliveryId) {
    return jdbc.sql("SELECT status::text FROM email_delivery_attempts WHERE email_delivery_id = :id")
        .param("id", emailDeliveryId).query(String.class).single();
  }

  private String attemptProviderMessageId(UUID emailDeliveryId) {
    return jdbc.sql("SELECT provider_message_id FROM email_delivery_attempts WHERE email_delivery_id = :id")
        .param("id", emailDeliveryId).query(String.class).single();
  }

  private long administrativeActionCount(UUID emailDeliveryId) {
    return jdbc.sql("SELECT count(*) FROM email_delivery_administrative_actions WHERE email_delivery_id = :id")
        .param("id", emailDeliveryId).query(Long.class).single();
  }

  private RequestCommunicationCommand inboxCommand(UUID accountId, String sourceReference, CommunicationPriority priority) {
    return new RequestCommunicationCommand(accountId, "wash-ultrasonico", "AppointmentCancelledNotice", priority, "appointments-service",
        "AppointmentCancelled", sourceReference, null, new RequestCommunicationCommand.InboxContent("Cita cancelada", "Tu cita fue cancelada.", null), null);
  }

  private long tableCount(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private long domainEventCount() {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'EVENT'").query(Long.class).single();
  }

  private void assertRejectedResult(UUID commandMessageId, RejectionCode code) {
    assertThat(jdbc.sql("SELECT message_type FROM outbox_messages WHERE causation_id = :id AND message_kind = 'RESULT'")
        .param("id", commandMessageId).query(String.class).single()).endsWith("Rejected");
    assertThat(jdbc.sql("SELECT payload::text FROM outbox_messages WHERE causation_id = :id AND message_kind = 'RESULT'")
        .param("id", commandMessageId).query(String.class).single()).contains("\"errorCode\": \"" + code.name() + "\"");
  }

  private void assertInvalidEmailRequestIsDurablyRejected(MessageEnvelope envelope) {
    long communicationsBefore = tableCount("communications");
    long inboxMessagesBefore = tableCount("inbox_messages");
    long emailDeliveriesBefore = tableCount("email_deliveries");

    assertThat(dispatcher.dispatch(envelope)).isEqualTo(new CommandOutcome.Rejected(RejectionCode.INVALID_CONTENT));
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE consumer_name = :consumerName AND message_id = :messageId")
        .param("consumerName", ConsumerNames.COMMANDS).param("messageId", envelope.messageId()).query(Long.class).single()).isEqualTo(1);
    assertRejectedResult(envelope.messageId(), RejectionCode.INVALID_CONTENT);
    assertThat(tableCount("communications")).isEqualTo(communicationsBefore);
    assertThat(tableCount("inbox_messages")).isEqualTo(inboxMessagesBefore);
    assertThat(tableCount("email_deliveries")).isEqualTo(emailDeliveriesBefore);
  }

  private void insertExpiredInboxMessage() {
    UUID communicationId = insertCommunication();
    Instant now = Instant.now();
    inboxMessageRepository.insert(InboxMessage.create(UUID.randomUUID(), communicationId, "Aviso", "Cuerpo", now.minusSeconds(60), now.minusSeconds(120)));
  }

  private UUID insertCommunication() {
    Communication communication =
        Communication.requested(
            UUID.randomUUID(), UUID.randomUUID(), "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL,
            "appointments-service", "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, java.util.List.of("EMAIL"),
            UUID.randomUUID(), UUID.randomUUID(), "fingerprint-" + UUID.randomUUID(), UUID.randomUUID(), null, Instant.now());
    communicationRepository.insert(communication);
    return communication.communicationId();
  }

  private UUID insertLegacyCommunication() {
    Communication communication = Communication.requested(
        UUID.randomUUID(), UUID.randomUUID(), "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL,
        "appointments-service", "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, Instant.now());
    communicationRepository.insert(communication);
    return communication.communicationId();
  }

  private UUID activeRecipient(String institutionalEmail) {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    recipientProjectionRepository.upsertAccountIfNewer(accountId, personId, "ACTIVE", 1, Instant.now());
    if (institutionalEmail != null) {
      recipientProjectionRepository.updatePersonIfNewer(personId, "Alumno", institutionalEmail, "ACTIVE", 1, Instant.now());
    }
    return accountId;
  }
}
