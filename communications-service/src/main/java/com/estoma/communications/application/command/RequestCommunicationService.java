package com.estoma.communications.application.command;

import com.estoma.communications.application.EventToAppend;
import com.estoma.communications.application.RequestFingerprint;
import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.EmailSenderDirectory;
import com.estoma.communications.application.port.InboxMessageRepository;
import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.command.RequestCommunicationCommand;
import com.estoma.communications.contracts.event.CommunicationCompletedPayload;
import com.estoma.communications.contracts.event.CommunicationRequestedPayload;
import com.estoma.communications.contracts.event.InboxMessageCreatedPayload;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.domain.inbox.InboxMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Creation logic for {@code RequestCommunication} (commands.md §2-§3),
 * shared by two callers: {@link RequestCommunicationHandler} (the durable
 * command path, dispatched by {@code CommandDispatcher}) and {@code
 * AppointmentCancelledAdapter} (the in-process self-issued path,
 * dispatched by {@code ProjectionDispatcher}) -- see system_contract.md
 * "Internal Architecture" for why the adapter does not round-trip through
 * RabbitMQ. Both callers pass the same {@link RequestCommunicationCommand}
 * shape; only how they obtain one differs.
 *
 * <p>{@code communicationId} is generated here, not supplied by the
 * caller: idempotency is protected by the business key (commands.md §6),
 * not a caller-chosen id.
 *
 * <p>Duplicate detection ({@link RejectionCode#COMMUNICATION_ALREADY_EXISTS})
 * is NOT caught here -- it propagates to the caller, which decides how to
 * handle it: {@code RequestCommunicationHandler} lets {@code
 * CommandDispatcher} turn it into a durable {@code Rejected} result; the
 * adapter treats it as an idempotent no-op.
 */
@Component
public class RequestCommunicationService {

  private final CommunicationRepository communicationRepository;
  private final InboxMessageRepository inboxMessageRepository;
  private final EmailDeliveryRepository emailDeliveryRepository;
  private final RecipientProjectionRepository recipientProjectionRepository;
  private final EmailSenderDirectory emailSenderDirectory;

  RequestCommunicationService(
      CommunicationRepository communicationRepository,
      InboxMessageRepository inboxMessageRepository,
      EmailDeliveryRepository emailDeliveryRepository,
      RecipientProjectionRepository recipientProjectionRepository,
      EmailSenderDirectory emailSenderDirectory) {
    this.communicationRepository = communicationRepository;
    this.inboxMessageRepository = inboxMessageRepository;
    this.emailDeliveryRepository = emailDeliveryRepository;
    this.recipientProjectionRepository = recipientProjectionRepository;
    this.emailSenderDirectory = emailSenderDirectory;
  }

  public CommandHandlerResult create(RequestCommunicationCommand command) {
    throw new CommunicationsRejectedException(RejectionCode.INVALID_CONTENT,
        "durable communication requests require an envelope correlationId and idempotencyKey");
  }

  public CommandHandlerResult create(RequestCommunicationCommand command, MessageEnvelope envelope) {
    if (command.inbox() == null && command.email() == null) {
      throw new CommunicationsRejectedException(RejectionCode.NO_CHANNEL_SELECTED, "at least one channel (inbox or email) is required");
    }
    if (command.inbox() != null && (isBlank(command.inbox().title()) || isBlank(command.inbox().body()))) {
      throw new CommunicationsRejectedException(RejectionCode.INVALID_CONTENT, "inbox content requires a non-blank title and body");
    }
    validateEmailContent(command.email());

    RecipientProjectionRepository.RecipientProjectionRecord recipient =
        recipientProjectionRepository
            .findByAccountId(command.recipientAccountId())
            .orElseThrow(
                () -> new CommunicationsRejectedException(RejectionCode.RECIPIENT_NOT_FOUND, "recipient " + command.recipientAccountId() + " is not known"));
    if (!"ACTIVE".equals(recipient.accountStatus()) || (recipient.personStatus() != null && !"ACTIVE".equals(recipient.personStatus()))) {
      throw new CommunicationsRejectedException(RejectionCode.RECIPIENT_INACTIVE, "recipient " + command.recipientAccountId() + " is not active");
    }
    if (command.email() != null && recipient.institutionalEmail() == null) {
      throw new CommunicationsRejectedException(
          RejectionCode.RECIPIENT_NOT_FOUND, "recipient " + command.recipientAccountId() + " has no known institutional email yet");
    }

    if (envelope != null && envelope.correlationId() == null) {
      throw new CommunicationsRejectedException(RejectionCode.INVALID_CONTENT, "correlationId is required for new communications");
    }
    if (envelope != null && envelope.idempotencyKey() == null) {
      throw new CommunicationsRejectedException(RejectionCode.INVALID_CONTENT, "idempotencyKey is required for durable communication requests");
    }
    Instant now = Instant.now();
    UUID communicationId = UUID.randomUUID();
    List<String> requestedChannels = command.requestedChannels() == null
        ? deriveChannels(command) : List.copyOf(command.requestedChannels());
    if (requestedChannels.isEmpty()) {
      throw new CommunicationsRejectedException(RejectionCode.NO_CHANNEL_SELECTED, "requestedChannels must not be empty");
    }
    if (!requestedChannels.equals(deriveChannels(command))) {
      throw new CommunicationsRejectedException(RejectionCode.INVALID_CONTENT,
          "requestedChannels must match the supplied inbox and email channels");
    }
    String fingerprint = RequestFingerprint.sha256(command.recipientAccountId().toString(), command.systemCode(),
        command.communicationType(), command.sourceService(), command.sourceReferenceType(), command.sourceReferenceId(),
        String.join(",", requestedChannels),
        command.inbox() == null ? null : command.inbox().title(),
        command.inbox() == null ? null : command.inbox().body(),
        command.inbox() == null || command.inbox().expiresAt() == null ? null : command.inbox().expiresAt().toString(),
        command.email() == null ? null : command.email().contentMode().name(),
        command.email() == null ? null : command.email().senderCode(),
        command.email() == null ? null : command.email().subject(),
        command.email() == null ? null : command.email().textBody(),
        command.email() == null ? null : command.email().htmlBody(),
        command.email() == null ? null : command.email().templateCode(),
        command.email() == null ? null : command.email().templateData());
    Communication communication =
        Communication.requested(
            communicationId, command.recipientAccountId(), command.systemCode(), command.communicationType(), command.priority(),
            command.sourceService(), command.sourceReferenceType(), command.sourceReferenceId(), command.initiatedByAccountId(),
            requestedChannels, envelope == null ? null : envelope.messageId(), envelope == null ? null : envelope.idempotencyKey(),
         fingerprint, envelope == null ? null : envelope.correlationId(), envelope == null ? null : envelope.causationId(), now);
     Communication persisted = communicationRepository.insert(communication);
     if (!persisted.communicationId().equals(communication.communicationId())) {
       return CommandHandlerResult.of(persisted.communicationId(), 1);
     }

    List<EventToAppend> events = new ArrayList<>();

    InboxMessage inboxMessage = null;
    if (command.inbox() != null) {
      inboxMessage = InboxMessage.create(UUID.randomUUID(), communicationId, command.inbox().title(), command.inbox().body(), command.inbox().expiresAt(), now);
      inboxMessageRepository.insert(inboxMessage);
    }
    if (command.email() != null) {
      EmailSenderDirectory.ResolvedSender sender = emailSenderDirectory.resolve(command.email().senderCode());
      EmailDelivery delivery =
          command.email().contentMode() == com.estoma.communications.contracts.EmailContentMode.TEMPLATE
              ? EmailDelivery.template(
                  UUID.randomUUID(), communicationId, recipient.institutionalEmail(), recipient.displayName(), sender.fromName(), sender.fromAddress(),
                  null, command.email().templateCode(), command.email().templateData(), now)
              : EmailDelivery.generic(
                  UUID.randomUUID(), communicationId, recipient.institutionalEmail(), recipient.displayName(), sender.fromName(), sender.fromAddress(),
                  null, command.email().subject(), command.email().textBody(), command.email().htmlBody(), now);
      emailDeliveryRepository.insert(delivery);
    }

    events.add(
        new EventToAppend(
            CommunicationsEvent.COMMUNICATION_REQUESTED.wireName(),
            new CommunicationRequestedPayload(
                communicationId, command.recipientAccountId(), command.systemCode(), command.communicationType(), command.priority().name(),
                requestedChannels, communication.status().name(), now)));
    if (inboxMessage != null) {
      events.add(
          new EventToAppend(
              CommunicationsEvent.INBOX_MESSAGE_CREATED.wireName(),
              new InboxMessageCreatedPayload(
                  inboxMessage.inboxMessageId(), communicationId, command.recipientAccountId(), command.systemCode(), command.communicationType(),
                  inboxMessage.title(), inboxMessage.body(), inboxMessage.createdAt(), inboxMessage.expiresAt())));
    }

    // No email channel: the inbox channel (if any) is synchronously
    // satisfied by this point, so the communication is already complete --
    // see Communication's javadoc for the completion rule.
    if (command.email() == null) {
      Communication completed = communication.completed(now);
       if (communicationRepository.update(completed) != 1) throw new IllegalStateException("communication claim lost while completing");
      events.add(
          new EventToAppend(
              CommunicationsEvent.COMMUNICATION_COMPLETED.wireName(),
              new CommunicationCompletedPayload(
                  communicationId, command.recipientAccountId(), command.systemCode(), command.communicationType(), requestedChannels, now)));
    }

    return CommandHandlerResult.of(communicationId, 1, events.toArray(EventToAppend[]::new));
  }

  private void validateEmailContent(RequestCommunicationCommand.EmailContent email) {
    if (email == null) {
      return;
    }
    boolean genericMissingFields =
        email.contentMode() == com.estoma.communications.contracts.EmailContentMode.GENERIC && (isBlank(email.subject()) || isBlank(email.textBody()));
    boolean templateMissingFields =
        email.contentMode() == com.estoma.communications.contracts.EmailContentMode.TEMPLATE && isBlank(email.templateCode());
    if (genericMissingFields || templateMissingFields) {
      throw new CommunicationsRejectedException(RejectionCode.INVALID_CONTENT, "email content is missing required fields for its content mode");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static List<String> deriveChannels(RequestCommunicationCommand command) {
    List<String> channels = new ArrayList<>();
    if (command.inbox() != null) channels.add("INBOX");
    if (command.email() != null) channels.add("EMAIL");
    return channels;
  }
}
