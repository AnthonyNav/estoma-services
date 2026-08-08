package com.estoma.communications.application.projection;

import com.estoma.communications.contracts.MessageEnvelope;
import java.util.List;

/**
 * One handler per group of consumed event {@code messageType}s. Unlike
 * {@code authentication-service}'s equivalent interface, {@link
 * #consumerName} is declared per handler, not fixed to one dispatcher-wide
 * constant: {@code communications-service} has more than one conceptually
 * independent consumer from day one (see {@code
 * infrastructure.observability.ConsumerNames}), and Phase 3 declares them
 * as separate physical queues, so the Consumer Inbox identity must already
 * be per-handler in Phase 2.
 *
 * <p>Implementations do not throw a functional rejection: events have no
 * result channel to reject into.
 */
public interface ProjectionHandler {

  List<String> consumedMessageTypes();

  String consumerName();

  ProjectionHandlerResult handle(MessageEnvelope envelope);
}
