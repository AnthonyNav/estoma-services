package com.estoma.platformbff.application.academic;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("platform.student-import.upload")
public record StudentImportUploadProperties(
    boolean enabled,
    @DefaultValue("20971520") long maxObjectBytes,
    @DefaultValue Storage storage) {

  public StudentImportUploadProperties {
    storage = storage == null ? new Storage(null, null, null, null) : storage;
    if (maxObjectBytes < 1) throw new IllegalArgumentException("student import upload max size must be positive");
    if (enabled && (blank(storage.bucket()) || blank(storage.region()))) {
      throw new IllegalArgumentException("student import upload requires bucket and region");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record Storage(String bucket, String prefix, String region, URI endpoint) {
    public Storage {
      prefix = prefix == null || prefix.isBlank()
          ? "student-imports" : prefix.replaceAll("^/+|/+$", "");
    }
  }
}
