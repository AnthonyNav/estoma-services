package com.estoma.academic.health;

import com.estoma.academic.persistence.OutboxRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("schemaVersion")
class SchemaVersionHealthIndicator implements HealthIndicator {
  private final JdbcClient jdbc; SchemaVersionHealthIndicator(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public Health health() { try { Integer version = jdbc.sql("SELECT max(installed_rank) FROM flyway_schema_history WHERE success").query(Integer.class).optional().orElse(null); return version == null ? Health.down().build() : Health.up().withDetail("version", version).build(); } catch (RuntimeException ex) { return Health.down(ex).build(); } }
}
@Component("outboxRelay")
class OutboxRelayHealthIndicator implements HealthIndicator {
  private final OutboxRepository outbox; OutboxRelayHealthIndicator(OutboxRepository outbox) { this.outbox = outbox; }
  @Override public Health health() { long overdue = outbox.overdue(); return overdue == 0 ? Health.up().build() : Health.down().withDetail("overdueMessages", overdue).build(); }
}
@Component("commandConsumer")
class CommandConsumerHealthIndicator implements HealthIndicator {
  private final RabbitListenerEndpointRegistry registry;
  CommandConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) { this.registry = registry; }
  @Override public Health health() { return listenerHealth("academicCommandListener"); }
  private Health listenerHealth(String id) { var listener = registry.getListenerContainer(id); return listener != null && listener.isRunning() ? Health.up().build() : Health.down().withDetail("listener", id).build(); }
}
@Component("eventProjectionConsumer")
class EventProjectionConsumerHealthIndicator implements HealthIndicator {
  private final RabbitListenerEndpointRegistry registry;
  EventProjectionConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) { this.registry = registry; }
  @Override public Health health() { var listener = registry.getListenerContainer("academicEventProjectionListener"); return listener != null && listener.isRunning() ? Health.up().build() : Health.down().withDetail("listener", "academicEventProjectionListener").build(); }
}
