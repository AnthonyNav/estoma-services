package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.rpc.AuthenticateAccountHandler;
import com.estoma.authentication.application.rpc.ChangePasswordHandler;
import com.estoma.authentication.application.rpc.GetRecoveryCodeForDeliveryHandler;
import com.estoma.authentication.application.rpc.RefreshSessionHandler;
import com.estoma.authentication.application.rpc.RegenerateTemporaryPasswordHandler;
import com.estoma.authentication.application.rpc.ResetPasswordHandler;
import com.estoma.authentication.application.rpc.RevokeSessionHandler;
import com.estoma.authentication.application.rpc.SetTemporaryPasswordHandler;
import com.estoma.authentication.application.rpc.ValidateRecoveryCodeHandler;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountRequest;
import com.estoma.authentication.contracts.rpc.ChangePasswordRequest;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryRequest;
import com.estoma.authentication.contracts.rpc.RefreshSessionRequest;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.contracts.rpc.RevokeSessionRequest;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeRequest;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import org.springframework.stereotype.Component;

/** Explicit transport-to-application routing for the closed V1 Security RPC catalog. */
@Component
public class RpcOperationRouter {
  private final EnvelopeJsonCodec codec;
  private final RpcDispatcher dispatcher;
  private final AuthenticateAccountHandler authenticate;
  private final RefreshSessionHandler refresh;
  private final RevokeSessionHandler revoke;
  private final ValidateRecoveryCodeHandler validateRecovery;
  private final ResetPasswordHandler resetPassword;
  private final ChangePasswordHandler changePassword;
  private final SetTemporaryPasswordHandler setTemporaryPassword;
  private final RegenerateTemporaryPasswordHandler regenerateTemporaryPassword;
  private final GetRecoveryCodeForDeliveryHandler getRecoveryCode;

  public RpcOperationRouter(
      EnvelopeJsonCodec codec,
      RpcDispatcher dispatcher,
      AuthenticateAccountHandler authenticate,
      RefreshSessionHandler refresh,
      RevokeSessionHandler revoke,
      ValidateRecoveryCodeHandler validateRecovery,
      ResetPasswordHandler resetPassword,
      ChangePasswordHandler changePassword,
      SetTemporaryPasswordHandler setTemporaryPassword,
      RegenerateTemporaryPasswordHandler regenerateTemporaryPassword,
      GetRecoveryCodeForDeliveryHandler getRecoveryCode) {
    this.codec = codec;
    this.dispatcher = dispatcher;
    this.authenticate = authenticate;
    this.refresh = refresh;
    this.revoke = revoke;
    this.validateRecovery = validateRecovery;
    this.resetPassword = resetPassword;
    this.changePassword = changePassword;
    this.setTemporaryPassword = setTemporaryPassword;
    this.regenerateTemporaryPassword = regenerateTemporaryPassword;
    this.getRecoveryCode = getRecoveryCode;
  }

  public RpcOutcome<?> route(MessageEnvelope envelope) {
    RpcRequestContext context = RpcRequestContext.from(envelope);
    String operation = envelope.messageType();
    if (AuthenticationRpcOperation.AUTHENTICATE_ACCOUNT.wireName().equals(operation)) {
      var request = codec.convertPayload(envelope, AuthenticateAccountRequest.class);
      return dispatcher.execute(context, () -> authenticate.handle(request));
    }
    if (AuthenticationRpcOperation.REFRESH_SESSION.wireName().equals(operation)) {
      var request = codec.convertPayload(envelope, RefreshSessionRequest.class);
      return dispatcher.execute(context, () -> refresh.handle(context, request));
    }
    if (AuthenticationRpcOperation.REVOKE_SESSION.wireName().equals(operation)) {
      var request = codec.convertPayload(envelope, RevokeSessionRequest.class);
      return dispatcher.execute(context, () -> revoke.handle(context, request));
    }
    if (AuthenticationRpcOperation.VALIDATE_RECOVERY_CODE.wireName().equals(operation)) {
      var request = codec.convertPayload(envelope, ValidateRecoveryCodeRequest.class);
      return dispatcher.execute(context, () -> validateRecovery.handle(request));
    }
    if (AuthenticationRpcOperation.RESET_PASSWORD.wireName().equals(operation)) {
      var request = codec.convertPayload(envelope, ResetPasswordRequest.class);
      return dispatcher.execute(context, () -> resetPassword.handle(request));
    }
    if (AuthenticationRpcOperation.CHANGE_PASSWORD.wireName().equals(operation)) {
      var request = codec.convertPayload(envelope, ChangePasswordRequest.class);
      return dispatcher.execute(context, () -> changePassword.handle(context, request));
    }
    if (AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD.wireName().equals(operation)) {
      var request = codec.convertPayload(envelope, SetTemporaryPasswordRequest.class);
      return dispatcher.execute(context, () -> setTemporaryPassword.handle(context, request));
    }
    if (AuthenticationRpcOperation.REGENERATE_TEMPORARY_PASSWORD.wireName().equals(operation)) {
      var request = codec.convertPayload(envelope, RegenerateTemporaryPasswordRequest.class);
      return dispatcher.execute(context, () -> regenerateTemporaryPassword.handle(context, request));
    }
    if (AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY.wireName().equals(operation)) {
      var request = codec.convertPayload(envelope, GetRecoveryCodeForDeliveryRequest.class);
      return dispatcher.execute(context, () -> getRecoveryCode.handle(context, request));
    }
    throw new InvalidMessageException("unsupported RPC operation");
  }
}
