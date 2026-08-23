package com.estoma.appointments.domain.model;

/**
 * Locally generated cancellation subreason constants.
 *
 * <p>This is deliberately not an enum: cancellation subreason codes received from
 * wash-execution-service are owner-defined and must be preserved even when Appointments does not
 * recognize them. CAPACITY_LOSS is only the code Appointments itself emits for its administrative
 * capacity-loss flow.
 */
public final class CancellationSubreason {
  public static final String CAPACITY_LOSS = "CAPACITY_LOSS";
  private CancellationSubreason() {}
}
