package com.estoma.users.config;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Messaging tunables.
 *
 * <p>{@code commandProducers}/{@code queryProducers} are the per-operation
 * authorization matrices {@link com.estoma.users.messaging.inbound.EnvelopeValidator}/
 * {@code QueryEnvelopeValidator} check a command's or query's {@code
 * producer} + {@code messageType} pair against (commands.md §8): a
 * producer being trusted at all does not imply it may issue every
 * operation — {@code academic} (the {@code student-import-process}) is
 * authorized for {@code CreatePerson}/{@code UpdatePerson} only, and for
 * {@code ResolvePersonByInstitutionalEmail} on the query side (queries.md
 * §2 — the only authorized requester in V1). This is a technical check,
 * not the functional authorization commands.md §8 says is resolved
 * upstream by the producer before publishing — it exists so a producer
 * cannot exceed the operations its own contract documents, purely by
 * technical identity.
 *
 * <p>{@code topologyDeclare} gates whether this service declares its own
 * exchanges/queues/bindings on startup ({@code true} in dev/test) or
 * assumes {@code estoma-infra} has already provisioned them via GitOps
 * ({@code false} in staging/prod, where the runtime AMQP user has no
 * {@code configure} permission — docs/system-contract.md §9).
 *
 * <p>{@code commandMaxAttempts}/{@code queryMaxAttempts} are the number
 * of retry tiers (5s/30s/300s) a redelivered command/query passes through
 * before {@code FailureRouter} sends it to the DLQ instead.
 *
 * <p>{@code publisherConfirmTimeout} bounds how long {@code
 * ConfirmedRabbitPublisher} waits for a publisher confirm before treating
 * the publication as failed (docs/system-contract.md §7). Also the input
 * to the Outbox lease budget ({@code OutboxLeaseBudgetValidator}) — see
 * {@code OutboxProperties.lease}.
 */
@ConfigurationProperties("estoma.messaging")
public record MessagingProperties(
    @DefaultValue Map<String, Set<String>> commandProducers,
    @DefaultValue Map<String, Set<String>> queryProducers,
    @DefaultValue("true") boolean topologyDeclare,
    @DefaultValue("3") int commandMaxAttempts,
    @DefaultValue("3") int queryMaxAttempts,
    @DefaultValue("5s") Duration publisherConfirmTimeout) {

  public MessagingProperties {
    if (publisherConfirmTimeout == null || publisherConfirmTimeout.isNegative() || publisherConfirmTimeout.isZero()) {
      throw new IllegalArgumentException(
          "estoma.messaging.publisher-confirm-timeout must be a positive duration, was " + publisherConfirmTimeout);
    }
    commandProducers = commandProducers == null ? Map.of() : Map.copyOf(commandProducers);
    queryProducers = queryProducers == null ? Map.of() : Map.copyOf(queryProducers);
  }

  /** Whether {@code producer} is technically authorized to issue {@code messageType}. */
  public boolean isCommandAllowed(String producer, String messageType) {
    return commandProducers.getOrDefault(producer, Set.of()).contains(messageType);
  }

  /** Whether {@code producer} is technically authorized to issue the query {@code messageType}. */
  public boolean isQueryAllowed(String producer, String messageType) {
    return queryProducers.getOrDefault(producer, Set.of()).contains(messageType);
  }
}
