package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Validates owner recovery Results, commits projection changes, then ACKs the delivery. */
@Component
public final class RecoveryResultListener {
  private static final Set<String> RESULTS = Set.of(
      "GetAppointmentAcademicConfigurationResult",
      "ListStudentAppointmentAcademicSnapshotsPageResult",
      "ListAccountAuthorizationSnapshotsPageResult",
      "GetWashAdministrationConfigurationResult",
      "ListWashExecutionIntegrationContextsPageResult");

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final ProjectionRecoveryResultProcessor processor;
  private final FailureRouter failures;

  public RecoveryResultListener(EnvelopeJsonCodec codec, EnvelopeValidator validator,
      ProjectionRecoveryResultProcessor processor, FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.processor = processor;
    this.failures = failures;
  }

  @RabbitListener(id = "appointmentsRecoveryResultConsumer", queues = MessagingNames.RESULT_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      MessageEnvelope result = validator.validate(
          codec.readTree(message.getBody()), MessageKind.RESULT, RESULTS);
      validateProducer(result);
      processor.process(result);
      channel.basicAck(tag, false);
    } catch (InvalidMessageException | IllegalArgumentException invalid) {
      failures.route(message, MessagingNames.RESULT_QUEUE, false);
      channel.basicAck(tag, false);
    } catch (RuntimeException technical) {
      try {
        failures.route(message, MessagingNames.RESULT_QUEUE, true);
        channel.basicAck(tag, false);
      } catch (RuntimeException routingFailure) {
        channel.basicNack(tag, false, true);
      }
    }
  }

  static void validateProducer(MessageEnvelope result) {
    String expected = switch (result.messageType()) {
      case "GetAppointmentAcademicConfigurationResult",
           "ListStudentAppointmentAcademicSnapshotsPageResult" -> "academic";
      case "ListAccountAuthorizationSnapshotsPageResult" -> "authorization";
      case "GetWashAdministrationConfigurationResult" -> "wash-administration";
      case "ListWashExecutionIntegrationContextsPageResult" -> "wash-execution";
      default -> throw new InvalidMessageException("unsupported recovery Result");
    };
    if (!expected.equals(result.producer())) {
      throw new InvalidMessageException(
          result.messageType() + " must be produced by " + expected);
    }
  }
}
