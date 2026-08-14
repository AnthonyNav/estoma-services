package com.estoma.washadministration.application.command;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Internal command request. Concrete payloads remain application models, not wire DTOs. */
public record CommandRequest(CommandMetadata metadata, JsonNode payload) {

  public CommandRequest {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(payload, "payload");
  }
}
