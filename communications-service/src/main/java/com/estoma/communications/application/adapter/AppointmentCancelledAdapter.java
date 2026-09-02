package com.estoma.communications.application.adapter;

import com.estoma.communications.application.command.CommandHandlerResult;
import com.estoma.communications.application.command.RequestCommunicationService;
import com.estoma.communications.application.projection.AcademicPeriodRetentionPolicy;
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

  private static final String SYSTEM_CODE = "LAVADO_ULTRASONICO";
  private static final String COMMUNICATION_TYPE = "AppointmentCancelledByClinic";

  private final RequestCommunicationService service;
  private final AcademicPeriodRetentionPolicy retentionPolicy;
  private final EnvelopeJsonCodec codec;

  AppointmentCancelledAdapter(
      RequestCommunicationService service, AcademicPeriodRetentionPolicy retentionPolicy, EnvelopeJsonCodec codec) {
    this.service = service;
    this.retentionPolicy = retentionPolicy;
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
    if (payload.appointmentId() == null || payload.studentAccountId() == null) {
      throw new IllegalArgumentException("qualifying AppointmentCancelled requires appointmentId and studentAccountId");
    }

    RequestCommunicationCommand command =
        new RequestCommunicationCommand(
            payload.studentAccountId(), SYSTEM_CODE, COMMUNICATION_TYPE, CommunicationPriority.NORMAL, "appointments", "AppointmentCancelled",
            payload.appointmentId().toString(), null,
            new RequestCommunicationCommand.InboxContent(
                "Cancelación de cita",
                "Tu cita fue cancelada debido a una reducción de capacidad operativa de la clínica.",
                retentionPolicy.resolveWashInboxExpiresAt()),
            new RequestCommunicationCommand.EmailContent(
                EmailContentMode.GENERIC, null, "Tu cita fue cancelada",
                "Tu cita fue cancelada debido a una reducción de capacidad operativa de la clínica.", null, null, null),
            List.of("INBOX", "EMAIL"));

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
