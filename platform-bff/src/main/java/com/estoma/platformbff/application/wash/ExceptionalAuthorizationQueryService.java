package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.port.ExceptionalAuthorizationQueryPort;
import com.estoma.platformbff.application.port.ExceptionalAuthorizationQueryPort.Authorization;
import com.estoma.platformbff.application.port.ExceptionalAuthorizationQueryPort.Student;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ExceptionalAuthorizationQueryService {
  public record Item(UUID authorizationId, String status, String reason, boolean canCancel) {}
  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final ExceptionalAuthorizationQueryPort queries;
  private final Clock clock;
  public ExceptionalAuthorizationQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, ExceptionalAuthorizationQueryPort queries, Clock clock) {
    this.authorization = authorization; this.readiness = readiness; this.queries = queries; this.clock = clock;
  }
  public List<Student> search(String query) {
    authorization.requireWashSupervisor();
    readiness.requireReady("person", "academic");
    if (query == null || query.strip().length() < 2 || query.length() > 100)
      throw new IllegalArgumentException("search must contain between 2 and 100 characters");
    return queries.search(query.strip());
  }
  public List<Item> list(UUID student, LocalDate date) {
    authorization.requireWashSupervisor();
    readiness.requireReady("appointments");
    return queries.list(student, date).stream().map(a -> {
      String status = status(a, date);
      return new Item(a.authorizationId(), status, a.reason(), status.equals("AVAILABLE"));
    }).toList();
  }
  private String status(Authorization a, LocalDate date) {
    if (a.status().equals("CANCELLED")) return "CANCELLED";
    if (a.superseded()) return "SUPERSEDED";
    if (a.consumed()) return "CONSUMED";
    if (date.isBefore(clock.instant().atZone(ZoneId.of("America/Mexico_City")).toLocalDate())
        || a.expiresAt() != null && clock.instant().isAfter(a.expiresAt())) return "EXPIRED";
    // Old events did not carry legacy expiry or supersession; never advertise them as usable.
    if (!a.policyContextKnown()) return "UNKNOWN";
    return "AVAILABLE";
  }
}
