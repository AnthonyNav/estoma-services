package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.operations.ResultDispatcher;
import com.estoma.platformbff.application.operations.ResultQuarantineService;
import com.estoma.platformbff.infrastructure.config.MessagingTopology;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ResultListener {
  private static final Logger log = LoggerFactory.getLogger(ResultListener.class);

  private final MessageEnvelopeCodec codec;
  private final ResultDispatcher dispatcher;
  private final ResultQuarantineService quarantine;

  public ResultListener(
      MessageEnvelopeCodec codec,
      ResultDispatcher dispatcher,
      ResultQuarantineService quarantine) {
    this.codec = codec;
    this.dispatcher = dispatcher;
    this.quarantine = quarantine;
  }

  @RabbitListener(
      id = "resultListener",
      queues = MessagingTopology.RESULTS_QUEUE,
      containerFactory = "resultsContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    UUID transportMessageId = messageId(message);
    try {
      IncomingResult result;
      try {
        result = codec.readIncomingResult(message.getBody());
      } catch (RuntimeException malformed) {
        quarantine.quarantine(transportMessageId, "MALFORMED_RESULT", message.getBody());
        log.warn("quarantined malformed BFF Result messageId={}", transportMessageId);
        channel.basicAck(deliveryTag, false);
        return;
      }

      ResultDispatcher.DispatchOutcome outcome = dispatcher.dispatch(result);
      if (outcome != ResultDispatcher.DispatchOutcome.APPLIED) {
        String reason = outcome == ResultDispatcher.DispatchOutcome.RECOVERY_NOT_ENABLED
            ? "RECOVERY_RESULT_NOT_ENABLED"
            : "UNTRUSTED_RESULT";
        UUID resultMessageId = result.messageId() == null ? transportMessageId : result.messageId();
        quarantine.quarantine(resultMessageId, reason, message.getBody());
        log.warn("quarantined BFF Result messageId={} reason={}", resultMessageId, reason);
      }
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException technicalFailure) {
      log.warn("BFF Result processing failed before safe isolation messageId={} failure={}",
          transportMessageId, technicalFailure.getClass().getSimpleName());
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private static UUID messageId(Message message) {
    String value = message.getMessageProperties().getMessageId();
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException invalidId) {
      return null;
    }
  }
}
