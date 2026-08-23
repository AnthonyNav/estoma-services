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
public final class HandleWashExecutionCancelledHandler extends AbstractExecutionTransitionHandler
    implements HandleWashExecutionCancelledUseCase {
  public HandleWashExecutionCancelledHandler(AppointmentRepository appointments, ProcessedMessageRepository inbox,
      WashExecutionTransitionRepository transitions, PendingWashExecutionEventRepository pending,
      OutboxRepository outbox) {
    super(appointments, inbox, transitions, pending, outbox);
  }

  @Override
  @Transactional
  public ApplicationResult handle(ExecutionEvent event) { return apply(event, Kind.CANCELLED); }
}
