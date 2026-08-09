package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.*;
import com.estoma.communications.contracts.*;
import com.estoma.communications.contracts.event.*;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.email.*;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;

/** Atomic result transaction: delivery, attempt, parent aggregate, and outbox share one commit. */
@Service
class EmailDeliveryResultTxService {
  private static final int MAX_CYCLE_ATTEMPTS = 6;
  private static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(24)};
  private final EmailDeliveryRepository deliveries;
  private final EmailDeliveryAttemptRepository attempts;
  private final CommunicationRepository communications;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;
  private final MeterRegistry metrics;
  EmailDeliveryResultTxService(EmailDeliveryRepository d, EmailDeliveryAttemptRepository a, CommunicationRepository c, OutboxPort o, EnvelopeJsonCodec codec, MeterRegistry metrics) {
    deliveries=d; attempts=a; communications=c; outbox=o; this.codec=codec; this.metrics=metrics;
  }
   @Transactional public void record(EmailDelivery claimed, EmailProviderPort.EmailSendResult result) {
     Instant now=Instant.now(); int number=claimed.attemptCount();
     EmailDeliveryAttempt prepared = attempts.findByClaim(claimed.emailDeliveryId(), claimed.claimToken()).orElse(null);
      if (prepared == null || (prepared.status() != EmailAttemptStatus.CALL_STARTED && prepared.status() != EmailAttemptStatus.READY_TO_SEND)) {
        metrics.counter("communications.email.claim_lost").increment();
        throw new LostEmailClaimException();
     }
    if (result instanceof EmailProviderPort.EmailSendResult.Accepted accepted) {
       metrics.counter("communications.email.provider.calls", "outcome", "accepted").increment();
       metrics.counter("communications.email.accepted.total").increment();
        EmailDelivery updated=claimed.accepted(now); if (deliveries.updateClaimed(updated, claimed.claimToken()) != 1) { metrics.counter("communications.email.claim_lost").increment(); throw new LostEmailClaimException(); }
       if (attempts.update(prepared.accepted(accepted.providerMessageId(), now)) != 1) throw new LostEmailClaimException();
       Communication communication=communications.findById(claimed.communicationId()).orElseThrow();
        UUID correlationId = ensureCorrelation(communication);
        communication = communications.findById(communication.communicationId()).orElseThrow();
       outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.EMAIL_DELIVERY_ACCEPTED.wireName(), 1,
          MessageKind.EVENT.name(), claimed.communicationId(), null, correlationId, null,
          codec.writeValueAsString(new EmailDeliveryAcceptedPayload(claimed.emailDeliveryId(), claimed.communicationId(),
              communication.recipientAccountId(), accepted.provider(), claimed.contentMode().name(), accepted.providerMessageId(), number, now)), now));
       if (communication.requestedChannels().isEmpty() || communication.requestedChannels().contains("EMAIL")) completeIfAllChannels(communication, correlationId, now);
      return;
    }
    if (result instanceof EmailProviderPort.EmailSendResult.Indeterminate unknown) {
       metrics.counter("communications.email.provider.calls", "outcome", "indeterminate").increment();
       metrics.counter("communications.email.indeterminate.total").increment();
        if (deliveries.updateClaimed(claimed.indeterminate(unknown.normalizedErrorCode(), unknown.errorMessage(), now), claimed.claimToken()) != 1) { metrics.counter("communications.email.claim_lost").increment(); throw new LostEmailClaimException(); }
        if (attempts.update(prepared.indeterminate(unknown.normalizedErrorCode(), unknown.errorMessage(), now)) != 1) throw new LostEmailClaimException(); return;
    }
    EmailProviderPort.EmailSendResult.Failed failed=(EmailProviderPort.EmailSendResult.Failed)result;
     metrics.counter("communications.email.provider.calls", "outcome", failed.retryable() ? "retryable_failure" : "terminal_failure").increment();
      if (!failed.retryable() || claimed.cycleAttemptCount() >= MAX_CYCLE_ATTEMPTS) {
        metrics.counter("communications.email.failed.total").increment();
        if (deliveries.updateClaimed(claimed.failedTerminal(failed.normalizedErrorCode(), failed.errorMessage(), now), claimed.claimToken()) != 1) { metrics.counter("communications.email.claim_lost").increment(); throw new LostEmailClaimException(); }
        if (attempts.update(prepared.failed(failed.normalizedErrorCode(), failed.errorMessage(), now)) != 1) throw new LostEmailClaimException();
       Communication communication=communications.findById(claimed.communicationId()).orElseThrow();
        UUID correlationId = ensureCorrelation(communication);
        communication = communications.findById(communication.communicationId()).orElseThrow();
       if (communications.update(communication.failed(now)) != 1) throw new LostEmailClaimException();
      outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.EMAIL_DELIVERY_FAILED.wireName(), 1,
            MessageKind.EVENT.name(), claimed.communicationId(), null, correlationId, null,
           codec.writeValueAsString(new EmailDeliveryFailedPayload(claimed.emailDeliveryId(), claimed.communicationId(), communication.recipientAccountId(), number, failed.normalizedErrorCode(), now)), now));
       outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.COMMUNICATION_FAILED.wireName(), 1,
            MessageKind.EVENT.name(), claimed.communicationId(), null, correlationId, null,
           codec.writeValueAsString(new CommunicationFailedPayload(communication.communicationId(), communication.recipientAccountId(), communication.systemCode(), communication.communicationType(), List.of("EMAIL"), failed.normalizedErrorCode(), now)), now));
      } else {
        metrics.counter("communications.email.retry_scheduled.total").increment();
       Instant next = now.plus(BACKOFF[Math.min(claimed.cycleAttemptCount() - 1, BACKOFF.length - 1)]);
        if (deliveries.updateClaimed(claimed.retryScheduled(failed.normalizedErrorCode(), failed.errorMessage(), next, now), claimed.claimToken()) != 1) { metrics.counter("communications.email.claim_lost").increment(); throw new LostEmailClaimException(); }
       if (attempts.update(prepared.failed(failed.normalizedErrorCode(), failed.errorMessage(), now)) != 1) throw new LostEmailClaimException();
       Communication communication=communications.findById(claimed.communicationId()).orElseThrow();
        UUID correlationId = ensureCorrelation(communication);
       outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.EMAIL_DELIVERY_RETRY_SCHEDULED.wireName(), 1,
            MessageKind.EVENT.name(), claimed.communicationId(), null, correlationId, null,
          codec.writeValueAsString(new EmailDeliveryRetryScheduledPayload(claimed.emailDeliveryId(), claimed.communicationId(), number, failed.normalizedErrorCode(), next, now)), now));
    }
  }
  private static final class LostEmailClaimException extends RuntimeException {}
   private void completeIfAllChannels(Communication c, UUID correlationId, Instant now) {
    if (c.status() != CommunicationStatus.PROCESSING) return;
     if (communications.update(c.completed(now)) != 1) throw new LostEmailClaimException();
    outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.COMMUNICATION_COMPLETED.wireName(), 1,
          MessageKind.EVENT.name(), c.communicationId(), null, correlationId, null,
        codec.writeValueAsString(new CommunicationCompletedPayload(c.communicationId(), c.recipientAccountId(), c.systemCode(), c.communicationType(),
             c.requestedChannels(), now)), now));
  }

   private UUID ensureCorrelation(Communication communication) {
     if (communication.correlationId() != null) return communication.correlationId();
     Communication traced = communication.withCorrelationId(UUID.randomUUID());
     if (communications.update(traced) != 1) throw new LostEmailClaimException();
     return traced.correlationId();
   }

}
