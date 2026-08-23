package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Handles only owner-query Results; incremental Events remain the normal projection path. */
@Component
public final class RecoveryResultListener {
  private static final Set<String> RESULTS = Set.of("GetWashAdministrationConfigurationResult",
      "GetAccountAuthorizationSnapshotResult", "ListAccountAuthorizationSnapshotsPageResult",
      "GetStudentAcademicEligibilitySnapshotResult");
  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final ProjectionRecoveryCoordinator recovery;
  private final FailureRouter failures;
  public RecoveryResultListener(EnvelopeJsonCodec codec, EnvelopeValidator validator,
      ProjectionRecoveryCoordinator recovery, FailureRouter failures) {
    this.codec = codec; this.validator = validator; this.recovery = recovery; this.failures = failures;
  }
  @RabbitListener(id = "appointmentsRecoveryResultConsumer", queues = MessagingNames.RESULT_QUEUE, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      var result = validator.validate(codec.readTree(message.getBody()), MessageKind.RESULT, RESULTS);
      switch (result.messageType()) {
        case "GetWashAdministrationConfigurationResult" -> recovery.ready("wash-administration", result.aggregateVersion() == null ? 1 : result.aggregateVersion());
        case "GetAccountAuthorizationSnapshotResult", "ListAccountAuthorizationSnapshotsPageResult" -> recovery.ready("authorization", result.aggregateVersion() == null ? 1 : result.aggregateVersion());
        case "GetStudentAcademicEligibilitySnapshotResult" -> recovery.ready("academic", result.aggregateVersion() == null ? 1 : result.aggregateVersion());
        default -> throw new InvalidMessageException("unsupported recovery result");
      }
      channel.basicAck(tag, false);
    } catch (InvalidMessageException | IllegalArgumentException invalid) {
      failures.route(message, MessagingNames.RESULT_QUEUE, false); channel.basicAck(tag, false);
    } catch (RuntimeException technical) {
      try { failures.route(message, MessagingNames.RESULT_QUEUE, true); channel.basicAck(tag, false); }
      catch (RuntimeException routingFailure) { channel.basicNack(tag, false, true); }
    }
  }
}
