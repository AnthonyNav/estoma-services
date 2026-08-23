package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.HandleWashExecutionStartedUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class HandleWashExecutionStartedHandler extends AbstractExecutionTransitionHandler
    implements HandleWashExecutionStartedUseCase {
  public HandleWashExecutionStartedHandler(AppointmentRepository appointments, ProcessedMessageRepository inbox,
      WashExecutionTransitionRepository transitions, PendingWashExecutionEventRepository pending,
      OutboxRepository outbox) {
    super(appointments, inbox, transitions, pending, outbox);
  }

  @Override
  @Transactional
  public ApplicationResult handle(ExecutionEvent event) { return apply(event, Kind.STARTED); }
}
