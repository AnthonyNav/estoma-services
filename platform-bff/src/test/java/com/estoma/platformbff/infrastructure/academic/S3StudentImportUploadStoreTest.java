package com.estoma.platformbff.infrastructure.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.academic.StudentImportUploadProperties;
import com.estoma.platformbff.application.academic.StudentImportUploadStore.ObjectConflictException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3StudentImportUploadStoreTest {
  private static final UUID REFERENCE = UUID.fromString("14557249-bd65-58f7-a672-dc2a2d462c95");

  @Test
  void enabledConfigurationSelectsTheProductionConstructor() {
    new ApplicationContextRunner()
        .withUserConfiguration(StoreConfiguration.class)
        .withPropertyValues(
            "platform.student-import.upload.enabled=true",
            "platform.student-import.upload.storage.bucket=bucket",
            "platform.student-import.upload.storage.prefix=uploads/",
            "platform.student-import.upload.storage.region=us-east-1")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(S3StudentImportUploadStore.class);
        });
  }

  @Test
  void createsAnImmutableObjectWithIntegrityMetadata() throws Exception {
    S3Client client = mock(S3Client.class);
    var store = new S3StudentImportUploadStore(storage(), client);
    var file = Files.createTempFile("upload-store-", ".xlsx");
    Files.write(file, new byte[] {1, 2, 3});

    var result = store.putIfAbsent(REFERENCE, file, 3, "checksum", "key-digest");

    assertThat(result.replayed()).isFalse();
    var request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(client).putObject(request.capture(), any(RequestBody.class));
    verify(client, never()).headObject(any(HeadObjectRequest.class));
    assertThat(request.getValue().bucket()).isEqualTo("bucket");
    assertThat(request.getValue().key())
        .isEqualTo("student-imports/" + REFERENCE + ".xlsx");
    assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
    assertThat(request.getValue().metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
        S3StudentImportUploadStore.CHECKSUM_METADATA, "checksum",
        S3StudentImportUploadStore.IDEMPOTENCY_METADATA, "key-digest"));
    Files.delete(file);
  }

  @Test
  void exactExistingObjectIsAReplayAndDifferentContentFailsClosed() throws Exception {
    S3Client client = mock(S3Client.class);
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().statusCode(412).build());
    when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
        .contentLength(3L).metadata(Map.of(
            S3StudentImportUploadStore.CHECKSUM_METADATA, "checksum",
            S3StudentImportUploadStore.IDEMPOTENCY_METADATA, "key-digest")).build());
    var store = new S3StudentImportUploadStore(storage(), client);
    var file = Files.createTempFile("upload-store-", ".xlsx");

    assertThat(store.putIfAbsent(REFERENCE, file, 3, "checksum", "key-digest").replayed()).isTrue();
    assertThatThrownBy(() -> store.putIfAbsent(REFERENCE, file, 4, "other", "key-digest"))
        .isInstanceOf(ObjectConflictException.class);
    Files.delete(file);
  }

  private static StudentImportUploadProperties.Storage storage() {
    return new StudentImportUploadProperties.Storage(
        "bucket", "student-imports", "us-east-1", null);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(StudentImportUploadProperties.class)
  @Import(S3StudentImportUploadStore.class)
  static class StoreConfiguration {}
}
