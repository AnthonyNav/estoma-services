package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public final class QueryListener {
  private static final Set<String> QUERIES = Set.of(
      "GetAppointmentExecutionContext", "ListAppointmentExecutionContextsPage",
      "GetAppointmentAvailability");
  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final AppointmentExecutionQueryService queries;
  private final FailureRouter failures;

  public QueryListener(EnvelopeJsonCodec codec, EnvelopeValidator validator,
      AppointmentExecutionQueryService queries, FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.queries = queries;
    this.failures = failures;
  }

  @RabbitListener(id = "appointmentsQueryConsumer", queues = MessagingNames.QUERY_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      var query = validator.validate(codec.readTree(message.getBody()), MessageKind.QUERY, QUERIES);
      validateRequester(query);
      queries.answer(query);
      channel.basicAck(tag, false);
    } catch (InvalidMessageException | IllegalArgumentException invalid) {
      DeliveryFailureSupport.routeThenAck(
          failures, message, MessagingNames.QUERY_QUEUE, false, channel, tag);
    } catch (RuntimeException technical) {
      DeliveryFailureSupport.routeThenAck(
          failures, message, MessagingNames.QUERY_QUEUE, true, channel, tag);
    }
  }

  static void validateRequester(com.estoma.appointments.contracts.MessageEnvelope query) {
    String requester = "GetAppointmentAvailability".equals(query.messageType())
        ? "bff" : "wash-execution";
    if (!requester.equals(query.producer())) {
      throw new InvalidMessageException("query requester is not allowlisted");
    }
    if (query.actor() == null || !"SYSTEM".equals(query.actor().type())
        || !requester.equals(query.actor().service()) || query.actor().accountId() != null) {
      throw new InvalidMessageException("query requires SYSTEM " + requester + " actor");
    }
    if (query.idempotencyKey() != null) {
      throw new InvalidMessageException("Query must not carry Command idempotencyKey");
    }
  }
}
