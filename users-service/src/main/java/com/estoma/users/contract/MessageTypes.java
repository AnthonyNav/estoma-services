package com.estoma.users.contract;

import java.util.Set;

/** Exhaustive V1 wire message types owned/consumed by users-service. */
public final class MessageTypes {

  private MessageTypes() {}

  public static final String CREATE_PERSON = "CreatePerson";
  public static final String UPDATE_PERSON = "UpdatePerson";
  public static final String ACTIVATE_PERSON = "ActivatePerson";
  public static final String DEACTIVATE_PERSON = "DeactivatePerson";

  // Existing BFF-facing result vocabulary.
  public static final String CREATE_PERSON_SUCCEEDED = "CreatePersonSucceeded";
  public static final String CREATE_PERSON_REJECTED = "CreatePersonRejected";
  public static final String UPDATE_PERSON_SUCCEEDED = "UpdatePersonSucceeded";
  public static final String UPDATE_PERSON_REJECTED = "UpdatePersonRejected";
  public static final String ACTIVATE_PERSON_SUCCEEDED = "ActivatePersonSucceeded";
  public static final String ACTIVATE_PERSON_REJECTED = "ActivatePersonRejected";
  public static final String DEACTIVATE_PERSON_SUCCEEDED = "DeactivatePersonSucceeded";
  public static final String DEACTIVATE_PERSON_REJECTED = "DeactivatePersonRejected";

  // Canonical coordinated Academic result vocabulary.
  public static final String CREATE_PERSON_RESULT = "CreatePersonResult";
  public static final String UPDATE_PERSON_RESULT = "UpdatePersonResult";

  public static final String PERSON_CREATED = "PersonCreated";
  public static final String PERSON_UPDATED = "PersonUpdated";
  public static final String PERSON_ACTIVATED = "PersonActivated";
  public static final String PERSON_DEACTIVATED = "PersonDeactivated";

  public static final String RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL = "ResolvePersonByInstitutionalEmail";
  public static final String GET_PERSON_STATUS_SNAPSHOT = "GetPersonStatusSnapshot";
  public static final String LIST_PERSON_STATUS_SNAPSHOTS_PAGE = "ListPersonStatusSnapshotsPage";
  public static final String LIST_PERSON_PRESENTATION_SNAPSHOTS_PAGE = "ListPersonPresentationSnapshotsPage";

  public static final String RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL_RESULT = "ResolvePersonByInstitutionalEmailResult";
  public static final String GET_PERSON_STATUS_SNAPSHOT_RESULT = "GetPersonStatusSnapshotResult";
  public static final String LIST_PERSON_STATUS_SNAPSHOTS_PAGE_RESULT = "ListPersonStatusSnapshotsPageResult";
  public static final String LIST_PERSON_PRESENTATION_SNAPSHOTS_PAGE_RESULT = "ListPersonPresentationSnapshotsPageResult";

  public static final Set<String> COMMANDS = Set.of(CREATE_PERSON, UPDATE_PERSON, ACTIVATE_PERSON, DEACTIVATE_PERSON);
  public static final Set<String> RESULTS = Set.of(
      CREATE_PERSON_SUCCEEDED, CREATE_PERSON_REJECTED,
      UPDATE_PERSON_SUCCEEDED, UPDATE_PERSON_REJECTED,
      ACTIVATE_PERSON_SUCCEEDED, ACTIVATE_PERSON_REJECTED,
      DEACTIVATE_PERSON_SUCCEEDED, DEACTIVATE_PERSON_REJECTED,
      CREATE_PERSON_RESULT, UPDATE_PERSON_RESULT);
  public static final Set<String> EVENTS = Set.of(PERSON_CREATED, PERSON_UPDATED, PERSON_ACTIVATED, PERSON_DEACTIVATED);
  public static final Set<String> QUERIES = Set.of(
      RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL,
      GET_PERSON_STATUS_SNAPSHOT,
      LIST_PERSON_STATUS_SNAPSHOTS_PAGE,
      LIST_PERSON_PRESENTATION_SNAPSHOTS_PAGE);
  public static final Set<String> QUERY_RESULTS = Set.of(
      RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL_RESULT,
      GET_PERSON_STATUS_SNAPSHOT_RESULT,
      LIST_PERSON_STATUS_SNAPSHOTS_PAGE_RESULT,
      LIST_PERSON_PRESENTATION_SNAPSHOTS_PAGE_RESULT);

  public static final Set<String> ALL;
  static {
    ALL = Set.copyOf(java.util.stream.Stream.of(COMMANDS, RESULTS, EVENTS, QUERIES, QUERY_RESULTS)
        .flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()));
  }
}
