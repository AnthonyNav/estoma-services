package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.command.RequestCommunicationCommand;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import com.estoma.communications.testsupport.Envelopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommandDispatcherIT extends AbstractPostgresIT {

  @Autowired private CommandDispatcher dispatcher;
  @Autowired private RecipientProjectionRepository recipientProjectionRepository;

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
    dispatcher.dispatch(Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(), command));

    var outcome = dispatcher.dispatch(Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(), command));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.COMMUNICATION_ALREADY_EXISTS);
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
