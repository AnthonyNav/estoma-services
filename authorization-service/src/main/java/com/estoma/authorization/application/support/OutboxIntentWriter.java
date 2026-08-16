package com.estoma.authorization.application.support;

import com.estoma.authorization.application.command.DomainEventIntent;
import com.estoma.authorization.application.command.OutboundResultIntent;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.recovery.OutboundQueryIntent;

public final class OutboxIntentWriter {

  private OutboxIntentWriter() {}

  public static void append(OutboxRepository outbox, DomainEventIntent intent) {
    outbox.append(
        new OutboxRepository.OutboxMessage(
            intent.messageId(),
            intent.messageType(),
            intent.messageVersion(),
            "EVENT",
            intent.producer(),
            intent.routingKey(),
            intent.operationId(),
            intent.idempotencyKey(),
            intent.actor(),
            intent.aggregateId(),
            intent.aggregateVersion(),
            intent.correlationId(),
            intent.causationId(),
            intent.payloadJson(),
            "PENDING",
            0,
            intent.occurredAt(),
            intent.occurredAt(),
            null,
            null,
            null,
            null,
            null,
            null));
  }

  public static void append(OutboxRepository outbox, OutboundResultIntent intent) {
    outbox.append(
        new OutboxRepository.OutboxMessage(
            intent.messageId(),
            intent.messageType(),
            intent.messageVersion(),
            "RESULT",
            intent.producer(),
            intent.routingKey(),
            intent.operationId(),
            intent.idempotencyKey(),
            intent.actor(),
            intent.aggregateId(),
            intent.aggregateVersion(),
            intent.correlationId(),
            intent.causationId(),
            intent.payloadJson(),
            "PENDING",
            0,
            intent.occurredAt(),
            intent.occurredAt(),
            null,
            null,
            null,
            null,
            null,
            null));
  }

  public static void append(OutboxRepository outbox, OutboundQueryIntent intent) {
    outbox.append(
        new OutboxRepository.OutboxMessage(
            intent.messageId(),
            intent.messageType(),
            intent.messageVersion(),
            "QUERY",
            intent.producer(),
            intent.routingKey(),
            intent.operationId(),
            null,
            intent.actor(),
            null,
            null,
            intent.correlationId(),
            intent.causationId(),
            intent.payloadJson(),
            "PENDING",
            0,
            intent.occurredAt(),
            intent.occurredAt(),
            null,
            null,
            null,
            null,
            null,
            null));
  }
}
