package com.estoma.authentication.contracts.result;

/**
 * The common V1 payload for durable Results and security RPC responses:
 * {@code status}, {@code data}, and {@code error}.
 */
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
