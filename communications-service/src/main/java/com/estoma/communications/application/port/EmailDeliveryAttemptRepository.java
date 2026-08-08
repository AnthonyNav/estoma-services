package com.estoma.communications.application.port;

import com.estoma.communications.domain.email.EmailDeliveryAttempt;

public interface EmailDeliveryAttemptRepository {

  void insert(EmailDeliveryAttempt attempt);
}
