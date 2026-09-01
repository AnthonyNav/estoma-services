package com.estoma.platformbff.application.mailbox;

import com.estoma.platformbff.application.port.ProjectionQueryPort.MailboxCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Versioned opaque cursor; the HTTP boundary never accepts raw database ordering values. */
final class MailboxCursorCodec {
  private static final String PREFIX = "v1:";

  private MailboxCursorCodec() {
  }

  static String encode(MailboxCursor cursor) {
    if (cursor == null) return null;
    String value = PREFIX + cursor.createdAt() + "|" + cursor.inboxMessageId();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  static MailboxCursor decode(String cursor) {
    if (cursor == null || cursor.isBlank()) return null;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (!decoded.startsWith(PREFIX)) throw new IllegalArgumentException("unsupported cursor version");
      String[] parts = decoded.substring(PREFIX.length()).split("\\|", -1);
      if (parts.length != 2) throw new IllegalArgumentException("invalid cursor shape");
      return new MailboxCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid mailbox cursor", invalid);
    }
  }
}
