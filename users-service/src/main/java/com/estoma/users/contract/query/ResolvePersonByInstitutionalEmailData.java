package com.estoma.users.contract.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * The {@code data} of a {@code ResolvePersonByInstitutionalEmailResult}
 * (queries.md §2). {@code personId}/{@code status} are omitted from the
 * wire entirely for {@link ResolveOutcome#NOT_FOUND} — not published as
 * {@code null} — since queries.md is explicit that the Query "no expone
 * más datos de los estrictamente necesarios".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolvePersonByInstitutionalEmailData(ResolveOutcome outcome, UUID personId, String status) {

  public static ResolvePersonByInstitutionalEmailData found(UUID personId, String status) {
    return new ResolvePersonByInstitutionalEmailData(ResolveOutcome.FOUND, personId, status);
  }

  public static ResolvePersonByInstitutionalEmailData inactiveMatch(UUID personId, String status) {
    return new ResolvePersonByInstitutionalEmailData(ResolveOutcome.INACTIVE_MATCH, personId, status);
  }

  public static ResolvePersonByInstitutionalEmailData notFound() {
    return new ResolvePersonByInstitutionalEmailData(ResolveOutcome.NOT_FOUND, null, null);
  }
}
