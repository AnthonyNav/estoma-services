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
      "GetWashExecutionIntegrationContextResult",
      "ListWashExecutionIntegrationContextsPageResult");

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final ProjectionRecoveryResultProcessor fullProcessor;
  private final WashExecutionEntityRecoveryResultProcessor entityProcessor;
  private final FailureRouter failures;

  public RecoveryResultListener(EnvelopeJsonCodec codec, EnvelopeValidator validator,
      ProjectionRecoveryResultProcessor fullProcessor,
      WashExecutionEntityRecoveryResultProcessor entityProcessor, FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.fullProcessor = fullProcessor;
    this.entityProcessor = entityProcessor;
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
      if ("GetWashExecutionIntegrationContextResult".equals(result.messageType())) {
        entityProcessor.process(result);
      } else {
        fullProcessor.process(result);
      }
      channel.basicAck(tag, false);
    } catch (InvalidMessageException | IllegalArgumentException invalid) {
      DeliveryFailureSupport.routeThenAck(
          failures, message, MessagingNames.RESULT_QUEUE, false, channel, tag);
    } catch (RuntimeException technical) {
      DeliveryFailureSupport.routeThenAck(
          failures, message, MessagingNames.RESULT_QUEUE, true, channel, tag);
    }
  }

  static void validateProducer(MessageEnvelope result) {
    String expected = switch (result.messageType()) {
      case "GetAppointmentAcademicConfigurationResult",
           "ListStudentAppointmentAcademicSnapshotsPageResult" -> "academic";
      case "ListAccountAuthorizationSnapshotsPageResult" -> "authorization";
      case "GetWashAdministrationConfigurationResult" -> "wash-administration";
      case "GetWashExecutionIntegrationContextResult",
           "ListWashExecutionIntegrationContextsPageResult" -> "wash-execution";
      default -> throw new InvalidMessageException("unsupported recovery Result");
    };
    if (!expected.equals(result.producer())) {
      throw new InvalidMessageException(
          result.messageType() + " must be produced by " + expected);
    }
  }
}
