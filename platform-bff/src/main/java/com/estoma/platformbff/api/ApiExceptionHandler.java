package com.estoma.platformbff.api;

import com.estoma.platformbff.application.commands.CommandGatewayService.IdempotencyConflictException;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService.AuthorizationDeniedException;
import com.estoma.platformbff.application.security.AuthenticationRejectedException;
import com.estoma.platformbff.application.security.AuthenticationRequiredException;
import com.estoma.platformbff.application.security.SecurityRpcUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(AuthenticationRejectedException.class)
  ResponseEntity<ProblemResponse> rejected() {
    return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "The authentication request was rejected.");
  }

  @ExceptionHandler(AuthenticationRequiredException.class)
  ResponseEntity<ProblemResponse> required() {
    return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required.");
  }

  @ExceptionHandler(SecurityRpcUnavailableException.class)
  ResponseEntity<ProblemResponse> unavailable() {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
        "Authentication is temporarily unavailable.");
  }

  @ExceptionHandler(ProjectionUnavailableException.class)
  ResponseEntity<ProblemResponse> projectionUnavailable() {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
        "The required local projection is temporarily unavailable.");
  }

  @ExceptionHandler(AuthorizationDeniedException.class)
  ResponseEntity<ProblemResponse> forbidden() {
    return problem(HttpStatus.FORBIDDEN, "Forbidden", "The authenticated account is not authorized.");
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<ProblemResponse> conflict() {
    return problem(HttpStatus.CONFLICT, "Conflict", "Idempotency-Key conflicts with a prior request.");
  }

  @ExceptionHandler({
      IllegalArgumentException.class,
      MethodArgumentNotValidException.class,
      MissingRequestHeaderException.class,
      HttpMessageNotReadableException.class
  })
  ResponseEntity<ProblemResponse> invalidRequest() {
    return problem(HttpStatus.BAD_REQUEST, "Bad Request", "The request is invalid.");
  }

  private static ResponseEntity<ProblemResponse> problem(
      HttpStatus status, String title, String detail) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(new ProblemResponse("about:blank", title, status.value(), detail));
  }

  public record ProblemResponse(String type, String title, int status, String detail) {}
}
