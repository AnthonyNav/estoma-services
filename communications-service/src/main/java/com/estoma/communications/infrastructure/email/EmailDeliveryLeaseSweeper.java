package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailDeliveryRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;

/** Reopens only expired provider leases; a live lease is never reclaimed. */
@Component
class EmailDeliveryLeaseSweeper {
  private final EmailDeliveryRepository repository;
  private final MeterRegistry metrics;
  private final boolean scheduledEnabled;
  EmailDeliveryLeaseSweeper(EmailDeliveryRepository repository, MeterRegistry metrics,
      @org.springframework.beans.factory.annotation.Value("${estoma.communications.email.worker.enabled:true}") boolean scheduledEnabled) {
    this.repository = repository;
    this.metrics = metrics;
    this.scheduledEnabled = scheduledEnabled;
  }
  @Scheduled(fixedDelayString = "${estoma.communications.email.worker.sweeper-interval-ms:5000}")
  @Transactional
  public void sweep() {
    if (!scheduledEnabled) return;
    int recovered = repository.releaseExpiredLeases();
    if (recovered > 0) metrics.counter("communications.email.lease_recovered.total").increment(recovered);
  }
}
