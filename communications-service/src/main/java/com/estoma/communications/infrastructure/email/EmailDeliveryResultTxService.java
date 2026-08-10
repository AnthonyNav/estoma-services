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
      Instant now=Instant.now();
      UUID claimToken = claimed.claimToken();
      EmailDelivery locked = deliveries.findByIdForUpdate(claimed.emailDeliveryId()).orElseThrow(LostEmailClaimException::new);
      if (claimToken == null || locked.status() != EmailDeliveryStatus.SENDING || !claimToken.equals(locked.claimToken())
          || locked.leaseUntil() == null || !locked.leaseUntil().isAfter(now)) {
        metrics.counter("communications.email.claim_lost").increment();
        throw new LostEmailClaimException();
      }
      int number=locked.attemptCount();
      EmailDeliveryAttempt prepared = attempts.findByClaimForUpdate(locked.emailDeliveryId(), claimToken).orElse(null);
       if (prepared == null || prepared.status() != EmailAttemptStatus.CALL_STARTED) {
         metrics.counter("communications.email.claim_lost").increment();
         throw new LostEmailClaimException();
       }
    if (result instanceof EmailProviderPort.EmailSendResult.Accepted accepted) {
       metrics.counter("communications.email.provider.calls", "outcome", "accepted").increment();
       metrics.counter("communications.email.accepted.total").increment();
         EmailDelivery updated=locked.accepted(now); if (deliveries.updateClaimed(updated, claimToken) != 1) { metrics.counter("communications.email.claim_lost").increment(); throw new LostEmailClaimException(); }
        if (attempts.completeCallStarted(prepared.accepted(accepted.providerMessageId(), now), claimToken) != 1) throw new LostEmailClaimException();
         Communication communication=communications.findByIdForUpdate(locked.communicationId()).orElseThrow();
        UUID correlationId = ensureCorrelation(communication);
         communication = communications.findByIdForUpdate(communication.communicationId()).orElseThrow();
       outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.EMAIL_DELIVERY_ACCEPTED.wireName(), 1,
           MessageKind.EVENT.name(), locked.communicationId(), null, correlationId, null,
           codec.writeValueAsString(new EmailDeliveryAcceptedPayload(locked.emailDeliveryId(), locked.communicationId(),
               communication.recipientAccountId(), accepted.provider(), locked.contentMode().name(), accepted.providerMessageId(), number, now)), now));
       if (communication.requestedChannels().isEmpty() || communication.requestedChannels().contains("EMAIL")) completeIfAllChannels(communication, correlationId, now);
      return;
    }
    if (result instanceof EmailProviderPort.EmailSendResult.Indeterminate unknown) {
       metrics.counter("communications.email.provider.calls", "outcome", "indeterminate").increment();
       metrics.counter("communications.email.indeterminate.total").increment();
         if (deliveries.updateClaimed(locked.indeterminate(unknown.normalizedErrorCode(), unknown.errorMessage(), now), claimToken) != 1) { metrics.counter("communications.email.claim_lost").increment(); throw new LostEmailClaimException(); }
         if (attempts.completeCallStarted(prepared.indeterminate(unknown.normalizedErrorCode(), unknown.errorMessage(), now), claimToken) != 1) throw new LostEmailClaimException(); return;
    }
    EmailProviderPort.EmailSendResult.Failed failed=(EmailProviderPort.EmailSendResult.Failed)result;
     metrics.counter("communications.email.provider.calls", "outcome", failed.retryable() ? "retryable_failure" : "terminal_failure").increment();
       if (!failed.retryable() || locked.cycleAttemptCount() >= MAX_CYCLE_ATTEMPTS) {
        metrics.counter("communications.email.failed.total").increment();
         if (deliveries.updateClaimed(locked.failedTerminal(failed.normalizedErrorCode(), failed.errorMessage(), now), claimToken) != 1) { metrics.counter("communications.email.claim_lost").increment(); throw new LostEmailClaimException(); }
         if (attempts.completeCallStarted(prepared.failed(failed.normalizedErrorCode(), failed.errorMessage(), now), claimToken) != 1) throw new LostEmailClaimException();
        Communication communication=communications.findByIdForUpdate(locked.communicationId()).orElseThrow();
        UUID correlationId = ensureCorrelation(communication);
         communication = communications.findByIdForUpdate(communication.communicationId()).orElseThrow();
       if (communications.update(communication.failed(now)) != 1) throw new LostEmailClaimException();
      outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.EMAIL_DELIVERY_FAILED.wireName(), 1,
             MessageKind.EVENT.name(), locked.communicationId(), null, correlationId, null,
            codec.writeValueAsString(new EmailDeliveryFailedPayload(locked.emailDeliveryId(), locked.communicationId(), communication.recipientAccountId(), number, failed.normalizedErrorCode(), now)), now));
       outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.COMMUNICATION_FAILED.wireName(), 1,
             MessageKind.EVENT.name(), locked.communicationId(), null, correlationId, null,
           codec.writeValueAsString(new CommunicationFailedPayload(communication.communicationId(), communication.recipientAccountId(), communication.systemCode(), communication.communicationType(), List.of("EMAIL"), failed.normalizedErrorCode(), now)), now));
      } else {
        metrics.counter("communications.email.retry_scheduled.total").increment();
        Instant next = now.plus(BACKOFF[Math.min(locked.cycleAttemptCount() - 1, BACKOFF.length - 1)]);
         if (deliveries.updateClaimed(locked.retryScheduled(failed.normalizedErrorCode(), failed.errorMessage(), next, now), claimToken) != 1) { metrics.counter("communications.email.claim_lost").increment(); throw new LostEmailClaimException(); }
        if (attempts.completeCallStarted(prepared.failed(failed.normalizedErrorCode(), failed.errorMessage(), now), claimToken) != 1) throw new LostEmailClaimException();
         Communication communication=communications.findByIdForUpdate(locked.communicationId()).orElseThrow();
        UUID correlationId = ensureCorrelation(communication);
       outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.EMAIL_DELIVERY_RETRY_SCHEDULED.wireName(), 1,
             MessageKind.EVENT.name(), locked.communicationId(), null, correlationId, null,
           codec.writeValueAsString(new EmailDeliveryRetryScheduledPayload(locked.emailDeliveryId(), locked.communicationId(), number, failed.normalizedErrorCode(), next, now)), now));
    }
  }
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
