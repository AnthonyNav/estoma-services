package com.estoma.platformbff.api.admin;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * Public, client-oriented schema for an administrative request.
 *
 * <p>The BFF deliberately does not expose owner RabbitMQ DTOs. This registry validates the HTTP
 * shape before adding path identifiers and forwarding the command to its owner.
 */
public final class AdminRequestSchema {
  public enum Kind { STRING, UUID, INTEGER, BOOLEAN, DATE, DATE_TIME }

  public record Field(Kind kind, boolean required, Integer minimum, List<String> values) {
    public Field {
      values = values == null ? List.of() : List.copyOf(values);
    }
  }

  private final Map<String, Field> fields;

  private AdminRequestSchema(Map<String, Field> fields) {
    this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
  }

  public static AdminRequestSchema of(FieldEntry... entries) {
    Map<String, Field> values = new LinkedHashMap<>();
    for (FieldEntry entry : entries) {
      if (values.put(entry.name(), entry.field()) != null) {
        throw new IllegalArgumentException("duplicate public request field: " + entry.name());
      }
    }
    return new AdminRequestSchema(values);
  }

  public static FieldEntry required(String name, Kind kind) {
    return new FieldEntry(name, new Field(kind, true, null, List.of()));
  }

  public static FieldEntry optional(String name, Kind kind) {
    return new FieldEntry(name, new Field(kind, false, null, List.of()));
  }

  public static FieldEntry positive(String name) {
    return new FieldEntry(name, new Field(Kind.INTEGER, true, 1, List.of()));
  }

  public static FieldEntry enumValue(String name, boolean required, String... values) {
    return new FieldEntry(name, new Field(Kind.STRING, required, null, List.of(values)));
  }

  public void validate(Map<String, Object> body) {
    if (body == null) throw new IllegalArgumentException("request body is required");
    Set<String> unknown = new LinkedHashSet<>(body.keySet());
    unknown.removeAll(fields.keySet());
    if (!unknown.isEmpty()) throw new IllegalArgumentException("unsupported request fields: " + unknown);
    for (Map.Entry<String, Field> entry : fields.entrySet()) {
      Object value = body.get(entry.getKey());
      Field field = entry.getValue();
      if (value == null) {
        if (field.required()) throw new IllegalArgumentException(entry.getKey() + " is required");
        continue;
      }
      validateValue(entry.getKey(), value, field);
    }
  }

  public Map<String, Object> openApi() {
    Map<String, Object> properties = new LinkedHashMap<>();
    java.util.ArrayList<String> required = new java.util.ArrayList<>();
    fields.forEach((name, field) -> {
      Map<String, Object> schema = new LinkedHashMap<>();
      switch (field.kind()) {
        case STRING -> schema.put("type", "string");
        case UUID -> {
          schema.put("type", "string");
          schema.put("format", "uuid");
        }
        case INTEGER -> schema.put("type", "integer");
        case BOOLEAN -> schema.put("type", "boolean");
        case DATE -> {
          schema.put("type", "string");
          schema.put("format", "date");
        }
        case DATE_TIME -> {
          schema.put("type", "string");
          schema.put("format", "date-time");
        }
      }
      if (field.minimum() != null) schema.put("minimum", field.minimum());
      if (!field.values().isEmpty()) schema.put("enum", field.values());
      properties.put(name, schema);
      if (field.required()) required.add(name);
    });
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      required.sort(String::compareTo);
      schema.put("required", required);
    }
    return schema;
  }

  private static void validateValue(String name, Object value, Field field) {
    boolean valid = switch (field.kind()) {
      case STRING, UUID, DATE, DATE_TIME -> value instanceof String string && !string.isBlank();
      case INTEGER -> value instanceof Number number && Math.floor(number.doubleValue()) == number.doubleValue();
      case BOOLEAN -> value instanceof Boolean;
    };
    if (!valid) throw new IllegalArgumentException(name + " has an invalid type");
    if (field.kind() == Kind.UUID) {
      try {
        java.util.UUID.fromString((String) value);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException(name + " must be a UUID", invalid);
      }
    }
    if (field.minimum() != null && ((Number) value).longValue() < field.minimum()) {
      throw new IllegalArgumentException(name + " is below its minimum");
    }
    if (!field.values().isEmpty() && !field.values().contains(value)) {
      throw new IllegalArgumentException(name + " is not an allowed value");
    }
  }

  public record FieldEntry(String name, Field field) {}
}
