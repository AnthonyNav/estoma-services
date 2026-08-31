package com.estoma.platformbff.contracts.http.wash;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Client-oriented request DTOs. Actor identities and operational timestamps are server-derived. */
public final class WashHttpRequests {
  private WashHttpRequests() {}

  public record ScheduleAppointmentRequest(
      @NotNull UUID appointmentTimeSlotId,
      @NotNull UUID courseSectionId,
      @NotBlank @Pattern(regexp = "NORMAL|JOURNEY|IMMUNOCOMPROMISED") String appointmentType,
      @Positive int instrumentCount,
      @NotBlank @Pattern(regexp = "HIGH_SPEED|LOW_SPEED|CONTRA_ANGLE") String pieceType,
      UUID exceptionalAuthorizationId,
      @AssertTrue boolean regulationAccepted) {}

  public record CancelAppointmentRequest(
      @Positive long expectedVersion,
      @Size(max = 500) String reason) {}

  public record GrantExceptionalAuthorizationRequest(
      @NotNull UUID studentAccountId,
      @NotNull LocalDate serviceDate,
      @NotBlank @Size(max = 500) String reason,
      Instant expiresAt) {}

  public record CancelExceptionalAuthorizationRequest(@NotBlank @Size(max = 500) String reason) {}

  public record RegisterArrivalRequest(@NotNull UUID appointmentId) {}

  /** QR is intentionally rejected until a QR-to-appointment projection is owner-supported. */
  public record EntryLookupRequest(
      @NotBlank @Pattern(regexp = "QR|STUDENT_ENROLLMENT") String lookupType,
      @Size(max = 2_000) String qrRepresentation,
      @Size(max = 120) String studentEnrollment) {
    @AssertTrue(message = "exactly one lookup value matching lookupType is required")
    public boolean hasMatchingValue() {
      return "QR".equals(lookupType) ? notBlank(qrRepresentation) && !notBlank(studentEnrollment)
          : "STUDENT_ENROLLMENT".equals(lookupType) ? notBlank(studentEnrollment) && !notBlank(qrRepresentation)
          : false;
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
  }

  public record EntryDecisionRequest(
      boolean identityConfirmed,
      boolean requirementsSatisfied,
      @NotBlank @Pattern(regexp = "AUTHORIZED|REJECTED") String decision,
      @Size(max = 500) String rejectionReason,
      @Positive int expectedVersion) {}

  public record ReassignmentRequest(
      @NotNull UUID cabinId,
      @NotNull UUID tankId,
      @Positive int expectedVersion) {}

  public record DisableOperationalResourceRequest(
      UUID cabinId,
      UUID tankId,
      @NotBlank @Pattern(regexp = "FAILURE|MANUAL_DISABLE") String causeType,
      @NotBlank @Size(max = 500) String reason,
      UUID detectedDuringWashExecutionId) {}

  public record RestoreOperationalResourceRequest(
      @NotNull UUID resourceUnavailabilityId,
      @Size(max = 500) String resolution,
      @Positive int expectedVersion) {}

  public record ExitMaterialsRequest(
      @PositiveOrZero int packageCount,
      @PositiveOrZero int greenPaperCassette8Count,
      @PositiveOrZero int greenPaperCassette10Count,
      @PositiveOrZero int witnessTapePortionCount) {
    public boolean isEmpty() {
      return packageCount == 0 && greenPaperCassette8Count == 0
          && greenPaperCassette10Count == 0 && witnessTapePortionCount == 0;
    }
  }

  public record SubmitExitRequest(@NotNull @Valid ExitMaterialsRequest materials,
                                  @Positive int expectedVersion) {}

  public record CompleteWashExecutionRequest(@NotNull @Valid ExitMaterialsRequest finalMaterials,
                                             @Positive int expectedVersion) {}

  public record ClinicCancelRequest(
      @NotBlank @Size(max = 160) String cancellationSubreason,
      @Size(max = 500) String cancellationReason,
      @Positive int expectedVersion) {}
}
