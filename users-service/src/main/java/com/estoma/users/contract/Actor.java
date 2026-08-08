package com.estoma.users.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.UUID;

/**
 * The ActorContext embedded in the envelope's {@code actor} field
 * (messaging-architecture.md §8.1). Two variants, discriminated by
 * {@code type} on the wire — a sealed interface rather than one record
 * with a nullable {@code accountId}, so a technical actor can never carry
 * a fabricated account identity:
 *
 * <ul>
 *   <li>{@link Account} — a human actor: {@code {"type":"ACCOUNT","accountId":"..."}}.
 *   <li>{@link System} — a technical/service actor: {@code
 *       {"type":"SYSTEM","service":"academic"}}. {@code service} uses the
 *       same {@code logicalComponentId} namespace as {@code producer} and
 *       routing keys, but is not required to equal this message's own
 *       {@code producer} (messaging-architecture.md §4).
 * </ul>
 *
 * <p>Never synthesizes an {@code accountId} for a {@link System} actor —
 * {@link #accountId()} returns {@code null} for it, truthfully reporting
 * "no account", not inventing one.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Actor.Account.class, name = "ACCOUNT"),
  @JsonSubTypes.Type(value = Actor.System.class, name = "SYSTEM")
})
public sealed interface Actor {

  /** A human actor, identified by an {@code authentication-service} Account id. */
  record Account(UUID accountId) implements Actor {}

  /** A technical/service actor acting on its own behalf, not a human's. */
  record System(String service) implements Actor {}

  /**
   * Convenience accessor for call sites that only care about the human
   * actor's account id (e.g. {@code person_status_history.changed_by_account_id}).
   * Returns {@code null} for a {@link System} actor — there is no account
   * to report, never a fabricated one.
   */
  default UUID accountId() {
    return this instanceof Account account ? account.accountId() : null;
  }
}
