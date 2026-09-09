package com.estoma.platformbff.api.admin;

import com.estoma.platformbff.application.academic.StudentImportUploadService;
import com.estoma.platformbff.application.academic.StudentImportUploadService.UploadedFile;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/academic/import-files")
public final class AcademicImportFileController {
  static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  private final StudentImportUploadService uploads;
  private final LocalAuthorizationService authorization;

  public AcademicImportFileController(
      StudentImportUploadService uploads, LocalAuthorizationService authorization) {
    this.uploads = uploads;
    this.authorization = authorization;
  }

  @PutMapping(value = "/{fileReference}", consumes = XLSX, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<UploadedFile> upload(
      @PathVariable UUID fileReference,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false, defaultValue = "-1")
          long contentLength,
      HttpServletRequest request) throws IOException {
    authorization.requireSuperAdministrator();
    UploadedFile uploaded = uploads.upload(
        fileReference, idempotencyKey, contentLength, request.getInputStream());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(uploaded);
  }
}
