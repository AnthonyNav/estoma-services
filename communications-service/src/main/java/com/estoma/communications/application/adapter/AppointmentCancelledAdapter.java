package com.estoma.communications.application.adapter;

import com.estoma.communications.application.command.CommandHandlerResult;
import com.estoma.communications.application.command.RequestCommunicationService;
import com.estoma.communications.application.projection.ProjectionHandler;
import com.estoma.communications.application.projection.ProjectionHandlerResult;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.command.RequestCommunicationCommand;
import com.estoma.communications.contracts.consumed.AppointmentCancelledPayload;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.communications.infrastructure.observability.ConsumerNames;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class AppointmentCancelledAdapter implements ProjectionHandler {

  private static final String SYSTEM_CODE = "wash-ultrasonico";
  private static final String COMMUNICATION_TYPE = "AppointmentCancelledByClinic";

  private final RequestCommunicationService service;
  private final EnvelopeJsonCodec codec;

  AppointmentCancelledAdapter(RequestCommunicationService service, EnvelopeJsonCodec codec) {
    this.service = service;
    this.codec = codec;
  }

  @Override
  public List<String> consumedMessageTypes() {
    return List.of(ConsumedEventTypes.APPOINTMENT_CANCELLED);
  }

  @Override
  public String consumerName() {
    return ConsumerNames.APPOINTMENT_CANCELLED_ADAPTER;
  }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    AppointmentCancelledPayload payload = codec.convertPayload(envelope, AppointmentCancelledPayload.class);
    if (!"CLINIC".equals(payload.cancellationType()) || !"CAPACITY_LOSS".equals(payload.cancellationSubreason())) {
      return ProjectionHandlerResult.NONE;
    }

    RequestCommunicationCommand command =
        new RequestCommunicationCommand(
            payload.studentAccountId(), SYSTEM_CODE, COMMUNICATION_TYPE, CommunicationPriority.NORMAL, "appointments", "AppointmentCancelled",
            payload.appointmentId().toString(), null, null,
            new RequestCommunicationCommand.EmailContent(
                EmailContentMode.GENERIC, null, "Tu cita fue cancelada",
                "Tu cita fue cancelada por una falla o reducción de capacidad de la clínica. Te contactaremos para reprogramarla.", null, null, null));

    try {
      MessageEnvelope boundary = new MessageEnvelope(
          envelope.messageId(), envelope.messageType(), envelope.messageVersion(), envelope.messageKind(),
          envelope.producer(), envelope.occurredAt(),
          envelope.correlationId() == null ? java.util.UUID.randomUUID() : envelope.correlationId(),
          envelope.messageId(), envelope.operationId(), payload.appointmentId().toString(),
          envelope.aggregateId(), envelope.aggregateVersion(), envelope.actor(), envelope.payload());
      CommandHandlerResult result = service.create(command, boundary);
      return new ProjectionHandlerResult(result.events().stream()
          .map(event -> event.onAggregate(result.aggregateId(), result.aggregateVersion()))
          .toList());
    } catch (CommunicationsRejectedException rejected) {
      if (rejected.code() == RejectionCode.COMMUNICATION_ALREADY_EXISTS) {
        return ProjectionHandlerResult.NONE;
      }
      throw rejected;
    }
  }
}
