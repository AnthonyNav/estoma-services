package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;

public interface HandleWashEntryRejectedUseCase {
  ApplicationResult handle(ExecutionEvent event);
}
