package com.estoma.users.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Messaging tunables. {@code allowedProducers} is the allow-list
 * {@link com.estoma.users.messaging.inbound.EnvelopeValidator} checks the
 * envelope's {@code producer} field against — the platform docs anticipate
 * a second authorized caller ("proceso administrativo de carga",
 * commands.md:254) that does not exist as a built component yet, so this
 * is a set, not a single hardcoded value.
 *
 * <p>Further fields (AMQP topology declaration, retry tiers) are added
 * once that wiring exists.
 */
@ConfigurationProperties("estoma.messaging")
public record MessagingProperties(@DefaultValue("bff") Set<String> allowedProducers) {}
