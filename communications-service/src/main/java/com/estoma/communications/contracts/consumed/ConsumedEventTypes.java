package com.estoma.communications.contracts.consumed;

/** Wire names of events this service consumes from other services (events.md §5). */
public final class ConsumedEventTypes {

  public static final String PERSON_CREATED = "PersonCreated";
  public static final String PERSON_UPDATED = "PersonUpdated";
  public static final String PERSON_ACTIVATED = "PersonActivated";
  public static final String PERSON_DEACTIVATED = "PersonDeactivated";

  public static final String ACCOUNT_CREATED = "AccountCreated";
  public static final String ACCOUNT_ACTIVATED = "AccountActivated";
  public static final String ACCOUNT_DEACTIVATED = "AccountDeactivated";
  public static final String LOGIN_IDENTIFIER_CHANGED = "LoginIdentifierChanged";

  public static final String APPOINTMENT_CANCELLED = "AppointmentCancelled";

  private ConsumedEventTypes() {}
}
