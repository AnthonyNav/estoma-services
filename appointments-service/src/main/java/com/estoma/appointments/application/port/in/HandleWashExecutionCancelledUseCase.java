package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;

public interface HandleWashExecutionCancelledUseCase {
  ApplicationResult handle(ExecutionEvent event);
}
