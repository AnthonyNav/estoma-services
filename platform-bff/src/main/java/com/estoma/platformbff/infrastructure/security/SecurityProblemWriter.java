package com.estoma.platformbff.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

/** Writes the public Problem contract for failures raised inside the security filter chain. */
public final class SecurityProblemWriter {
  private SecurityProblemWriter() {}

  public static void authenticationRequired(HttpServletResponse response) throws IOException {
    write(response, HttpServletResponse.SC_UNAUTHORIZED, "BFF.AUTHENTICATION_REQUIRED",
        "Unauthorized", "Authentication is required.");
  }

  public static void accessDenied(HttpServletResponse response) throws IOException {
    write(response, HttpServletResponse.SC_FORBIDDEN, "BFF.ACCESS_DENIED",
        "Forbidden", "The authenticated account is not authorized.");
  }

  public static void invalidToken(HttpServletResponse response) throws IOException {
    write(response, HttpServletResponse.SC_UNAUTHORIZED, "BFF.AUTHENTICATION_REQUIRED",
        "Unauthorized", "Authentication is required.");
  }

  private static void write(
      HttpServletResponse response, int status, String code, String title, String detail)
      throws IOException {
    String traceId = MDC.get("traceId");
    if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getWriter().write("{\"type\":\"urn:estoma:error:" + code
        + "\",\"title\":\"" + title + "\",\"status\":" + status
        + ",\"code\":\"" + code + "\",\"detail\":\"" + detail
        + "\",\"traceId\":\"" + json(traceId) + "\"}");
  }

  private static String json(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r");
  }
}
