package com.estoma.platformbff.application.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

final class ProjectionPayloads {
  private ProjectionPayloads() {}

  static Object required(Map<String, Object> payload, String name) {
    Object value = payload.get(name);
    if (value == null || (value instanceof String text && text.isBlank())) {
      throw new IllegalArgumentException("missing projection field: " + name);
    }
    return value;
  }

  static String text(Map<String, Object> payload, String name) {
    Object value = required(payload, name);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("invalid projection text field: " + name);
    }
    return text;
  }

  static String optionalText(Map<String, Object> payload, String name) {
    Object value = payload.get(name);
    if (value == null) return null;
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("invalid projection text field: " + name);
    }
    return text;
  }

  static UUID uuid(Map<String, Object> payload, String name) {
    try {
      return UUID.fromString(text(payload, name));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid projection UUID field: " + name, invalid);
    }
  }

  static UUID optionalUuid(Map<String, Object> payload, String name) {
    String value = optionalText(payload, name);
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid projection UUID field: " + name, invalid);
    }
  }

  static Instant instant(Map<String, Object> payload, String name) {
    try {
      return Instant.parse(text(payload, name));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid projection timestamp field: " + name, invalid);
    }
  }

  static Instant optionalInstant(Map<String, Object> payload, String name) {
    String value = optionalText(payload, name);
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid projection timestamp field: " + name, invalid);
    }
  }

  static LocalDate optionalDate(Map<String, Object> payload, String name) {
    String value = optionalText(payload, name);
    if (value == null) return null;
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid projection date field: " + name, invalid);
    }
  }

  static LocalTime optionalTime(Map<String, Object> payload, String name) {
    String value = optionalText(payload, name);
    if (value == null) return null;
    try {
      return LocalTime.parse(value);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid projection time field: " + name, invalid);
    }
  }

  static boolean bool(Map<String, Object> payload, String name) {
    Object value = required(payload, name);
    if (!(value instanceof Boolean bool)) {
      throw new IllegalArgumentException("invalid projection boolean field: " + name);
    }
    return bool;
  }

  static Boolean optionalBool(Map<String, Object> payload, String name) {
    Object value = payload.get(name);
    if (value == null) return null;
    if (!(value instanceof Boolean bool)) {
      throw new IllegalArgumentException("invalid projection boolean field: " + name);
    }
    return bool;
  }

  static int integer(Map<String, Object> payload, String name) {
    long value = number(payload, name);
    if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
      throw new IllegalArgumentException("projection integer out of range: " + name);
    }
    return (int) value;
  }

  static Integer optionalInteger(Map<String, Object> payload, String name) {
    if (!payload.containsKey(name) || payload.get(name) == null) return null;
    return integer(payload, name);
  }

  static long number(Map<String, Object> payload, String name) {
    Object value = required(payload, name);
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("invalid projection number field: " + name);
    }
    return number.longValue();
  }

  static long optionalNumber(Map<String, Object> payload, String name, long defaultValue) {
    if (!payload.containsKey(name) || payload.get(name) == null) return defaultValue;
    return number(payload, name);
  }

  static void aggregate(ProjectionEvent event, UUID expected) {
    if (event.aggregateId() == null || !event.aggregateId().equals(expected)) {
      throw new IllegalArgumentException("projection aggregateId mismatch");
    }
  }

  static long aggregateVersion(ProjectionEvent event) {
    if (event.aggregateVersion() == null || event.aggregateVersion() <= 0) {
      throw new IllegalArgumentException("projection aggregateVersion is required");
    }
    return event.aggregateVersion();
  }
}
