package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.contracts.WireActor;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.ActorType;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class WireValues {

  private WireValues() {}

  static JsonNode object(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      throw new InvalidMessageException(field + " must be an object");
    }
    return node;
  }

  static void fields(JsonNode object, Set<String> required, Set<String> allowed) {
    object(object, "object");
    for (Map.Entry<String, JsonNode> property : object.properties()) {
      if (!allowed.contains(property.getKey())) {
        throw new InvalidMessageException("unknown field: " + property.getKey());
      }
      // Owner DTO serializers may materialize an optional property as JSON null.
      // Treat that exactly like an omitted optional property, while required
      // fields and all non-null nested values remain strict.
      if (required.contains(property.getKey()) || !property.getValue().isNull()) {
        rejectNulls(property.getValue(), property.getKey());
      }
    }
    for (String field : required) {
      if (!object.hasNonNull(field)) {
        throw new InvalidMessageException("missing required field: " + field);
      }
    }
  }

  static void rejectNulls(JsonNode value, String path) {
    if (value == null || value.isNull()) {
      throw new InvalidMessageException(path + " must not be null");
    }
    if (value.isObject()) {
      for (Map.Entry<String, JsonNode> child : value.properties()) {
        rejectNulls(child.getValue(), path + "." + child.getKey());
      }
    } else if (value.isArray()) {
      int index = 0;
      for (JsonNode child : value) {
        rejectNulls(child, path + "[" + index++ + "]");
      }
    }
  }

  static String text(JsonNode object, String field) {
    JsonNode value = requiredNode(object, field);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new InvalidMessageException(field + " must be non-blank text");
    }
    return value.asText();
  }

  static String optionalText(JsonNode object, String field) {
    return object.hasNonNull(field) ? text(object, field) : null;
  }

  static UUID uuid(JsonNode object, String field) {
    try {
      return UUID.fromString(text(object, field));
    } catch (IllegalArgumentException exception) {
      throw new InvalidMessageException(field + " must be a UUID", exception);
    }
  }

  static UUID optionalUuid(JsonNode object, String field) {
    return object.hasNonNull(field) ? uuid(object, field) : null;
  }

  static Instant instant(JsonNode object, String field) {
    try {
      return Instant.parse(text(object, field));
    } catch (RuntimeException exception) {
      throw new InvalidMessageException(field + " must be an RFC 3339 timestamp", exception);
    }
  }

  static int positiveInt(JsonNode object, String field) {
    JsonNode value = requiredNode(object, field);
    if (!value.isInt() || value.asInt() <= 0) {
      throw new InvalidMessageException(field + " must be a positive integer");
    }
    return value.asInt();
  }

  static Integer optionalPositiveInt(JsonNode object, String field) {
    return object.hasNonNull(field) ? positiveInt(object, field) : null;
  }

  static boolean bool(JsonNode object, String field) {
    JsonNode value = requiredNode(object, field);
    if (!value.isBoolean()) {
      throw new InvalidMessageException(field + " must be a boolean");
    }
    return value.asBoolean();
  }

  static JsonNode array(JsonNode object, String field) {
    JsonNode value = requiredNode(object, field);
    if (!value.isArray()) {
      throw new InvalidMessageException(field + " must be an array");
    }
    return value;
  }

  static JsonNode optionalObject(JsonNode object, String field) {
    return object.hasNonNull(field) ? object(object.get(field), field) : null;
  }

  static <T extends Enum<T>> T enumValue(JsonNode object, String field, Class<T> type) {
    try {
      return Enum.valueOf(type, text(object, field));
    } catch (IllegalArgumentException exception) {
      throw new InvalidMessageException(field + " has an unsupported value", exception);
    }
  }

  static ActorRef actor(WireActor actor) {
    try {
      return switch (ActorType.valueOf(actor.type())) {
        case ACCOUNT -> ActorRef.account(actor.accountId());
        case SYSTEM -> ActorRef.system(actor.service());
      };
    } catch (RuntimeException exception) {
      throw new InvalidMessageException("invalid actor", exception);
    }
  }

  private static JsonNode requiredNode(JsonNode object, String field) {
    if (!object.hasNonNull(field)) {
      throw new InvalidMessageException("missing required field: " + field);
    }
    return object.get(field);
  }
}
