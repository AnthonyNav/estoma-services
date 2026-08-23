package com.estoma.authorization.application.port;

import com.estoma.authorization.application.command.AuthorizationCommand;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.CommandOutcome;

/** Persists the terminal technical outcome selected by the transport retry policy. */
public interface TerminalCommandFailureRecorder {

  CommandOutcome recordTerminalFailure(CommandMetadata metadata, AuthorizationCommand command);
}
