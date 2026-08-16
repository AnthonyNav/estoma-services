package com.estoma.academic.importing;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("estoma.student-import")
public record StudentImportProperties(
    @DefaultValue Storage storage,
    @DefaultValue("20971520") int maxObjectBytes,
    @DefaultValue("209715200") long maxExpandedZipBytes,
    @DefaultValue("10000") int maxNrcRows,
    @DefaultValue("25000") int maxStudentRows,
    @DefaultValue("200000") int maxEnrollmentRows,
    @DefaultValue("4096") int maxCellCharacters,
    @DefaultValue("100") int validationErrorBatchSize,
    @DefaultValue("60s") Duration parseTimeout) {
  public StudentImportProperties {
    storage = storage == null ? new Storage(false, null, null, null, null) : storage;
    if (maxObjectBytes < 1 || maxExpandedZipBytes < 1 || maxNrcRows < 1 || maxStudentRows < 1
        || maxEnrollmentRows < 1 || maxCellCharacters < 1
        || validationErrorBatchSize < 1 || validationErrorBatchSize > 200
        || parseTimeout == null || parseTimeout.isZero() || parseTimeout.isNegative()) {
      throw new IllegalArgumentException("invalid estoma.student-import configuration");
    }
  }

  public record Storage(boolean enabled, String bucket, String prefix, String region, URI endpoint) {
    public Storage {
      prefix = prefix == null ? "student-imports" : prefix.replaceAll("^/+|/+$", "");
      if (enabled && (bucket == null || bucket.isBlank() || region == null || region.isBlank())) {
        throw new IllegalArgumentException("student import storage requires bucket and region");
      }
    }
  }
}
