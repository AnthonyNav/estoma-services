package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.persistence.JdbcOutboxRepository;
import com.estoma.users.persistence.OutboxRecord;
import com.estoma.users.testsupport.AbstractMessagingIT;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Demonstrates the design's explicit AT_LEAST_ONCE trade-off
 * (docs/system-contract.md §7, §8): RabbitMQ can confirm a publication
 * that this service then never gets to record as PUBLISHED (a crash, in
 * this test simulated directly), and recovery republishes the exact same
 * wire {@code messageId} once the lease is reclaimed — correctness then
 * depends on the consumer's own {@code (messageId, consumerName)}
 * deduplication, not on this service never double-publishing.
 */
class OutboxCrashAfterPublishIT extends AbstractMessagingIT {

  @Autowired private JdbcOutboxRepository outboxRepository;
  @Autowired private ConfirmedRabbitPublisher publisher;
  @Autowired private OutboxRelay relay;
  @Autowired private OutboxSweeper sweeper;
  @Autowired private AmqpAdmin amqpAdmin;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private TopicExchange eventsExchange;
  @Autowired private JdbcClient jdbc;

  @Test
  void aRowConfirmedButNeverMarkedIsReclaimedAndRepublishedWithTheSameMessageId() {
    // A named, non-auto-delete queue -- an anonymous amqpAdmin.declareQueue()
    // is exclusive+auto-delete and gets removed by the broker as soon as
    // its first consumer (the first receive() below) disconnects, which
    // would break the second receive() a few lines later.
    Queue testQueue = new Queue("it.crash-after-publish." + UUID.randomUUID(), false, false, false);
    amqpAdmin.declareQueue(testQueue);
    Binding binding =
        BindingBuilder.bind(testQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_PERSON_CREATED);
    amqpAdmin.declareBinding(binding);
    try {
      UUID messageId = appendEvent();

      // Claim with a very short lease and publish, but deliberately never
      // call markPublished -- exactly what a hard crash between "RabbitMQ
      // confirmed" and "the DB write that says so" looks like. A short
      // lease here (rather than the application's normal 2-minute one)
      // keeps the test fast without needing a long sleep.
      OutboxRecord claimed =
          outboxRepository.claimBatch(1, Duration.ofMillis(300)).stream()
              .filter(r -> r.messageId().equals(messageId))
              .findFirst()
              .orElseThrow();
      publisher.publishEvent(AmqpNames.EXCHANGE_EVENTS, AmqpNames.ROUTING_KEY_PERSON_CREATED, wireMessage(messageId));

      Message firstDelivery = rabbitTemplate.receive(testQueue.getName(), 5_000);
      assertThat(firstDelivery).isNotNull();
      assertThat(firstDelivery.getMessageProperties().getMessageId()).isEqualTo(messageId.toString());
      // The "crash": still PROCESSING, because markPublished never ran.
      assertThat(statusOf(messageId)).isEqualTo("PROCESSING");

      // Run the productive sweeper explicitly rather than waiting for its
      // scheduled trigger.
      sleepPastLease();
      sweeper.releaseExpiredLeases();
      assertThat(statusOf(messageId)).isEqualTo("PENDING");

      // The sweeper applies the normal retry backoff. Once it becomes
      // available, invoke the real relay explicitly to republish it.
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                relay.relay();
                assertThat(statusOf(messageId)).isEqualTo("PUBLISHED");
              });

      Message secondDelivery = rabbitTemplate.receive(testQueue.getName(), 5_000);
      assertThat(secondDelivery).isNotNull();
      assertThat(secondDelivery.getMessageProperties().getMessageId()).isEqualTo(messageId.toString());
    } finally {
      amqpAdmin.deleteQueue(testQueue.getName());
    }
  }

  private static void sleepPastLease() {
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static Message wireMessage(UUID messageId) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setMessageId(messageId.toString());
    return new Message("{}".getBytes(), props);
  }

  private UUID appendEvent() {
    UUID messageId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    outboxRepository.append(
        new OutboxAppendRequest(
            messageId,
            "PersonCreated",
            1,
            "EVENT",
            personId,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "{\"personId\":\"" + personId + "\"}",
            null,
            Instant.now()));
    return messageId;
  }

  private String statusOf(UUID messageId) {
    return jdbc.sql("SELECT status FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(String.class)
        .single();
  }
}
