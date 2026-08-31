package com.estoma.platformbff.application.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.application.port.ProjectionQueryPort.MailboxCursor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MailboxCursorCodecTest {
  @Test
  void roundTripsTheStableDatabaseTieBreakerWithoutExposingRawParameters() {
    MailboxCursor cursor = new MailboxCursor(Instant.parse("2026-08-30T18:30:00Z"), UUID.randomUUID());

    String encoded = MailboxCursorCodec.encode(cursor);

    assertThat(encoded).doesNotContain("2026-08-30", cursor.inboxMessageId().toString());
    assertThat(MailboxCursorCodec.decode(encoded)).isEqualTo(cursor);
  }

  @Test
  void rejectsMalformedCursors() {
    assertThatThrownBy(() -> MailboxCursorCodec.decode("not-a-cursor"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
