package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryRequest;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryResponse;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Returns a recovery code only while its request remains usable. */
@Component
public class GetRecoveryCodeForDeliveryHandler {

  private final PasswordRecoveryRepository repository;
  private final RecoveryCodeDeriver codeDeriver;

  GetRecoveryCodeForDeliveryHandler(PasswordRecoveryRepository repository, RecoveryCodeDeriver codeDeriver) {
    this.repository = repository;
    this.codeDeriver = codeDeriver;
  }

  public RpcHandlerOutcome<GetRecoveryCodeForDeliveryResponse> handle(GetRecoveryCodeForDeliveryRequest request) {
    var recovery =
        repository
            .findByIdForUpdate(request.recoveryId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.RECOVERY_TOKEN_INVALID, "recovery request does not exist"));
    Instant now = Instant.now();
    if (recovery.isExpired(now)) {
      repository.expireIfActive(recovery.recoveryId(), now);
    }
    if (!recovery.isActiveAndUsable(now)) {
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_TOKEN_INVALID, "recovery request is no longer usable");
    }
    return RpcHandlerOutcome.withoutEvents(new GetRecoveryCodeForDeliveryResponse(codeDeriver.derive(recovery.recoveryId())));
  }
}
