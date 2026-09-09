package com.estoma.platformbff.application.academic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

public interface StudentImportUploadStore {
  StoreResult putIfAbsent(
      UUID fileReference, Path file, long sizeBytes, String sha256, String idempotencyKeySha256)
      throws IOException;

  record StoreResult(boolean replayed) {}

  final class ObjectConflictException extends IOException {
    public ObjectConflictException() {
      super("student import object reference already contains different content");
    }
  }
}
