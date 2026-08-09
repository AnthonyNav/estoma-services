package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.domain.email.EmailDelivery;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

/** Transaction boundary for claims. Kept separate so the scheduled bean never self-invokes a transaction. */
@Service
class ClaimEmailDeliveriesTxService {
  private final EmailDeliveryRepository repository;
  private final String workerId;
  ClaimEmailDeliveriesTxService(EmailDeliveryRepository repository,
      @Value("${estoma.communications.email.worker.id:communications-worker}") String workerId) {
    this.repository = repository;
    this.workerId = workerId;
  }
  @Transactional public List<EmailDelivery> claim(int batchSize) {
    return repository.claimDue(batchSize, workerId);
  }
}
