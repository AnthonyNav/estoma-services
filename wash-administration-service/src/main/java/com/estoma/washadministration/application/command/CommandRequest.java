package com.estoma.washadministration.application.command;

import java.util.Objects;

/**
 * Internal command request.
 *
 * <p>The payload is a typed Application model. JSON/AMQP DTO mapping belongs to PR3.
 */
public record CommandRequest(CommandMetadata metadata, AdministrativeCommand payload) {

  public CommandRequest {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(payload, "payload");
  }
}
