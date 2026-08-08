package com.estoma.users.messaging.inbound;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import org.springframework.stereotype.Component;

/**
 * Structural and contractual validation of an inbound envelope, run
 * before any business processing. Anything this rejects is a permanent
 * failure — {@link InvalidMessageException} — and goes straight to the
 * DLQ, never a retry tier (docs/system-contract.md §7).
 *
 * <p>This is deliberately NOT domain validation (blank names, malformed
 * email, etc.) — that happens inside the transactional dispatcher and
 * produces a functional rejection, not a DLQ'd message. This class only
 * checks that the envelope itself is a message {@code users-service}
 * should even attempt to process.
 */
@Component
public class EnvelopeValidator {

  private static final int SUPPORTED_MESSAGE_VERSION = 1;

  private final MessagingProperties properties;

  public EnvelopeValidator(MessagingProperties properties) {
    this.properties = properties;
  }

  public void validate(MessageEnvelope envelope) {
    requireNonNull(envelope, "envelope");
    requireNonNull(envelope.messageId(), "messageId");
    requireNonNull(envelope.correlationId(), "correlationId");
    requireNonBlank(envelope.messageType(), "messageType");
    requireNonBlank(envelope.producer(), "producer");
    requireNonNull(envelope.messageKind(), "messageKind");

    if (envelope.messageKind() != MessageKind.COMMAND) {
      throw new InvalidMessageException(
          "expected messageKind=COMMAND on commands.users-service, got " + envelope.messageKind());
    }
    if (envelope.messageVersion() != SUPPORTED_MESSAGE_VERSION) {
      throw new InvalidMessageException(
          "unsupported messageVersion " + envelope.messageVersion() + " for " + envelope.messageType());
    }
    if (!MessageTypes.COMMANDS.contains(envelope.messageType())) {
      throw new InvalidMessageException("unrecognized command messageType " + envelope.messageType());
    }
    if (!properties.isCommandAllowed(envelope.producer(), envelope.messageType())) {
      throw new InvalidMessageException(
          "producer not authorized for this operation: producer="
              + envelope.producer()
              + ", messageType="
              + envelope.messageType());
    }
    if (envelope.operationId() == null) {
      throw new InvalidMessageException("commands require operationId for result correlation");
    }
    if (envelope.idempotencyKey() == null || envelope.idempotencyKey().isBlank()) {
      // commands.md §9: every modifying request is identified by an
      // idempotencyKey (the functional intention), distinct from
      // messageId (messaging-architecture.md §10.1).
      throw new InvalidMessageException("commands require a non-blank idempotencyKey");
    }
    if (!(envelope.actor() instanceof Actor.Account account) || account.accountId() == null) {
      // A command with no identified ACCOUNT actor is treated as
      // structurally invalid, not as a business authorization failure —
      // none of the four commands has a rejection code for "no actor"
      // (there is no authorization-service yet to even define one
      // against; docs/system-contract.md §10 D5). This guarantees every
      // command PersonCommandDispatcher ever sees has a usable actor. A
      // technical SYSTEM actor is never accepted here — that variant is
      // reserved for Query, validated by QueryEnvelopeValidator.
      throw new InvalidMessageException("commands require an ACCOUNT actor with accountId");
    }
  }

  private static void requireNonNull(Object value, String fieldName) {
    if (value == null) {
      throw new InvalidMessageException(fieldName + " must not be null");
    }
  }

  private static void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new InvalidMessageException(fieldName + " must not be blank");
    }
  }
}
