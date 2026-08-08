package com.estoma.users.contract;

import java.util.Set;

/**
 * Every {@code messageType} value this service's contract defines: 4
 * commands + 8 results + 4 events + 1 query + 1 query result = 18. Kept
 * as one exhaustive list so {@code RoutingKeyResolverTest} (messaging.
 * outbound) can assert every constant has a routing key mapping — the
 * guard against adding a message type and forgetting its key.
 */
public final class MessageTypes {

  private MessageTypes() {}

  // Commands — received on commands.users-service
  public static final String CREATE_PERSON = "CreatePerson";
  public static final String UPDATE_PERSON = "UpdatePerson";
  public static final String ACTIVATE_PERSON = "ActivatePerson";
  public static final String DEACTIVATE_PERSON = "DeactivatePerson";

  // Results — published to platform.results
  public static final String CREATE_PERSON_SUCCEEDED = "CreatePersonSucceeded";
  public static final String CREATE_PERSON_REJECTED = "CreatePersonRejected";
  public static final String UPDATE_PERSON_SUCCEEDED = "UpdatePersonSucceeded";
  public static final String UPDATE_PERSON_REJECTED = "UpdatePersonRejected";
  public static final String ACTIVATE_PERSON_SUCCEEDED = "ActivatePersonSucceeded";
  public static final String ACTIVATE_PERSON_REJECTED = "ActivatePersonRejected";
  public static final String DEACTIVATE_PERSON_SUCCEEDED = "DeactivatePersonSucceeded";
  public static final String DEACTIVATE_PERSON_REJECTED = "DeactivatePersonRejected";

  // Events — published to platform.events
  public static final String PERSON_CREATED = "PersonCreated";
  public static final String PERSON_UPDATED = "PersonUpdated";
  public static final String PERSON_ACTIVATED = "PersonActivated";
  public static final String PERSON_DEACTIVATED = "PersonDeactivated";

  // Query — received on queries.users-service
  public static final String RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL = "ResolvePersonByInstitutionalEmail";

  // Query Result — published to platform.results; single messageType,
  // no Succeeded/Rejected split (queries.md §2: FOUND/INACTIVE_MATCH/
  // NOT_FOUND are three flavors of one successful resolution).
  public static final String RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL_RESULT = "ResolvePersonByInstitutionalEmailResult";

  public static final Set<String> COMMANDS =
      Set.of(CREATE_PERSON, UPDATE_PERSON, ACTIVATE_PERSON, DEACTIVATE_PERSON);

  public static final Set<String> RESULTS =
      Set.of(
          CREATE_PERSON_SUCCEEDED,
          CREATE_PERSON_REJECTED,
          UPDATE_PERSON_SUCCEEDED,
          UPDATE_PERSON_REJECTED,
          ACTIVATE_PERSON_SUCCEEDED,
          ACTIVATE_PERSON_REJECTED,
          DEACTIVATE_PERSON_SUCCEEDED,
          DEACTIVATE_PERSON_REJECTED);

  public static final Set<String> EVENTS =
      Set.of(PERSON_CREATED, PERSON_UPDATED, PERSON_ACTIVATED, PERSON_DEACTIVATED);

  public static final Set<String> QUERIES = Set.of(RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL);

  public static final Set<String> QUERY_RESULTS = Set.of(RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL_RESULT);

  public static final Set<String> ALL;

  static {
    ALL =
        Set.copyOf(
            java.util.stream.Stream.of(COMMANDS, RESULTS, EVENTS, QUERIES, QUERY_RESULTS)
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet()));
  }
}
