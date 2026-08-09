package com.estoma.authentication.application.projection;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.StudentNumberSyncRepository;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.consumed.ConsumedEventTypes;
import com.estoma.authentication.contracts.consumed.StudentNumberCorrectedPayload;
import com.estoma.authentication.contracts.event.StudentLoginIdentifierSynchronizationFailedPayload;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code StudentNumberCorrected} to synchronize a MATRICULA
 * account's {@code loginIdentifier} (events.md §9, data-model.md §9.1).
 * Order is protected by {@code studentNumberVersion} against {@code
 * student_number_login_sync_projection.last_applied_student_number_version},
 * not by {@code aggregateVersion} or Consumer Inbox alone (those still
 * protect physical message delivery; this protects the functional
 * application order). Never calls {@code academic-service} directly to
 * resolve a conflict.
 */
@Component
class StudentNumberCorrectedHandler implements ProjectionHandler {

  private final AccountRepository accountRepository;
  private final StudentNumberSyncRepository syncRepository;
  private final EnvelopeJsonCodec codec;

  StudentNumberCorrectedHandler(AccountRepository accountRepository, StudentNumberSyncRepository syncRepository, EnvelopeJsonCodec codec) {
    this.accountRepository = accountRepository;
    this.syncRepository = syncRepository;
    this.codec = codec;
  }

  @Override
  public List<String> consumedMessageTypes() {
    return List.of(ConsumedEventTypes.STUDENT_NUMBER_CORRECTED);
  }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    StudentNumberCorrectedPayload payload = codec.convertPayload(envelope, StudentNumberCorrectedPayload.class);
    Instant now = Instant.now();

    syncRepository.ensureExists(payload.accountId(), payload.studentAcademicProfileId(), now);
    var sync = syncRepository.findByAccountId(payload.accountId()).orElseThrow(() -> new IllegalStateException("sync row must exist after ensureExists"));

    if (payload.studentNumberVersion() <= sync.lastAppliedStudentNumberVersion()) {
      return ProjectionHandlerResult.NONE; // stale or already-applied redelivery: ignored, per data-model.md §9.1
    }
    var accountOpt = accountRepository.findById(payload.accountId());
    if (accountOpt.isEmpty()) {
      return fail(envelope, payload, RejectionCode.ACCOUNT_NOT_FOUND.name(), now);
    }
    Account account = accountOpt.get();
    if (account.loginIdentifier().type() != LoginType.MATRICULA) {
      return fail(envelope, payload, RejectionCode.LOGIN_TYPE_NOT_SUPPORTED.name(), now);
    }
    LoginIdentifier newIdentifier = LoginIdentifier.of(LoginType.MATRICULA, payload.newStudentNumber());
    var collision = accountRepository.findByLoginIdentifier(LoginType.MATRICULA, newIdentifier.normalized());
    if (collision.isPresent() && !collision.get().accountId().equals(account.accountId())) {
      return fail(envelope, payload, RejectionCode.LOGIN_IDENTIFIER_ALREADY_USED.name(), now);
    }

    Account updated = account.withLoginIdentifierChanged(newIdentifier, now);
    int rowsAffected = accountRepository.update(updated, account.version());
    if (rowsAffected == 0) {
      // A concurrent modification raced this application; not one of the documented conflict
      // codes (data-model.md §9.1 lists functional conflicts, not concurrent-write races). Let
      // it surface as a technical failure so redelivery retries rather than silently mis-recording.
      throw new IllegalStateException("account " + account.accountId() + " changed concurrently during student number synchronization");
    }

    syncRepository.markSynchronized(account.accountId(), payload.studentNumberVersion(), now);
    var event = new com.estoma.authentication.contracts.event.LoginIdentifierChangedPayload(updated.accountId(), LoginType.MATRICULA, "STUDENT_NUMBER_CORRECTED", now);
    return ProjectionHandlerResult.of(new EventToAppend(AuthenticationEvent.LOGIN_IDENTIFIER_CHANGED.wireName(), event, updated.accountId(), updated.version()));
  }

  private ProjectionHandlerResult fail(MessageEnvelope envelope, StudentNumberCorrectedPayload payload, String errorCode, Instant now) {
    syncRepository.markFailed(payload.accountId(), errorCode, now);
    var event =
        new StudentLoginIdentifierSynchronizationFailedPayload(
            payload.accountId(), payload.studentAcademicProfileId(), payload.studentNumberVersion(), errorCode, now, envelope.correlationId(), envelope.causationId());
    return ProjectionHandlerResult.of(new EventToAppend(AuthenticationEvent.STUDENT_LOGIN_IDENTIFIER_SYNCHRONIZATION_FAILED.wireName(), event, payload.accountId(), null));
  }
}
