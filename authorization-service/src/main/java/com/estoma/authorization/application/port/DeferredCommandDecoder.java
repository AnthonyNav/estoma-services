package com.estoma.authorization.application.port;

import com.estoma.authorization.application.command.AuthorizationCommand;
import com.estoma.authorization.application.command.CommandMetadata;

/** Reconstitutes only the retained internal Command forms that V1 permits to be DEFERRED. */
public interface DeferredCommandDecoder {

  DecodedCommand decode(ProcessedMessageRepository.ProcessedMessage message);

  record DecodedCommand(CommandMetadata metadata, AuthorizationCommand command) {}
}
