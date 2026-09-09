package com.estoma.platformbff.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.estoma.platformbff.application.academic.StudentImportUploadService;
import com.estoma.platformbff.application.academic.StudentImportUploadService.UploadedFile;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AcademicImportFileControllerTest {
  private static final UUID REFERENCE = UUID.fromString("14557249-bd65-58f7-a672-dc2a2d462c95");
  private StudentImportUploadService uploads;
  private LocalAuthorizationService authorization;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    uploads = mock(StudentImportUploadService.class);
    authorization = mock(LocalAuthorizationService.class);
    mvc = MockMvcBuilders.standaloneSetup(
        new AcademicImportFileController(uploads, authorization)).build();
  }

  @Test
  void requiresSuperadminAndReturnsFrozenReferenceAndChecksum() throws Exception {
    when(uploads.upload(any(), anyString(), anyLong(), any()))
        .thenReturn(new UploadedFile(REFERENCE, "a".repeat(64), 4, false));

    mvc.perform(put("/api/v1/admin/academic/import-files/{reference}", REFERENCE)
            .header("Idempotency-Key", "582e6ea7-47bb-40ca-b322-2d74b60ab365")
            .contentType(AcademicImportFileController.XLSX)
            .content(new byte[] {1, 2, 3, 4}))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.fileReference").value(REFERENCE.toString()))
        .andExpect(jsonPath("$.fileChecksum").value("a".repeat(64)))
        .andExpect(jsonPath("$.sizeBytes").value(4))
        .andExpect(jsonPath("$.replayed").value(false));

    verify(authorization).requireSuperAdministrator();
    verify(uploads).upload(any(), anyString(), anyLong(), any());
  }

  @Test
  void rejectsMissingIdempotencyKeyBeforeCallingTheUpload() throws Exception {
    mvc.perform(put("/api/v1/admin/academic/import-files/{reference}", REFERENCE)
            .contentType(AcademicImportFileController.XLSX)
            .content(new byte[] {1}))
        .andExpect(status().isBadRequest());
  }
}
