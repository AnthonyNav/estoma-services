package com.estoma.authentication.infrastructure.messaging.rpc;

import com.estoma.authentication.application.rpc.AuthenticateAccountHandler;
import com.estoma.authentication.application.rpc.ChangePasswordHandler;
import com.estoma.authentication.application.rpc.GetRecoveryCodeForDeliveryHandler;
import com.estoma.authentication.application.rpc.RefreshSessionHandler;
import com.estoma.authentication.application.rpc.RegenerateTemporaryPasswordHandler;
import com.estoma.authentication.application.rpc.ResetPasswordHandler;
import com.estoma.authentication.application.rpc.RevokeSessionHandler;
import com.estoma.authentication.application.rpc.RpcHandlerOutcome;
import com.estoma.authentication.application.rpc.SetTemporaryPasswordHandler;
import com.estoma.authentication.application.rpc.ValidateRecoveryCodeHandler;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountRequest;
import com.estoma.authentication.contracts.rpc.ChangePasswordRequest;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryRequest;
import com.estoma.authentication.contracts.rpc.RefreshSessionRequest;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.contracts.rpc.RevokeSessionRequest;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeRequest;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

/**
 * The one piece of new glue code this tramo adds for RPC: maps each
 * {@link AuthenticationRpcOperation} wire name to its already-built,
 * already-tested handler bean, decoding the raw message body into that
 * operation's specific request type. Every handler already follows the
 * uniform {@code handle(Request) -> RpcHandlerOutcome<Response>} shape,
 * so no operation-specific listener code is needed beyond this table.
 */
@Component
public class RpcOperationRouter {

  private final EnvelopeJsonCodec codec;
  private final Map<String, BiFunction<RpcRequestContext, byte[], RpcHandlerOutcome<Object>>> handlersByOperation;

  public RpcOperationRouter(
      EnvelopeJsonCodec codec,
      AuthenticateAccountHandler authenticateAccountHandler,
      RefreshSessionHandler refreshSessionHandler,
      RevokeSessionHandler revokeSessionHandler,
      ValidateRecoveryCodeHandler validateRecoveryCodeHandler,
      ResetPasswordHandler resetPasswordHandler,
      ChangePasswordHandler changePasswordHandler,
      GetRecoveryCodeForDeliveryHandler getRecoveryCodeForDeliveryHandler,
      SetTemporaryPasswordHandler setTemporaryPasswordHandler,
      RegenerateTemporaryPasswordHandler regenerateTemporaryPasswordHandler) {
    this.codec = codec;
    this.handlersByOperation =
        Map.ofEntries(
             binding(AuthenticationRpcOperation.AUTHENTICATE_ACCOUNT, AuthenticateAccountRequest.class, (context, request) -> authenticateAccountHandler.handle(request)),
             binding(AuthenticationRpcOperation.REFRESH_SESSION, RefreshSessionRequest.class, refreshSessionHandler::handle),
             binding(AuthenticationRpcOperation.REVOKE_SESSION, RevokeSessionRequest.class, revokeSessionHandler::handle),
             binding(AuthenticationRpcOperation.VALIDATE_RECOVERY_CODE, ValidateRecoveryCodeRequest.class, (context, request) -> validateRecoveryCodeHandler.handle(request)),
             binding(AuthenticationRpcOperation.RESET_PASSWORD, ResetPasswordRequest.class, (context, request) -> resetPasswordHandler.handle(request)),
             binding(AuthenticationRpcOperation.CHANGE_PASSWORD, ChangePasswordRequest.class, changePasswordHandler::handle),
            binding(
                AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY,
                 GetRecoveryCodeForDeliveryRequest.class,
                 getRecoveryCodeForDeliveryHandler::handle),
             binding(AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD, SetTemporaryPasswordRequest.class, setTemporaryPasswordHandler::handle),
            binding(
                AuthenticationRpcOperation.REGENERATE_TEMPORARY_PASSWORD,
                RegenerateTemporaryPasswordRequest.class,
                 regenerateTemporaryPasswordHandler::handle));
  }

  public boolean supports(String operationWireName) {
    return handlersByOperation.containsKey(operationWireName);
  }

  /** @throws IllegalArgumentException if {@code operationWireName} is not a recognized RPC operation -- checked by the caller via {@link #supports} first. */
  public RpcHandlerOutcome<Object> invoke(String operationWireName, byte[] body, RpcRequestContext context) {
    BiFunction<RpcRequestContext, byte[], RpcHandlerOutcome<Object>> handler = handlersByOperation.get(operationWireName);
    if (handler == null) {
      throw new IllegalArgumentException("unrecognized RPC operation " + operationWireName);
    }
    return handler.apply(context, body);
  }

  /**
   * The cast to {@code RpcHandlerOutcome<Object>} is safe despite being
   * unchecked: {@code Res} is erased at runtime regardless, and every
   * caller of {@link #invoke} only ever reads {@code response()} as an
   * {@code Object} to serialize it back out -- it never needs {@code Res}
   * as a static type. This is what lets a single {@code Map} hold
    * bindings for the different response types without a wildcard
   * flowing into {@link com.estoma.authentication.application.RpcDispatcher#execute}'s
   * generic {@code Supplier} parameter, which resists straightforward
   * wildcard capture at that call site.
   */
  @SuppressWarnings("unchecked")
   private <Req, Res> Map.Entry<String, BiFunction<RpcRequestContext, byte[], RpcHandlerOutcome<Object>>> binding(
       AuthenticationRpcOperation operation, Class<Req> requestType, BiFunction<RpcRequestContext, Req, RpcHandlerOutcome<Res>> handle) {
    BiFunction<RpcRequestContext, byte[], RpcHandlerOutcome<Object>> decodeAndHandle =
        (context, body) -> (RpcHandlerOutcome<Object>) (RpcHandlerOutcome<?>) handle.apply(context, codec.decodeValue(body, requestType));
    return Map.entry(operation.wireName(), decodeAndHandle);
  }
}
