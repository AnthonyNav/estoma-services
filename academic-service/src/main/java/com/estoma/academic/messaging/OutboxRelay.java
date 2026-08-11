package com.estoma.academic.messaging;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.config.AcademicOutboxProperties;
import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.persistence.OutboxRepository;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Claims fenced rows and marks them published only after RabbitMQ confirms the envelope. */
@Component("academicOutboxRelay")
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private final OutboxRepository outbox; private final RabbitTemplate rabbit; private final EnvelopeCodec codec; private final AcademicOutboxProperties properties;
  OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit, EnvelopeCodec codec, AcademicOutboxProperties properties) { this.outbox = outbox; this.rabbit = rabbit; this.codec = codec; this.properties = properties; }
  @Scheduled(fixedDelay = 2000)
  public void relay() {
    outbox.recoverExpired(properties.maxAttempts());
    for (OutboxRepository.Row row : outbox.claim(properties.claimBatchSize(), properties.lease())) {
      try {
        publish(row);
        if (outbox.published(row) == 0) log.warn("outbox claim lost before publish transition messageId={} claimToken={}", row.id(), row.token());
      } catch (Exception ex) {
        int changed = row.attempts() >= properties.maxAttempts() ? outbox.failed(row, failureCode(ex)) : outbox.retry(row, properties.retryDelay(), failureCode(ex));
        if (changed == 0) log.warn("outbox claim lost before failure transition messageId={} claimToken={}", row.id(), row.token());
      }
    }
  }
  private void publish(OutboxRepository.Row row) throws Exception {
    JsonNode meta = codec.node(row.metadata()); Actor actor = codec.actor(meta.path("actor"));
    String exchange = "EVENT".equals(row.kind()) ? AcademicTopology.EVENTS : AcademicTopology.RESULTS;
    String key = "EVENT".equals(row.kind()) ? eventKey(row.type()) : meta.path("destination").asString();
    MessageEnvelope envelope = new MessageEnvelope(row.id(), row.type(), 1, row.kind(), "academic", row.occurredAt(), row.correlationId(), row.causationId(), "RESULT".equals(row.kind()) && meta.hasNonNull("operationId") ? java.util.UUID.fromString(meta.path("operationId").asString()) : null, null, row.aggregateId(), row.aggregateVersion(), actor, codec.node(row.payload()));
    MessageProperties props = new MessageProperties(); props.setContentType(MessageProperties.CONTENT_TYPE_JSON); props.setMessageId(row.id().toString());
    CorrelationData correlation = new CorrelationData(row.id().toString()); rabbit.send(exchange, key, new Message(codec.encode(envelope), props), correlation);
    CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
    if (!confirm.isAck()) throw new IllegalStateException("PUBLISH_NACK");
    if (correlation.getReturned() != null) throw new IllegalStateException("PUBLISH_RETURNED");
  }
  private static String failureCode(Exception ex) { return ex.getMessage() != null && ex.getMessage().startsWith("PUBLISH_") ? ex.getMessage() : "PUBLISH_FAILED"; }
  private static String eventKey(String type) { return "academic." + type.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT); }
}
