package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.domain.model.ActorRef;
import tools.jackson.databind.ObjectMapper;

final class ActorJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ActorJsonCodec() {}

  static String write(ActorRef actor) {
    return actor == null ? null : MAPPER.writeValueAsString(actor);
  }

  static ActorRef read(String json) {
    return json == null ? null : MAPPER.readValue(json, ActorRef.class);
  }
}
