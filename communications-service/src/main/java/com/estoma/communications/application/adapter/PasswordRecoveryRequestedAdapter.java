package com.estoma.communications.application.adapter;

import com.estoma.communications.application.EventToAppend;
import com.estoma.communications.application.RequestFingerprint;
import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailSenderDirectory;
import com.estoma.communications.application.port.PasswordRecoveryDeliveryPort;
import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.application.projection.ProjectionHandler;
import com.estoma.communications.application.projection.ProjectionHandlerResult;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import com.estoma.communications.contracts.consumed.PasswordRecoveryRequestedPayload;
import com.estoma.communications.contracts.event.CommunicationRequestedPayload;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.communications.infrastructure.observability.ConsumerNames;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Translates Authentication's non-secret PasswordRecoveryRequested fact into one recovery email delivery. */
@Component
class PasswordRecoveryRequestedAdapter implements ProjectionHandler {

  private static final String SYSTEM_CODE = "GLOBAL";
  private static final String COMMUNICATION_TYPE = "PASSWORD_RECOVERY";
  private static final String SOURCE_SERVICE = "authentication";
  private static final String SOURCE_TYPE = "PasswordRecoveryRequested";
  private static final String SENDER_CODE = "default";

  private final CommunicationRepository communications;
  private final RecipientProjectionRepository recipients;
  private final PasswordRecoveryDeliveryPort recoveries;
  private final EmailSenderDirectory senders;
  private final EnvelopeJsonCodec codec;

  PasswordRecoveryRequestedAdapter(
      CommunicationRepository communications,
      RecipientProjectionRepository recipients,
      PasswordRecoveryDeliveryPort recoveries,
      EmailSenderDirectory senders,
      EnvelopeJsonCodec codec) {
    this.communications=communications; this.recipients=recipients; this.recoveries=recoveries; this.senders=senders; this.codec=codec;
  }

  @Override public List<String> consumedMessageTypes() { return List.of(ConsumedEventTypes.PASSWORD_RECOVERY_REQUESTED); }
  @Override public String consumerName() { return ConsumerNames.PASSWORD_RECOVERY_ADAPTER; }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    PasswordRecoveryRequestedPayload payload=codec.convertPayload(envelope,PasswordRecoveryRequestedPayload.class);
    validate(payload);
    if (communications.findBySourceAndIdempotency(SOURCE_SERVICE,payload.recoveryId()).isPresent()) return ProjectionHandlerResult.NONE;

    var recipient=recipients.findByAccountId(payload.accountId()).orElse(null);
    if (recipient!=null && recipient.personId()!=null && !recipient.personId().equals(payload.personId()))
      throw new IllegalArgumentException("PasswordRecoveryRequested personId does not match recipient projection");
    boolean recipientReady=recipient!=null && "ACTIVE".equals(recipient.accountStatus()) && "ACTIVE".equals(recipient.personStatus())
        && recipient.institutionalEmail()!=null && !recipient.institutionalEmail().isBlank();

    Instant now=Instant.now();
    UUID communicationId=UUID.randomUUID();
    String fingerprint=RequestFingerprint.sha256(
        SOURCE_TYPE,payload.recoveryId().toString(),payload.accountId().toString(),payload.personId().toString(),payload.expiresAt().toString());
    Communication communication=Communication.requested(
        communicationId,payload.accountId(),SYSTEM_CODE,COMMUNICATION_TYPE,CommunicationPriority.CRITICAL,
        SOURCE_SERVICE,SOURCE_TYPE,payload.recoveryId().toString(),null,List.of("EMAIL"),
        envelope.messageId(),payload.recoveryId(),fingerprint,envelope.correlationId(),envelope.messageId(),now);

    Communication persisted=communications.insert(communication);
    if (!persisted.communicationId().equals(communicationId)) return ProjectionHandlerResult.NONE;

    EmailSenderDirectory.ResolvedSender sender=senders.resolve(SENDER_CODE);
    recoveries.insert(UUID.randomUUID(),communicationId,payload.recoveryId(),payload.expiresAt(),
        recipientReady?recipient.institutionalEmail():null,recipientReady?recipient.displayName():null,
        sender.fromName(),sender.fromAddress(),SENDER_CODE,!recipientReady,now);

    EventToAppend requested=new EventToAppend(
        CommunicationsEvent.COMMUNICATION_REQUESTED.wireName(),
        new CommunicationRequestedPayload(communicationId,payload.accountId(),SYSTEM_CODE,COMMUNICATION_TYPE,
            CommunicationPriority.CRITICAL.name(),List.of("EMAIL"),"PROCESSING",now),communicationId,1);
    return ProjectionHandlerResult.of(requested);
  }

  private static void validate(PasswordRecoveryRequestedPayload payload) {
    if (payload==null || payload.recoveryId()==null || payload.accountId()==null || payload.personId()==null
        || payload.expiresAt()==null || payload.requestedAt()==null)
      throw new IllegalArgumentException("PasswordRecoveryRequested payload is incomplete");
    if (!payload.expiresAt().isAfter(payload.requestedAt()))
      throw new IllegalArgumentException("PasswordRecoveryRequested expiration must be after requestedAt");
  }
}
