package com.estoma.platformbff.application.academic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public final class StudentImportUploadService {
  private final Optional<StudentImportUploadStore> store;
  private final long maxObjectBytes;

  public StudentImportUploadService(
      Optional<StudentImportUploadStore> store, StudentImportUploadProperties properties) {
    this.store = store;
    this.maxObjectBytes = properties.maxObjectBytes();
  }

  public UploadedFile upload(
      UUID fileReference, String idempotencyKey, long declaredLength, InputStream source) {
    StudentImportUploadStore enabledStore = store.orElseThrow(() ->
        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "student import upload storage is disabled"));
    requireIdempotencyKey(idempotencyKey);
    if (declaredLength > maxObjectBytes) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "student import file exceeds size limit");
    }
    Path temporary = null;
    try {
      temporary = Files.createTempFile("estoma-student-import-", ".xlsx");
      DigestCopy copied = copyBounded(source, temporary);
      if (copied.sizeBytes == 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "student import file is empty");
      }
      String keyDigest = sha256(idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var stored = enabledStore.putIfAbsent(
          fileReference, temporary, copied.sizeBytes, copied.sha256, keyDigest);
      return new UploadedFile(fileReference, copied.sha256, copied.sizeBytes, stored.replayed());
    } catch (StudentImportUploadStore.ObjectConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "fileReference was already used for different content or idempotency key", ex);
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "student import storage is unavailable", ex);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The upload outcome is authoritative; temporary-file cleanup is best effort.
        }
      }
    }
  }

  private DigestCopy copyBounded(InputStream source, Path target) throws IOException {
    MessageDigest digest = digest();
    long total = 0;
    byte[] buffer = new byte[64 * 1024];
    try (OutputStream output = Files.newOutputStream(target)) {
      int read;
      while ((read = source.read(buffer)) != -1) {
        total += read;
        if (total > maxObjectBytes) {
          throw new ResponseStatusException(
              HttpStatus.PAYLOAD_TOO_LARGE, "student import file exceeds size limit");
        }
        output.write(buffer, 0, read);
        digest.update(buffer, 0, read);
      }
    }
    return new DigestCopy(total, HexFormat.of().formatHex(digest.digest()));
  }

  private static String sha256(byte[] value) {
    MessageDigest digest = digest();
    return HexFormat.of().formatHex(digest.digest(value));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static void requireIdempotencyKey(String value) {
    if (value == null || value.isBlank() || value.length() > 200) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Idempotency-Key is required and must fit the V1 contract");
    }
  }

  private record DigestCopy(long sizeBytes, String sha256) {}

  public record UploadedFile(
      @NotNull UUID fileReference,
      @NotNull @Size(min = 64, max = 64) String fileChecksum,
      @Positive long sizeBytes,
      boolean replayed) {}
}
