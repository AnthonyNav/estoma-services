package com.estoma.platformbff.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {
  @Test
  void invalidRequestUsesCanonicalProblemDetailsShape() {
    var response = new ApiExceptionHandler().invalidRequest();

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().type()).isEqualTo("urn:estoma:error:BFF.INVALID_REQUEST");
    assertThat(response.getBody().code()).isEqualTo("BFF.INVALID_REQUEST");
    assertThat(response.getBody().traceId()).isNotBlank();
  }
}
