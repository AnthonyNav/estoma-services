package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.InboxMessageRepository;
import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.contracts.command.ExpireInboxMessagesCommand;
import com.estoma.communications.contracts.command.RegisterInboxVisitCommand;
import com.estoma.communications.contracts.command.RequestCommunicationCommand;
import com.estoma.communications.contracts.command.RetryEmailDeliveryCommand;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.domain.inbox.InboxMessage;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import com.estoma.communications.testsupport.Envelopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "estoma.communications.admin.system-allowlist=bff")
class CommandDispatcherIT extends AbstractPostgresIT {

  @Autowired private CommandDispatcher dispatcher;
  @Autowired private RecipientProjectionRepository recipientProjectionRepository;
  @Autowired private CommunicationRepository communicationRepository;
  @Autowired private EmailDeliveryRepository emailDeliveryRepository;
  @Autowired private InboxMessageRepository inboxMessageRepository;

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

     var outcome = dispatcher.dispatch(Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(), command, UUID.randomUUID(), key));

     assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
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
    assertThat(emailDeliveryRepository.findById(emailDeliveryId).orElseThrow().status()).isEqualTo(EmailDeliveryStatus.PENDING);
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
