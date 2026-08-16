package com.estoma.authorization.application.port;

import com.estoma.authorization.application.command.AuthorizationCommand;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.CommandOutcome;

public interface CommandDispatcher {

  CommandOutcome dispatch(CommandMetadata metadata, AuthorizationCommand command);
}
