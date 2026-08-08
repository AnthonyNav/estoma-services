package com.estoma.authentication.contracts.consumed;

/** Wire {@code messageType} names for events owned by other services that this service consumes. */
public final class ConsumedEventTypes {

  public static final String PERSON_CREATED = "PersonCreated";
  public static final String PERSON_ACTIVATED = "PersonActivated";
  public static final String PERSON_DEACTIVATED = "PersonDeactivated";
  public static final String STUDENT_NUMBER_CORRECTED = "StudentNumberCorrected";

  private ConsumedEventTypes() {}
}
