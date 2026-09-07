package com.estoma.platformbff.contracts.http.wash;

import jakarta.validation.Valid;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

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
  public record SupervisorLookupRequest(
      @NotNull LookupType lookupType,
      @Size(max = 2_000) String qrRepresentation,
      @Size(max = 120) String studentEnrollment) {
    @AssertTrue(message = "exactly one lookup value matching lookupType is required")
    public boolean hasMatchingValue() {
      return lookupType == LookupType.QR ? notBlank(qrRepresentation) && !notBlank(studentEnrollment)
          : lookupType == LookupType.STUDENT_ENROLLMENT
              ? notBlank(studentEnrollment) && !notBlank(qrRepresentation)
          : false;
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
  }

  public enum LookupType { QR, STUDENT_ENROLLMENT }

  public record EntryDecisionRequest(
      @JsonDeserialize(using = StrictNullableBooleanDeserializer.class)
      Boolean identityConfirmed,
      @JsonDeserialize(using = StrictNullableBooleanDeserializer.class)
      Boolean requirementsSatisfied,
      @NotBlank @Pattern(regexp = "AUTHORIZED|REJECTED") String decision,
      @Size(max = 500) String rejectionReason,
      @Positive int expectedVersion) {
    @AssertTrue(message = "entry decision fields are inconsistent")
    @JsonIgnore
    public boolean isDecisionShapeValid() {
      if ("AUTHORIZED".equals(decision)) {
        return Boolean.TRUE.equals(identityConfirmed)
            && Boolean.TRUE.equals(requirementsSatisfied)
            && rejectionReason == null;
      }
      if (!"REJECTED".equals(decision)) return true;
      boolean checksAbsent = identityConfirmed == null && requirementsSatisfied == null;
      boolean checksPresent = identityConfirmed != null && requirementsSatisfied != null;
      return (checksAbsent || checksPresent)
          && rejectionReason != null && !rejectionReason.isBlank();
    }

    @JsonIgnore
    public boolean isUnclassifiedRejection() {
      return "REJECTED".equals(decision)
          && identityConfirmed == null && requirementsSatisfied == null;
    }
  }

  /** Limits strict JSON boolean handling to the two legacy entry-decision checks. */
  public static final class StrictNullableBooleanDeserializer extends StdDeserializer<Boolean> {
    public StrictNullableBooleanDeserializer() {
      super(Boolean.class);
    }

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.hasToken(JsonToken.VALUE_TRUE)) return Boolean.TRUE;
      if (parser.hasToken(JsonToken.VALUE_FALSE)) return Boolean.FALSE;
      return context.reportInputMismatch(Boolean.class, "expected a JSON boolean or null");
    }

    @Override
    public Boolean getNullValue(DeserializationContext context) {
      return null;
    }
  }

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
