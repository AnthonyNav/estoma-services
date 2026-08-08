package com.estoma.authentication.domain.account;

import com.estoma.authentication.contracts.LoginType;
import java.util.Locale;

/**
 * A login identifier: its type and its normalized form. {@code loginType +
 * normalized} is unique across every account (system_contract.md "Mandatory
 * Invariants"; {@code uq_accounts_login}) -- enforced by the repository
 * translating a unique-constraint violation into {@link
 * com.estoma.authentication.domain.RejectionCode#LOGIN_IDENTIFIER_ALREADY_USED},
 * the same lost-race pattern as {@code JdbcPersonRepository}.
 *
 * <p>Normalization is trim + lowercase. The platform docs do not specify a
 * more exact algorithm (system_contract.md notes "unicidad exacta de
 * identificadores" as a documented gap) -- this is the smallest reasonable
 * choice and easy to change later without touching callers, since they only
 * ever see {@link #normalized()}, never a normalization algorithm of their
 * own.
 *
 * <p>Blank/null inputs throw {@link IllegalArgumentException}, not a
 * functional rejection: commands.md's rejection catalog has no generic
 * "malformed data" code for this service (unlike {@code users-service}'s
 * {@code INVALID_PERSON_DATA}), so structural validation is a technical
 * precondition, checked before any message reaches this constructor once
 * the messaging shell's envelope validation exists.
 */
public record LoginIdentifier(LoginType type, String value, String normalized) {

  public static LoginIdentifier of(LoginType type, String rawValue) {
    if (type == null) {
      throw new IllegalArgumentException("loginType must not be null");
    }
    if (rawValue == null || rawValue.isBlank()) {
      throw new IllegalArgumentException("loginIdentifier must not be blank");
    }
    String trimmed = rawValue.trim();
    return new LoginIdentifier(type, trimmed, trimmed.toLowerCase(Locale.ROOT));
  }
}
