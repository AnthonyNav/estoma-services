package com.estoma.platformbff.infrastructure.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.application.academic.StudentImportUploadProperties;
import com.estoma.platformbff.application.academic.StudentImportUploadStore.ObjectConflictException;
import java.net.URI;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Testcontainers(disabledWithoutDocker = true)
class S3StudentImportUploadStoreMinioIT {
  private static final String ACCESS_KEY = "estoma-test-admin";
  private static final String SECRET_KEY = "estoma-test-secret-key";
  private static final String BUCKET = "student-import-test";

  @Container
  static final GenericContainer<?> MINIO =
      new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z")
          .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
          .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
          .withCommand("server", "/data")
          .withExposedPorts(9000);

  private static S3Client client;
  private static S3StudentImportUploadStore store;

  @BeforeAll
  static void configureStore() {
    URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
    client = S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
    client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    store = new S3StudentImportUploadStore(
        new StudentImportUploadProperties.Storage(BUCKET, "uploads", "us-east-1", endpoint),
        client);
  }

  @Test
  void providerPreservesImmutableCreateReplayConflictAndConcurrentCreate() throws Exception {
    UUID reference = UUID.randomUUID();
    var first = Files.createTempFile("student-import-minio-", ".xlsx");
    var different = Files.createTempFile("student-import-minio-", ".xlsx");
    Files.write(first, new byte[] {1, 2, 3});
    Files.write(different, new byte[] {4, 5, 6});
    try {
      assertThat(store.putIfAbsent(reference, first, 3, "checksum-a", "key-a").replayed()).isFalse();
      assertThat(store.putIfAbsent(reference, first, 3, "checksum-a", "key-a").replayed()).isTrue();
      assertThatThrownBy(() ->
          store.putIfAbsent(reference, different, 3, "checksum-b", "key-a"))
          .isInstanceOf(ObjectConflictException.class);
      assertThatThrownBy(() ->
          store.putIfAbsent(reference, first, 3, "checksum-a", "key-b"))
          .isInstanceOf(ObjectConflictException.class);

      UUID racedReference = UUID.randomUUID();
      try (var executor = Executors.newFixedThreadPool(2)) {
        Callable<Boolean> upload = () ->
            store.putIfAbsent(racedReference, first, 3, "checksum-a", "race-key").replayed();
        var results = executor.invokeAll(java.util.List.of(upload, upload)).stream()
            .map(future -> {
              try {
                return future.get();
              } catch (Exception ex) {
                throw new AssertionError(ex);
              }
            }).toList();
        assertThat(results).containsExactlyInAnyOrder(false, true);
      }
    } finally {
      Files.deleteIfExists(first);
      Files.deleteIfExists(different);
    }
  }
}
