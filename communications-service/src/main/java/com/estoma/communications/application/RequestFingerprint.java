package com.estoma.communications.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;
import com.estoma.communications.contracts.command.RequestCommunicationCommand;

/** SHA-256 over a fixed-order, null-normalized functional request representation. */
public final class RequestFingerprint {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private RequestFingerprint() {}
  public static String sha256(String... values) {
    String canonical = String.join("\u001f", java.util.Arrays.stream(values).map(RequestFingerprint::canonicalValue).toList());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
  /** Canonical functional request representation used before mutable validation. */
  public static String forCommand(RequestCommunicationCommand command, List<String> channels) {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("recipientAccountId", command.recipientAccountId());
    canonical.put("systemCode", command.systemCode());
    canonical.put("communicationType", command.communicationType());
    canonical.put("priority", command.priority());
    canonical.put("sourceService", command.sourceService());
    canonical.put("sourceReferenceType", command.sourceReferenceType());
    canonical.put("sourceReferenceId", command.sourceReferenceId());
    canonical.put("initiatedByAccountId", command.initiatedByAccountId());
    canonical.put("requestedChannels", channels);
    canonical.put("inbox", canonicalInbox(command.inbox()));
    canonical.put("email", canonicalEmail(command.email()));
    try {
      String json = OBJECT_MAPPER.writeValueAsString(normalize(canonical));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("cannot canonicalize communication request", e);
    }
  }

  private static Map<String, Object> canonicalInbox(RequestCommunicationCommand.InboxContent inbox) {
    if (inbox == null) return null;
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("title", inbox.title());
    canonical.put("body", inbox.body());
    canonical.put("expiresAt", inbox.expiresAt());
    return canonical;
  }

  private static Map<String, Object> canonicalEmail(RequestCommunicationCommand.EmailContent email) {
    if (email == null) return null;
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("contentMode", email.contentMode());
    canonical.put("senderCode", email.senderCode());
    canonical.put("subject", email.subject());
    canonical.put("textBody", email.textBody());
    canonical.put("htmlBody", email.htmlBody());
    canonical.put("templateCode", email.templateCode());
    canonical.put("templateData", normalizeTemplateData(email.templateData()));
    return canonical;
  }

  private static Object normalizeTemplateData(String templateData) {
    if (templateData == null) return null;
    try {
      return normalize(OBJECT_MAPPER.readValue(templateData, Object.class));
    } catch (Exception ignored) {
      return templateData;
    }
  }
  private static String marker(String value) { return value == null ? "<null>" : value.isEmpty() ? "<empty>" : value; }

  private static String canonicalValue(String value) {
    if (value == null || value.isEmpty()) return marker(value);
    try {
      Object parsed = OBJECT_MAPPER.readValue(value, Object.class);
      return OBJECT_MAPPER.writeValueAsString(normalize(parsed));
    } catch (Exception ignored) {
      return marker(value);
    }
  }

  @SuppressWarnings("unchecked")
  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
      return sorted;
    }
    if (value instanceof List<?> list) return list.stream().map(RequestFingerprint::normalize).toList();
    return value;
  }
}
