package com.estoma.washadministration.contracts.result;

import java.util.Map;

public record ErrorPayload(String code, String message, Map<String, Object> details) {

  public ErrorPayload(String code) {
    this(code, null, null);
  }
}
