package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.HandleWashExecutionCancelledUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandleWashExecutionCancelledHandler extends AbstractExecutionTransitionHandler
    implements HandleWashExecutionCancelledUseCase {
  private final PendingCapacityResolutionCoordinator capacityResolution;

  public HandleWashExecutionCancelledHandler(AppointmentRepository appointments, ProcessedMessageRepository inbox,
      WashExecutionTransitionRepository transitions, PendingWashExecutionEventRepository pending,
      OutboxRepository outbox, PendingCapacityResolutionCoordinator capacityResolution) {
    super(appointments, inbox, transitions, pending, outbox);
    this.capacityResolution = capacityResolution;
  }

  @Override
  @Transactional
  public ApplicationResult handle(ExecutionEvent event) {
    ApplicationResult result = apply(event, Kind.CANCELLED);
    if (result.status() == ApplicationResult.Status.APPLIED) {
      capacityResolution.resolve(event.washExecutionId(), "CANCELLED",
          ApplicationSupport.inboundMetadata(event, event.occurredAt()), event.occurredAt());
    }
    return result;
  }
}
