package com.estoma.platformbff.application.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class StudentImportUploadServiceTest {
  private static final UUID REFERENCE = UUID.fromString("14557249-bd65-58f7-a672-dc2a2d462c95");

  @Test
  void hashesExactBytesStoresThemAndDeletesTheTemporaryFile() {
    RecordingStore store = new RecordingStore(false);
    var service = new StudentImportUploadService(Optional.of(store), properties(1024));
    byte[] content = "xlsx-test-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    var uploaded = service.upload(
        REFERENCE, "f1476bd3-5823-4cc4-b5f1-08d0272c1a80", content.length,
        new ByteArrayInputStream(content));

    assertThat(uploaded.fileReference()).isEqualTo(REFERENCE);
    assertThat(uploaded.fileChecksum())
        .isEqualTo("5b44fbbabc598f8a9f14a43956880649ff7a2e246bbd85a3d429840c9ac058df");
    assertThat(uploaded.sizeBytes()).isEqualTo(content.length);
    assertThat(uploaded.replayed()).isFalse();
    assertThat(store.content).isEqualTo(content);
    assertThat(store.path).doesNotExist();
    assertThat(store.idempotencyDigest).hasSize(64).doesNotContain("f1476bd3");
  }

  @Test
  void reportsAnExactStoreReplay() {
    RecordingStore store = new RecordingStore(true);
    var service = new StudentImportUploadService(Optional.of(store), properties(1024));

    var uploaded = service.upload(
        REFERENCE, "same-key", 3, new ByteArrayInputStream(new byte[] {1, 2, 3}));

    assertThat(uploaded.replayed()).isTrue();
  }

  @Test
  void rejectsAnOversizedStreamEvenWithoutContentLength() {
    var service = new StudentImportUploadService(
        Optional.of(new RecordingStore(false)), properties(3));

    assertThatThrownBy(() -> service.upload(
        REFERENCE, "key", -1, new ByteArrayInputStream(new byte[] {1, 2, 3, 4})))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(413);
  }

  @Test
  void disabledStorageFailsBeforeReadingTheRequestBody() {
    var service = new StudentImportUploadService(Optional.empty(), properties(1024));
    var unread = new java.io.InputStream() {
      @Override public int read() {
        throw new AssertionError("disabled upload must not read request content");
      }
    };

    assertThatThrownBy(() -> service.upload(REFERENCE, "key", -1, unread))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(503);
  }

  private static StudentImportUploadProperties properties(long maxBytes) {
    return new StudentImportUploadProperties(false, maxBytes,
        new StudentImportUploadProperties.Storage("bucket", "student-imports", "region", null));
  }

  private static final class RecordingStore implements StudentImportUploadStore {
    private final boolean replay;
    private Path path;
    private byte[] content;
    private String idempotencyDigest;

    private RecordingStore(boolean replay) {
      this.replay = replay;
    }

    @Override
    public StoreResult putIfAbsent(
        UUID reference, Path file, long size, String checksum, String idempotencyKeySha256)
        throws java.io.IOException {
      this.path = file;
      this.content = Files.readAllBytes(file);
      this.idempotencyDigest = idempotencyKeySha256;
      assertThat(reference).isEqualTo(REFERENCE);
      assertThat(size).isEqualTo(content.length);
      return new StoreResult(replay);
    }
  }
}
