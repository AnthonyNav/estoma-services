package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ExceptionalAuthorizationQueryPort {
  record Student(UUID accountId, String enrollment, String fullName) {}
  record Authorization(UUID authorizationId, String status, String reason, Instant expiresAt,
      boolean policyContextKnown, boolean superseded, boolean consumed) {}
  List<Student> search(String query);
  List<Authorization> list(UUID studentAccountId, LocalDate date);
}
