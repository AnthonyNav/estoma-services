package com.estoma.authentication.contracts.result;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The common V1 payload for durable Results and security RPC responses:
 * {@code status}, {@code data}, and {@code error}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultPayload(ResultStatus status, Object data, ErrorPayload error) {

  public static ResultPayload succeeded(Object data) {
    return new ResultPayload(ResultStatus.SUCCEEDED, data, null);
  }

  public static ResultPayload rejected(String code) {
    return new ResultPayload(ResultStatus.REJECTED, null, ErrorPayload.of(code));
  }

  public static ResultPayload failed(String code) {
    return new ResultPayload(ResultStatus.FAILED, null, ErrorPayload.of(code));
  }
}
