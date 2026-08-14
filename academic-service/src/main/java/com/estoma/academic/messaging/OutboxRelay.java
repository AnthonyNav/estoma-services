package com.estoma.academic.messaging;

import com.estoma.academic.config.AcademicOutboxProperties;
import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.observability.AcademicOutboxMetrics;
import com.estoma.academic.persistence.OutboxRepository;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Claims fenced rows and marks them published only after RabbitMQ confirms the envelope. */
@Component("academicOutboxRelay")
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository outbox;
  private final RabbitTemplate requiredRouteRabbit;
  private final RabbitTemplate eventRabbit;
  private final EnvelopeCodec codec;
  private final AcademicOutboxProperties properties;
  private final AcademicOutboxMetrics metrics;

  OutboxRelay(
      OutboxRepository outbox,
      @Qualifier("requiredRouteRabbitTemplate") RabbitTemplate requiredRouteRabbit,
      @Qualifier("eventRabbitTemplate") RabbitTemplate eventRabbit,
      EnvelopeCodec codec,
      AcademicOutboxProperties properties,
      AcademicOutboxMetrics metrics) {
    this.outbox = outbox;
    this.requiredRouteRabbit = requiredRouteRabbit;
    this.eventRabbit = eventRabbit;
    this.codec = codec;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelay = 2000)
  public void relay() {
    outbox.recoverExpired(properties.maxAttempts());
    boolean first = true;
    for (OutboxRepository.Row row : outbox.claim(properties.claimBatchSize(), properties.lease())) {
      try {
        if (!first && !properties.publishBackoff().isZero()) {
          Thread.sleep(properties.publishBackoff().toMillis());
        }
        first = false;
        publish(row);
        if (outbox.published(row) == 0) {
          metrics.claimLost();
          log.warn(
              "outbox claim lost before publish transition messageId={} claimToken={}",
              row.id(),
              row.token());
        } else {
          metrics.published();
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        handleFailure(row, "PUBLISH_INTERRUPTED");
        return;
      } catch (TimeoutException ex) {
        handleFailure(row, "PUBLISH_TIMEOUT");
      } catch (Exception ex) {
        handleFailure(row, failureCode(ex));
      }
    }
  }

  private void handleFailure(OutboxRepository.Row row, String code) {
    int changed = row.attempts() >= properties.maxAttempts()
        ? outbox.failed(row, code)
        : outbox.retry(row, properties.retryDelay(), code);
    if (changed == 0) {
      metrics.claimLost();
      log.warn(
          "outbox claim lost before failure transition messageId={} claimToken={} failureCode={}",
          row.id(),
          row.token(),
          code);
    } else if (row.attempts() >= properties.maxAttempts()) {
      metrics.failed();
    } else {
      metrics.retried();
    }
  }

  private void publish(OutboxRepository.Row row) throws Exception {
    JsonNode meta = codec.node(row.metadata());
    Actor actor = codec.actor(meta.path("actor"));
    String exchange = switch (row.kind()) {
      case "EVENT" -> AcademicTopology.EVENTS;
      case "RESULT" -> AcademicTopology.RESULTS;
      case "QUERY" -> AcademicTopology.QUERIES;
      default -> throw new IllegalStateException("unsupported outbox message kind " + row.kind());
    };
    String key = switch (row.kind()) {
      case "EVENT" -> AcademicEventRoutingKeyResolver.resolve(row.type());
      case "RESULT" -> meta.path("destination").asString();
      case "QUERY" -> "users-service";
      default -> throw new IllegalStateException("unsupported outbox message kind " + row.kind());
    };

    MessageEnvelope envelope = new MessageEnvelope(
        row.id(),
        row.type(),
        1,
        row.kind(),
        "academic",
        row.occurredAt(),
        row.correlationId(),
        row.causationId(),
        ("RESULT".equals(row.kind()) || "QUERY".equals(row.kind())) && meta.hasNonNull("operationId")
            ? java.util.UUID.fromString(meta.path("operationId").asString())
            : null,
        null,
        row.aggregateId(),
        row.aggregateVersion(),
        actor,
        codec.node(row.payload()));

    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setMessageId(row.id().toString());
    CorrelationData correlation = new CorrelationData(row.id().toString());
    RabbitTemplate rabbit = "EVENT".equals(row.kind()) ? eventRabbit : requiredRouteRabbit;
    rabbit.send(exchange, key, new Message(codec.encode(envelope), props), correlation);

    CorrelationData.Confirm confirm = correlation.getFuture()
        .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
    if (!confirm.ack()) {
      throw new IllegalStateException("PUBLISH_NACK");
    }
    if (!"EVENT".equals(row.kind()) && correlation.getReturned() != null) {
      throw new IllegalStateException("PUBLISH_RETURNED");
    }
  }

  private static String failureCode(Exception ex) {
    if (ex instanceof TimeoutException) {
      return "PUBLISH_TIMEOUT";
    }
    if (ex instanceof InterruptedException) {
      return "PUBLISH_INTERRUPTED";
    }
    if (ex.getMessage() != null && ex.getMessage().startsWith("PUBLISH_")) {
      return ex.getMessage();
    }
    return "PUBLISH_UNEXPECTED_ERROR";
  }
}
