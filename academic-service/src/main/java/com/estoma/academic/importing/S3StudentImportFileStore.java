package com.estoma.academic.importing;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@ConditionalOnProperty(prefix = "estoma.student-import.storage", name = "enabled", havingValue = "true")
final class S3StudentImportFileStore implements StudentImportFileStore, AutoCloseable {
  private final StudentImportProperties.Storage storage;
  private final S3Client client;

  S3StudentImportFileStore(StudentImportProperties properties) {
    storage = properties.storage();
    var builder = S3Client.builder()
        .region(Region.of(storage.region()))
        .credentialsProvider(DefaultCredentialsProvider.create());
    if (storage.endpoint() != null) {
      builder.endpointOverride(storage.endpoint())
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }
    client = builder.build();
  }

  @Override
  public InputStream open(UUID fileReference) throws IOException {
    try {
      return client.getObject(GetObjectRequest.builder().bucket(storage.bucket())
          .key(storage.prefix() + "/" + fileReference + ".xlsx").build());
    } catch (S3Exception ex) {
      throw new IOException("student import object unavailable", ex);
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
