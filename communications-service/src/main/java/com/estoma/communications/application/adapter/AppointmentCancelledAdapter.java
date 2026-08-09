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

/**
 * Translates {@code AppointmentCancelled} (from {@code
 * appointments-service}) into a self-issued {@code RequestCommunication},
 * in-process, when {@code cancellationType = CLINIC} and {@code
 * cancellationSubreason = CAPACITY_LOSS} (events.md §5.1). Every other
 * combination is ignored -- this is the only documented, confirmed adapter
 * as of this service's initial scope; it does not make this service the
 * owner of any Lavado Ultrasónico business rule, it only translates an
 * already-confirmed fact into a communication request.
 *
 * <p>Calls {@link RequestCommunicationService} directly (not through
 * {@code CommandDispatcher}/RabbitMQ) -- see system_contract.md "Internal
 * Architecture" for why. {@link RejectionCode#COMMUNICATION_ALREADY_EXISTS}
 * is caught and treated as an idempotent no-op (the same
 * {@code AppointmentCancelled} fact may legitimately be redelivered under a
 * different {@code messageId}); any other rejection is a genuine
 * inconsistency and is left to propagate.
 */
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
            payload.studentAccountId(), SYSTEM_CODE, COMMUNICATION_TYPE, CommunicationPriority.NORMAL, "appointments-service", "AppointmentCancelled",
            payload.appointmentId().toString(), null, null,
            new RequestCommunicationCommand.EmailContent(
                EmailContentMode.GENERIC, null, "Tu cita fue cancelada",
                "Tu cita fue cancelada por una falla o reducción de capacidad de la clínica. Te contactaremos para reprogramarla.", null, null, null));

    try {
      MessageEnvelope boundary = new MessageEnvelope(envelope.messageId(), envelope.messageType(), envelope.messageVersion(), envelope.messageKind(),
          envelope.producer(), envelope.occurredAt(), java.util.UUID.randomUUID(), envelope.messageId(), envelope.operationId(), payload.appointmentId(),
          envelope.aggregateId(), envelope.aggregateVersion(), envelope.actor(), envelope.payload());
      CommandHandlerResult result = service.create(command, boundary);
      return new ProjectionHandlerResult(result.events());
    } catch (CommunicationsRejectedException rejected) {
      if (rejected.code() == RejectionCode.COMMUNICATION_ALREADY_EXISTS) {
        return ProjectionHandlerResult.NONE;
      }
      throw rejected;
    }
  }
}
