package com.estoma.platformbff.application.projection;

/** Raised when an endpoint would otherwise serve or authorize from an untrustworthy projection. */
public class ProjectionUnavailableException extends RuntimeException {
  public ProjectionUnavailableException(String message) {
    super(message);
  }
}
