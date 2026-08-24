package com.estoma.communications.contracts.consumed;

import java.util.UUID;

/**
 * Consumer-side union of the fields Communications needs from Users Person events.
 * Created/Updated carry identity/contact fields; Activated/Deactivated carry only status.
 * Event ordering is always taken from the envelope aggregateVersion.
 */
public record PersonProjectionEventPayload(
    UUID personId,
    String firstName,
    String paternalSurname,
    String maternalSurname,
    String institutionalEmail,
    String status) {

  public String displayName() {
    return java.util.stream.Stream.of(firstName, paternalSurname, maternalSurname)
        .filter(value -> value != null && !value.isBlank())
        .collect(java.util.stream.Collectors.joining(" "));
  }
}
