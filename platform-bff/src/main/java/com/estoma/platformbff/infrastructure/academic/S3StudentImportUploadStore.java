package com.estoma.platformbff.infrastructure.academic;

import com.estoma.platformbff.application.academic.StudentImportUploadProperties;
import com.estoma.platformbff.application.academic.StudentImportUploadStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@ConditionalOnProperty(prefix = "platform.student-import.upload", name = "enabled", havingValue = "true")
final class S3StudentImportUploadStore implements StudentImportUploadStore, AutoCloseable {
  static final String CHECKSUM_METADATA = "estoma-sha256";
  static final String IDEMPOTENCY_METADATA = "estoma-idempotency-sha256";

  private final StudentImportUploadProperties.Storage storage;
  private final S3Client client;

  @Autowired
  S3StudentImportUploadStore(StudentImportUploadProperties properties) {
    this(properties.storage(), client(properties.storage()));
  }

  S3StudentImportUploadStore(StudentImportUploadProperties.Storage storage, S3Client client) {
    this.storage = storage;
    this.client = client;
  }

  @Override
  public StoreResult putIfAbsent(
      UUID fileReference, Path file, long sizeBytes, String sha256, String idempotencyKeySha256)
      throws IOException {
    String key = key(fileReference);
    try {
      client.putObject(PutObjectRequest.builder()
              .bucket(storage.bucket()).key(key)
              .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
              .contentLength(sizeBytes).ifNoneMatch("*")
              .metadata(Map.of(
                  CHECKSUM_METADATA, sha256,
                  IDEMPOTENCY_METADATA, idempotencyKeySha256))
              .build(),
          RequestBody.fromFile(file));
      return new StoreResult(false);
    } catch (S3Exception ex) {
      if (ex.statusCode() == 412) {
        HeadObjectResponse existing = head(key);
        assertExact(existing, sizeBytes, sha256, idempotencyKeySha256);
        return new StoreResult(true);
      }
      throw new IOException("student import object upload failed", ex);
    }
  }

  private HeadObjectResponse head(String key) throws IOException {
    try {
      return client.headObject(HeadObjectRequest.builder().bucket(storage.bucket()).key(key).build());
    } catch (S3Exception ex) {
      throw new IOException("student import object lookup failed", ex);
    }
  }

  private static void assertExact(
      HeadObjectResponse existing, long sizeBytes, String sha256, String idempotencyKeySha256)
      throws ObjectConflictException {
    if (existing.contentLength() != sizeBytes
        || !sha256.equals(existing.metadata().get(CHECKSUM_METADATA))
        || !idempotencyKeySha256.equals(existing.metadata().get(IDEMPOTENCY_METADATA))) {
      throw new ObjectConflictException();
    }
  }

  private String key(UUID reference) {
    return storage.prefix() + "/" + reference + ".xlsx";
  }

  private static S3Client client(StudentImportUploadProperties.Storage storage) {
    var builder = S3Client.builder()
        .region(Region.of(storage.region()))
        .credentialsProvider(DefaultCredentialsProvider.create());
    if (storage.endpoint() != null) {
      builder.endpointOverride(storage.endpoint())
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }
    return builder.build();
  }

  @Override
  public void close() {
    client.close();
  }
}
