package com.estoma.communications.application.command;

import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.command.RequestCommunicationCommand;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import org.springframework.stereotype.Component;

/** Handles {@code RequestCommunication} (commands.md §3) received as a durable command. */
@Component
class RequestCommunicationHandler implements CommandHandler {

  private final RequestCommunicationService service;
  private final EnvelopeJsonCodec codec;

  RequestCommunicationHandler(RequestCommunicationService service, EnvelopeJsonCodec codec) {
    this.service = service;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return CommunicationsCommand.REQUEST_COMMUNICATION.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    RequestCommunicationCommand command = codec.convertPayload(envelope, RequestCommunicationCommand.class);
    return service.create(command);
  }
}
