package com.estoma.authorization.application.command;

import com.estoma.authorization.application.support.CanonicalJson;
import java.util.Map;

public record ApplicationResult(Status status, String dataJson, Error error) {

  public enum Status {
    SUCCEEDED,
    REJECTED,
    FAILED
  }

  public record Error(String code, String details) {}

  public static ApplicationResult succeeded(Map<String, ?> data) {
    return new ApplicationResult(Status.SUCCEEDED, CanonicalJson.write(data), null);
  }

  public static ApplicationResult rejected(String code, String details) {
    return new ApplicationResult(Status.REJECTED, null, new Error(code, details));
  }

  public static ApplicationResult failed(String code, String details) {
    return new ApplicationResult(Status.FAILED, null, new Error(code, details));
  }
}
