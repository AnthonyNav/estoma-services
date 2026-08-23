package com.estoma.washexecution.application.port;

import com.estoma.washexecution.application.command.CommandOutcome;
import com.estoma.washexecution.application.command.CommandRequest;

public interface WashExecutionCommandUseCase {
  CommandOutcome dispatch(CommandRequest request);
}
