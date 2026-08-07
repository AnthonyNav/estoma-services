package com.estoma.users.messaging.inbound;

import com.estoma.users.config.MessagingProperties;
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
  private static final String TARGET_SERVICE = "users-service";

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
    if (!TARGET_SERVICE.equals(envelope.targetService())) {
      throw new InvalidMessageException(
          "expected targetService=" + TARGET_SERVICE + ", got " + envelope.targetService());
    }
    if (envelope.messageVersion() != SUPPORTED_MESSAGE_VERSION) {
      throw new InvalidMessageException(
          "unsupported messageVersion " + envelope.messageVersion() + " for " + envelope.messageType());
    }
    if (!MessageTypes.COMMANDS.contains(envelope.messageType())) {
      throw new InvalidMessageException("unrecognized command messageType " + envelope.messageType());
    }
    if (!properties.allowedProducers().contains(envelope.producer())) {
      throw new InvalidMessageException("producer not allow-listed: " + envelope.producer());
    }
    if (envelope.operationId() == null) {
      throw new InvalidMessageException("commands require operationId for result correlation");
    }
    if (envelope.actor() == null || envelope.actor().accountId() == null) {
      // A command with no identified actor is treated as structurally
      // invalid, not as a business authorization failure — none of the
      // four commands has a rejection code for "no actor" (there is no
      // authorization-service yet to even define one against;
      // docs/system-contract.md §10 D5). This guarantees every command
      // PersonCommandDispatcher ever sees has a usable actor.
      throw new InvalidMessageException("commands require actor.accountId");
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
