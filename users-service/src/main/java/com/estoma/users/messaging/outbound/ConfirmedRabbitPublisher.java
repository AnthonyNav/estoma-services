package com.estoma.users.messaging.outbound;

import org.springframework.amqp.core.Message;

/**
 * The single place that decides whether a publication to RabbitMQ
 * actually happened, in the sense that matters for the Outbox and for
 * command failure routing: broker acknowledged (publisher confirm) and,
 * where required, actually routed to a queue (no {@code basic.return}).
 * {@code RabbitTemplate#send(...)} returning is NOT that signal — the
 * confirm is asynchronous (docs/system-contract.md §7).
 *
 * <p>Used by both {@code OutboxRelay} (RESULT/EVENT) and {@code
 * FailureRouter} (retry tiers/DLQ) so the confirmation semantics are
 * defined exactly once.
 *
 * <p>{@link #publishRequiredRoute} is for messages that MUST land in a
 * queue: RESULT, retry tiers, DLQ. It fails if the broker returns the
 * message as unroutable. {@link #publishEvent} is for EVENT messages,
 * whose consumers own their own queues/bindings — a still-missing binding
 * must not block this service's Outbox, so only the confirm is checked,
 * not routability.
 *
 * <p>Every call throws {@link RabbitPublishException} instead of
 * returning a value on failure — the normal return means "confirmed".
 */
public interface ConfirmedRabbitPublisher {

  void publishRequiredRoute(String exchange, String routingKey, Message message);

  void publishEvent(String exchange, String routingKey, Message message);
}
