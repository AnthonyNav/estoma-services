package com.estoma.authentication.infrastructure.messaging.rpc;

/**
 * Wire shape of every {@code RPC_RESPONSE}, reusing the {@code
 * status}/{@code data}/{@code error} shape messaging-architecture.md §3
 * defines for a durable Result -- there is no separate documented shape
 * for RPC responses, and this keeps the platform's response vocabulary
 * consistent. {@code status} is one of {@code SUCCEEDED} or {@code
 * REJECTED} (RPC has no {@code FAILED}/{@code EXPIRED}: a technical
 * failure here just means the BFF's timeout fires -- system_contract.md
 * "RPC Security Rules").
 */
public record RpcResponseEnvelope(String status, Object data, RpcError error) {

  public static RpcResponseEnvelope succeeded(Object data) {
    return new RpcResponseEnvelope("SUCCEEDED", data, null);
  }

  public static RpcResponseEnvelope rejected(String code) {
    return new RpcResponseEnvelope("REJECTED", null, new RpcError(code));
  }

  public record RpcError(String code) {}
}
