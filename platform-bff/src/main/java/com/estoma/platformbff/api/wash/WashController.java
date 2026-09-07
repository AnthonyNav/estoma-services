package com.estoma.platformbff.api.wash;

import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.application.wash.WashSupervisionQueryService;
import com.estoma.platformbff.application.wash.WashStudentQueryService;
import com.estoma.platformbff.application.wash.AppointmentAvailabilityService;
import com.estoma.platformbff.application.wash.ReassignmentCandidatesService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse;
import com.estoma.platformbff.contracts.http.wash.ReassignmentCandidatesResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentFormContextResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentAvailabilityResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentType;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.PieceType;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeResponse;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.CancelAppointmentRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.CancelExceptionalAuthorizationRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.ClinicCancelRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.CompleteWashExecutionRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.DisableOperationalResourceRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.EntryDecisionRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.SupervisorLookupRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.GrantExceptionalAuthorizationRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.ReassignmentRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.RegisterArrivalRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.RestoreOperationalResourceRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.ScheduleAppointmentRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.SubmitExitRequest;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorHomeResponse;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorLookupResponse;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorExecutionDetailResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wash")
public class WashController {
  private final PublicCommandAdapter commands;
  private final LocalAuthorizationService authorization;
  private final WashSupervisionQueryService supervision;
  private final WashStudentQueryService studentQueries;
  private final AppointmentAvailabilityService appointmentAvailability;
  private final ReassignmentCandidatesService reassignmentCandidates;
  private final Clock clock;
  private final boolean unclassifiedEntryRejectionEnabled;

  public WashController(PublicCommandAdapter commands, LocalAuthorizationService authorization,
      WashSupervisionQueryService supervision, WashStudentQueryService studentQueries,
      AppointmentAvailabilityService appointmentAvailability,
      ReassignmentCandidatesService reassignmentCandidates, Clock clock,
      @Value("${platform.wash.unclassified-entry-rejection-enabled:false}")
          boolean unclassifiedEntryRejectionEnabled) {
    this.commands = commands;
    this.authorization = authorization;
    this.supervision = supervision;
    this.studentQueries = studentQueries;
    this.appointmentAvailability = appointmentAvailability;
    this.reassignmentCandidates = reassignmentCandidates;
    this.clock = clock;
    this.unclassifiedEntryRejectionEnabled = unclassifiedEntryRejectionEnabled;
  }

  @PostMapping("/appointments")
  public ResponseEntity<AcceptedOperationResponse> scheduleAppointment(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ScheduleAppointmentRequest request) {
    UUID studentId = authorization.requireStudent();
    UUID appointmentId = UUID.randomUUID();
    Map<String, Object> intent = payload(
        "appointmentTimeSlotId", request.appointmentTimeSlotId(),
        "courseSectionId", request.courseSectionId(),
        "appointmentType", request.appointmentType(),
        "instrumentCount", request.instrumentCount(),
        "pieceType", request.pieceType(),
        "exceptionalAuthorizationId", request.exceptionalAuthorizationId(),
        "regulationAccepted", request.regulationAccepted());
    Map<String, Object> owner = new LinkedHashMap<>(intent);
    owner.put("appointmentId", appointmentId);
    owner.put("studentAccountId", studentId);
    return accepted(commands.submitEnriched(studentId, "wash-schedule-appointment", "ScheduleAppointment",
        owner, intent, idempotencyKey, appointmentId, null, null, null));
  }

  @PostMapping("/appointments/{appointmentId}/cancel")
  public ResponseEntity<AcceptedOperationResponse> cancelAppointment(@PathVariable UUID appointmentId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CancelAppointmentRequest request) {
    authorization.requireStudentOwnsAppointment(appointmentId);
    UUID studentId = authorization.requireAuthenticatedAccount();
    return accepted(commands.submit(studentId, "wash-cancel-appointment", "CancelAppointment",
        payload("appointmentId", appointmentId, "expectedVersion", request.expectedVersion(),
            "reason", request.reason()), idempotencyKey, appointmentId, null));
  }

  @PostMapping("/exceptional-authorizations")
  public ResponseEntity<AcceptedOperationResponse> grantExceptionalAuthorization(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody GrantExceptionalAuthorizationRequest request) {
    UUID supervisorId = authorization.requireWashSupervisor();
    UUID authorizationId = UUID.randomUUID();
    Map<String, Object> intent = payload("studentAccountId", request.studentAccountId(),
        "serviceDate", request.serviceDate(), "reason", request.reason(), "expiresAt", request.expiresAt());
    Map<String, Object> owner = new LinkedHashMap<>(intent);
    owner.put("authorizationId", authorizationId);
    return accepted(commands.submitEnriched(supervisorId, "wash-grant-exceptional-authorization",
        "GrantExceptionalAppointmentAuthorization", owner, intent, idempotencyKey,
        authorizationId, null, null, null));
  }

  @PostMapping("/exceptional-authorizations/{authorizationId}/cancel")
  public ResponseEntity<AcceptedOperationResponse> cancelExceptionalAuthorization(
      @PathVariable UUID authorizationId, @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CancelExceptionalAuthorizationRequest request) {
    UUID supervisorId = authorization.requireWashSupervisor();
    return accepted(commands.submit(supervisorId, "wash-cancel-exceptional-authorization",
        "CancelExceptionalAppointmentAuthorization",
        payload("authorizationId", authorizationId, "reason", request.reason()),
        idempotencyKey, authorizationId, null));
  }

  @PostMapping("/executions/arrivals")
  public ResponseEntity<AcceptedOperationResponse> registerArrival(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody RegisterArrivalRequest request) {
    UUID supervisorId = authorization.requireWashSupervisor();
    UUID washExecutionId = UUID.randomUUID();
    Instant arrivedAt = clock.instant();
    Map<String, Object> intent = payload("appointmentId", request.appointmentId());
    Map<String, Object> owner = payload("washExecutionId", washExecutionId,
        "appointmentId", request.appointmentId(), "arrivedAt", arrivedAt);
    return accepted(commands.submitEnriched(supervisorId, "wash-register-arrival", "RegisterWashArrival",
        owner, intent, idempotencyKey, washExecutionId, null, null, null));
  }

  @PostMapping("/executions/{washExecutionId}/entry-decision")
  public ResponseEntity<AcceptedOperationResponse> decideEntry(@PathVariable UUID washExecutionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody EntryDecisionRequest request) {
    UUID supervisorId = authorization.requireWashSupervisor();
    if (request.isUnclassifiedRejection() && !unclassifiedEntryRejectionEnabled) {
      throw new UnclassifiedEntryRejectionUnavailableException();
    }
    Instant now = clock.instant();
    Map<String, Object> intent = payload("washExecutionId", washExecutionId,
        "decision", request.decision(), "rejectionReason", request.rejectionReason(),
        "expectedVersion", request.expectedVersion());
    if (request.identityConfirmed() != null) {
      intent.put("identityConfirmed", request.identityConfirmed());
      intent.put("requirementsSatisfied", request.requirementsSatisfied());
    }
    Map<String, Object> owner = new LinkedHashMap<>(intent);
    owner.put("decidedByAccountId", supervisorId);
    owner.put("decidedAt", now);
    return accepted(commands.submitEnriched(supervisorId, "wash-decide-entry", "DecideWashEntry",
        owner, intent, idempotencyKey, washExecutionId, request.expectedVersion(),
        washExecutionId, request.expectedVersion()));
  }

  @PostMapping("/executions/{washExecutionId}/reassignment")
  public ResponseEntity<AcceptedOperationResponse> resolveReassignment(@PathVariable UUID washExecutionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ReassignmentRequest request) {
    UUID supervisorId = authorization.requireWashSupervisor();
    Instant now = clock.instant();
    Map<String, Object> intent = payload("washExecutionId", washExecutionId,
        "cabinId", request.cabinId(), "tankId", request.tankId(), "expectedVersion", request.expectedVersion());
    Map<String, Object> owner = new LinkedHashMap<>(intent);
    owner.put("resolvedByAccountId", supervisorId);
    owner.put("resolvedAt", now);
    return accepted(commands.submitEnriched(supervisorId, "wash-resolve-reassignment",
        "ResolvePendingReassignment", owner, intent, idempotencyKey,
        washExecutionId, request.expectedVersion(), washExecutionId, request.expectedVersion()));
  }

  @PostMapping("/executions/{washExecutionId}/exit")
  public ResponseEntity<AcceptedOperationResponse> submitExit(@PathVariable UUID washExecutionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody SubmitExitRequest request) {
    if (request.materials().isEmpty()) throw new IllegalArgumentException("exit materials cannot be empty");
    authorization.requireStudentOwnsWashExecution(washExecutionId);
    UUID studentId = authorization.requireAuthenticatedAccount();
    Instant now = clock.instant();
    Map<String, Object> intent = payload("washExecutionId", washExecutionId,
        "materials", materials(request.materials()), "expectedVersion", request.expectedVersion());
    Map<String, Object> owner = new LinkedHashMap<>(intent);
    owner.put("submittedByStudentAccountId", studentId);
    owner.put("submittedAt", now);
    return accepted(commands.submitEnriched(studentId, "wash-submit-exit", "SubmitWashExit",
        owner, intent, idempotencyKey, washExecutionId, request.expectedVersion(),
        washExecutionId, request.expectedVersion()));
  }

  @PostMapping("/executions/{washExecutionId}/complete")
  public ResponseEntity<AcceptedOperationResponse> completeWashExecution(@PathVariable UUID washExecutionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CompleteWashExecutionRequest request) {
    if (request.finalMaterials().isEmpty()) throw new IllegalArgumentException("final materials cannot be empty");
    UUID supervisorId = authorization.requireWashSupervisor();
    Instant now = clock.instant();
    Map<String, Object> intent = payload("washExecutionId", washExecutionId,
        "finalMaterials", materials(request.finalMaterials()), "expectedVersion", request.expectedVersion());
    Map<String, Object> owner = new LinkedHashMap<>(intent);
    owner.put("completedByAccountId", supervisorId);
    owner.put("completedAt", now);
    return accepted(commands.submitEnriched(supervisorId, "wash-complete-execution", "CompleteWashExecution",
        owner, intent, idempotencyKey, washExecutionId, request.expectedVersion(),
        washExecutionId, request.expectedVersion()));
  }

  @PostMapping("/executions/{washExecutionId}/clinic-cancel")
  public ResponseEntity<AcceptedOperationResponse> cancelByClinic(@PathVariable UUID washExecutionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ClinicCancelRequest request) {
    UUID supervisorId = authorization.requireWashSupervisor();
    Instant now = clock.instant();
    Map<String, Object> intent = payload("washExecutionId", washExecutionId,
        "cancellationSubreason", request.cancellationSubreason(),
        "cancellationReason", request.cancellationReason(), "expectedVersion", request.expectedVersion());
    Map<String, Object> owner = new LinkedHashMap<>(intent);
    owner.put("cancelledByAccountId", supervisorId);
    owner.put("cancelledAt", now);
    return accepted(commands.submitEnriched(supervisorId, "wash-clinic-cancel-execution",
        "CancelWashExecutionByClinic", owner, intent, idempotencyKey,
        washExecutionId, request.expectedVersion(), washExecutionId, request.expectedVersion()));
  }

  @PostMapping("/operational-resources/disable")
  public ResponseEntity<AcceptedOperationResponse> disableOperationalResource(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody DisableOperationalResourceRequest request) {
    if ((request.cabinId() == null) == (request.tankId() == null)) {
      throw new IllegalArgumentException("exactly one operational resource is required");
    }
    UUID supervisorId = authorization.requireWashSupervisor();
    UUID resourceUnavailabilityId = UUID.randomUUID();
    Instant now = clock.instant();
    Map<String, Object> intent = payload("cabinId", request.cabinId(), "tankId", request.tankId(),
        "causeType", request.causeType(), "reason", request.reason(),
        "detectedDuringWashExecutionId", request.detectedDuringWashExecutionId());
    Map<String, Object> owner = new LinkedHashMap<>(intent);
    owner.put("resourceUnavailabilityId", resourceUnavailabilityId);
    owner.put("disabledByAccountId", supervisorId);
    owner.put("disabledAt", now);
    return accepted(commands.submitEnriched(supervisorId, "wash-disable-operational-resource",
        "DisableOperationalResource", owner, intent, idempotencyKey,
        resourceUnavailabilityId, null, null, null));
  }

  @PostMapping("/operational-resources/restore")
  public ResponseEntity<AcceptedOperationResponse> restoreOperationalResource(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody RestoreOperationalResourceRequest request) {
    UUID supervisorId = authorization.requireWashSupervisor();
    Instant now = clock.instant();
    Map<String, Object> intent = payload("resourceUnavailabilityId", request.resourceUnavailabilityId(),
        "resolution", request.resolution(), "expectedVersion", request.expectedVersion());
    Map<String, Object> owner = new LinkedHashMap<>(intent);
    owner.put("restoredByAccountId", supervisorId);
    owner.put("restoredAt", now);
    return accepted(commands.submitEnriched(supervisorId, "wash-restore-operational-resource",
        "RestoreOperationalResource", owner, intent, idempotencyKey,
        request.resourceUnavailabilityId(), request.expectedVersion(),
        request.resourceUnavailabilityId(), request.expectedVersion()));
  }

  @GetMapping("/supervision/pending-reassignments")
  public PendingReassignmentsResponse pendingReassignments() {
    return supervision.pendingReassignments();
  }

  @GetMapping("/supervision/pending-reassignments/{washExecutionId}/candidates")
  public ReassignmentCandidatesResponse reassignmentCandidates(
      @PathVariable UUID washExecutionId) {
    return reassignmentCandidates.candidates(washExecutionId);
  }

  @GetMapping("/supervision/home")
  public SupervisorHomeResponse supervisorHome() {
    return supervision.home();
  }

  @PostMapping("/supervision/lookup")
  public SupervisorLookupResponse supervisorLookup(@Valid @RequestBody SupervisorLookupRequest request) {
    return supervision.lookup(request);
  }

  @GetMapping("/supervision/executions/{washExecutionId}")
  public SupervisorExecutionDetailResponse supervisorExecution(
      @PathVariable UUID washExecutionId) {
    return supervision.execution(washExecutionId);
  }

  @GetMapping("/student/home")
  public StudentHomeResponse studentHome() {
    return studentQueries.home();
  }

  @GetMapping("/appointments/form-context")
  public AppointmentFormContextResponse appointmentFormContext() {
    return studentQueries.formContext();
  }

  @GetMapping("/appointments/availability")
  public AppointmentAvailabilityResponse appointmentAvailability(
      @RequestParam AppointmentType appointmentType,
      @RequestParam @jakarta.validation.constraints.Min(1)
      @jakarta.validation.constraints.Max(80) int instrumentCount,
      @RequestParam PieceType pieceType,
      @RequestParam UUID courseSectionId) {
    return appointmentAvailability.availability(
        appointmentType, instrumentCount, pieceType, courseSectionId);
  }

  private static ResponseEntity<AcceptedOperationResponse> accepted(AcceptedOperationResponse response) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  private static Map<String, Object> materials(
      com.estoma.platformbff.contracts.http.wash.WashHttpRequests.ExitMaterialsRequest values) {
    return Map.of("packageCount", values.packageCount(),
        "greenPaperCassette8Count", values.greenPaperCassette8Count(),
        "greenPaperCassette10Count", values.greenPaperCassette10Count(),
        "witnessTapePortionCount", values.witnessTapePortionCount());
  }

  private static Map<String, Object> payload(Object... values) {
    if (values.length % 2 != 0) throw new IllegalArgumentException("payload entries must be pairs");
    Map<String, Object> payload = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      payload.put((String) values[index], values[index + 1]);
    }
    return payload;
  }

  public static final class UnclassifiedEntryRejectionUnavailableException
      extends RuntimeException {}
}
