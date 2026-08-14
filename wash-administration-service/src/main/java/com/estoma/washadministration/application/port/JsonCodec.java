package com.estoma.washadministration.application.port;

import tools.jackson.databind.JsonNode;

/** JSON operations needed by application services without coupling them to Jackson. */
public interface JsonCodec {

  JsonNode tree(Object value);

  String write(Object value);

  JsonNode parse(String value);

  /** Returns a stable representation suitable for a functional request fingerprint. */
  String canonical(JsonNode value);
}
