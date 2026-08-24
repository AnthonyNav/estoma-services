package com.estoma.communications.contracts.consumed;

/** Wire names of events this service consumes from canonical owner services. */
public final class ConsumedEventTypes {

  public static final String PERSON_CREATED = "PersonCreated";
  public static final String PERSON_UPDATED = "PersonUpdated";
  public static final String PERSON_ACTIVATED = "PersonActivated";
  public static final String PERSON_DEACTIVATED = "PersonDeactivated";

  public static final String ACCOUNT_CREATED = "AccountCreated";
  public static final String ACCOUNT_ACTIVATED = "AccountActivated";
  public static final String ACCOUNT_DEACTIVATED = "AccountDeactivated";
  public static final String PASSWORD_RECOVERY_REQUESTED = "PasswordRecoveryRequested";

  public static final String APPOINTMENT_CANCELLED = "AppointmentCancelled";

  private ConsumedEventTypes() {}
}
