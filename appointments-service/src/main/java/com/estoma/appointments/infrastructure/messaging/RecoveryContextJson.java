package com.estoma.appointments.infrastructure.messaging;

import tools.jackson.databind.ObjectMapper;

/** Reads persisted JSON semantically; PostgreSQL jsonb does not preserve formatting. */
final class RecoveryContextJson {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private RecoveryContextJson() {}

  static String requiredText(String json, String field) {
    var context = MAPPER.readTree(json);
    var value = context.path(field);
    if (!context.isObject() || !value.isTextual() || value.asString().isBlank()) {
      throw new IllegalArgumentException("missing or invalid recovery context field " + field);
    }
    return value.asString();
  }
}
