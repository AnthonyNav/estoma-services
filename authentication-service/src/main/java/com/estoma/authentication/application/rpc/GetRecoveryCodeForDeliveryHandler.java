package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryRequest;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryResponse;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Returns a recovery code only while both the request and its owner remain eligible for delivery. */
@Component
public class GetRecoveryCodeForDeliveryHandler {

  private final PasswordRecoveryRepository repository;
  private final RecoveryCodeDeriver codeDeriver;
  private final AccountRepository accountRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;

  GetRecoveryCodeForDeliveryHandler(
      PasswordRecoveryRepository repository,
      RecoveryCodeDeriver codeDeriver,
      AccountRepository accountRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository) {
    this.repository = repository;
    this.codeDeriver = codeDeriver;
    this.accountRepository = accountRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
  }

  public RpcHandlerOutcome<GetRecoveryCodeForDeliveryResponse> handle(GetRecoveryCodeForDeliveryRequest request) {
    var recovery =
        repository
            .findByIdForUpdate(request.recoveryId())
            .orElseThrow(() -> new AuthenticationRejectedException(
                RejectionCode.RECOVERY_CODE_INVALID, "recovery request is not available for delivery"));
    Instant now = Instant.now();
    if (!recovery.hasCanonicalCodeDerivationContext()) {
      throw unavailable();
    }
    if (recovery.isExpired(now)) {
      repository.expireIfActive(recovery.recoveryId(), now);
      throw new AuthenticationRejectedException(
          RejectionCode.RECOVERY_CODE_EXPIRED, "recovery request is not available for delivery");
    }
    if (!recovery.isActiveAndUsable(now)) {
      RejectionCode code = recovery.status() == com.estoma.authentication.domain.recovery.PasswordRecoveryRequest.Status.ATTEMPTS_EXHAUSTED
          ? RejectionCode.RECOVERY_ATTEMPTS_EXHAUSTED
          : RejectionCode.RECOVERY_CODE_INVALID;
      throw new AuthenticationRejectedException(code, "recovery request is not available for delivery");
    }

    var account = accountRepository.findById(recovery.accountId()).orElseThrow(GetRecoveryCodeForDeliveryHandler::unavailable);
    if (!account.isActive()) {
      throw unavailable();
    }
    var person = personStatusProjectionRepository.findByPersonId(account.personId()).orElseThrow(GetRecoveryCodeForDeliveryHandler::unavailable);
    if (!"ACTIVE".equals(person.status())) {
      throw unavailable();
    }

    return RpcHandlerOutcome.withoutEvents(new GetRecoveryCodeForDeliveryResponse(
        codeDeriver.derive(recovery.recoveryId(), recovery.codeDerivationContext())));
  }

  public RpcHandlerOutcome<GetRecoveryCodeForDeliveryResponse> handle(
      RpcRequestContext context, GetRecoveryCodeForDeliveryRequest request) {
    return handle(request);
  }

  private static AuthenticationRejectedException unavailable() {
    return new AuthenticationRejectedException(
        RejectionCode.RECOVERY_CODE_INVALID, "recovery request is not available for delivery");
  }
}
