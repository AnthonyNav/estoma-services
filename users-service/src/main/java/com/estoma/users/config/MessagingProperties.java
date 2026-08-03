package com.estoma.users.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Messaging tunables.
 *
 * <p>{@code allowedProducers} is the allow-list
 * {@link com.estoma.users.messaging.inbound.EnvelopeValidator} checks the
 * envelope's {@code producer} field against — the platform docs anticipate
 * a second authorized caller ("proceso administrativo de carga",
 * commands.md:254) that does not exist as a built component yet, so this
 * is a set, not a single hardcoded value.
 *
 * <p>{@code topologyDeclare} gates whether this service declares its own
 * exchanges/queues/bindings on startup ({@code true} in dev/test) or
 * assumes {@code estoma-infra} has already provisioned them via GitOps
 * ({@code false} in staging/prod, where the runtime AMQP user has no
 * {@code configure} permission — docs/system-contract.md §9).
 *
 * <p>{@code commandMaxAttempts} is the number of retry tiers
 * (5s/30s/300s) a redelivered command passes through before
 * {@code FailureRouter} sends it to the DLQ instead.
 */
@ConfigurationProperties("estoma.messaging")
public record MessagingProperties(
    @DefaultValue("bff") Set<String> allowedProducers,
    @DefaultValue("true") boolean topologyDeclare,
    @DefaultValue("3") int commandMaxAttempts) {}
