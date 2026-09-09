package com.estoma.platformbff.api.admin;

import static com.estoma.platformbff.api.admin.AdminRequestSchema.Kind.BOOLEAN;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.Kind.DATE;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.Kind.DATE_TIME;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.Kind.INTEGER;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.Kind.STRING;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.Kind.UUID;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.enumValue;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.of;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.optional;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.positive;
import static com.estoma.platformbff.api.admin.AdminRequestSchema.required;

import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.security.AuthenticationEdgeService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Canonical administrative HTTP V1 surface. Each route is a declared owner intent with a
 * client-oriented schema; arbitrary commands and owner wire DTOs are intentionally never public.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private static final AdminRequestSchema EXPECTED = of(positive("expectedVersion"));
  private static final Map<String, String> AGGREGATE_ID_FIELDS = Map.ofEntries(
      Map.entry("CreatePerson", "personId"),
      Map.entry("UpdatePerson", "personId"),
      Map.entry("ActivatePerson", "personId"),
      Map.entry("DeactivatePerson", "personId"),
      Map.entry("CreateAccount", "accountId"),
      Map.entry("ActivateAccount", "accountId"),
      Map.entry("DeactivateAccount", "accountId"),
      Map.entry("ChangeLoginIdentifier", "accountId"),
      Map.entry("RevokeAllAccountSessions", "accountId"),
      Map.entry("AssignAccountRole", "accountId"),
      Map.entry("ChangeAccountRole", "accountId"),
      Map.entry("GrantSystemAccess", "systemAccessId"),
      Map.entry("SuspendSystemAccess", "systemAccessId"),
      Map.entry("RevokeSystemAccess", "systemAccessId"),
      Map.entry("RestoreSystemAccess", "systemAccessId"),
      Map.entry("CreateStudentAcademicProfile", "studentAcademicProfileId"),
      Map.entry("CreateAndActivateAcademicPeriod", "academicPeriodId"),
      Map.entry("StartStudentImport", "importId"),
      Map.entry("ResumeStudentImport", "importId"),
      Map.entry("CreateServiceCalendar", "calendarId"),
      Map.entry("ActivateServiceCalendar", "calendarId"),
      Map.entry("ConfigureServiceSchedule", "scheduleId"),
      Map.entry("RegisterCabin", "cabinId"),
      Map.entry("ActivateCabin", "cabinId"),
      Map.entry("RegisterTank", "tankId"),
      Map.entry("ActivateTank", "tankId"),
      Map.entry("CreateAppointmentTimeSlot", "slotId"));
  private static final Map<String, RouteContract> ROUTES = Map.ofEntries(
      route("POST", "/api/v1/admin/people", "admin.people.create", "CreatePerson", of(
          required("personId", UUID), required("firstName", STRING), required("paternalSurname", STRING),
          optional("maternalSurname", STRING), required("institutionalEmail", STRING), optional("phone", STRING))),
      route("PATCH", "/api/v1/admin/people/{personId}", "admin.people.update", "UpdatePerson", of(
          positive("expectedVersion"), required("firstName", STRING), required("paternalSurname", STRING),
          optional("maternalSurname", STRING), required("institutionalEmail", STRING), optional("phone", STRING))),
      route("POST", "/api/v1/admin/people/{personId}/activate", "admin.people.activate", "ActivatePerson", EXPECTED),
      route("POST", "/api/v1/admin/people/{personId}/deactivate", "admin.people.deactivate", "DeactivatePerson", of(
          positive("expectedVersion"), required("reasonCode", STRING), optional("reasonDetail", STRING))),

      route("POST", "/api/v1/admin/accounts", "admin.accounts.create", "CreateAccount", of(
          required("accountId", UUID), required("personId", UUID),
          enumValue("loginType", true, "MATRICULA", "USERNAME"), required("loginIdentifier", STRING))),
      route("POST", "/api/v1/admin/accounts/{accountId}/activate", "admin.accounts.activate", "ActivateAccount", EXPECTED),
      route("POST", "/api/v1/admin/accounts/{accountId}/deactivate", "admin.accounts.deactivate", "DeactivateAccount", of(
          positive("expectedVersion"), required("reasonCode", STRING), optional("reasonDetail", STRING))),
      route("PATCH", "/api/v1/admin/accounts/{accountId}/login-identifier", "admin.accounts.change-login-identifier", "ChangeLoginIdentifier", of(
          positive("expectedVersion"), required("newLoginIdentifier", STRING))),
      route("POST", "/api/v1/admin/accounts/{accountId}/sessions/revoke-all", "admin.accounts.revoke-all-sessions", "RevokeAllAccountSessions", of(optional("reason", STRING))),

      route("POST", "/api/v1/admin/authorization/accounts/{accountId}/role", "admin.authorization.assign-role", "AssignAccountRole", of(required("roleCode", STRING))),
      route("PATCH", "/api/v1/admin/authorization/accounts/{accountId}/role", "admin.authorization.change-role", "ChangeAccountRole", of(
          required("newRoleCode", STRING), positive("expectedVersion"))),
      route("POST", "/api/v1/admin/authorization/accounts/{accountId}/system-accesses", "admin.authorization.grant-system-access", "GrantSystemAccess", of(
          required("systemAccessId", UUID), required("systemCode", STRING))),
      route("POST", "/api/v1/admin/authorization/system-accesses/{systemAccessId}/suspend", "admin.authorization.suspend-system-access", "SuspendSystemAccess", of(
          positive("expectedVersion"), required("reasonCode", STRING), optional("reasonDetail", STRING))),
      route("POST", "/api/v1/admin/authorization/system-accesses/{systemAccessId}/revoke", "admin.authorization.revoke-system-access", "RevokeSystemAccess", of(
          positive("expectedVersion"), required("reasonCode", STRING), optional("reasonDetail", STRING))),
      route("POST", "/api/v1/admin/authorization/system-accesses/{systemAccessId}/restore", "admin.authorization.restore-system-access", "RestoreSystemAccess", EXPECTED),

      route("POST", "/api/v1/admin/academic/students", "admin.academic.students.create", "CreateStudentAcademicProfile", of(
          optional("studentAcademicProfileId", UUID), required("personId", UUID), required("accountId", UUID),
          required("studentNumber", STRING), positive("currentSemester"),
          enumValue("academicStatus", false, "ACTIVE", "INACTIVE"))),
      route("PATCH", "/api/v1/admin/academic/students/{studentAcademicProfileId}/student-number", "admin.academic.students.correct-number", "CorrectStudentNumber", of(
          positive("expectedVersion"), required("currentStudentNumber", STRING), required("newStudentNumber", STRING), required("reason", STRING)),
          Set.of("correctedByAccountId")),
      route("PATCH", "/api/v1/admin/academic/students/{studentAcademicProfileId}/semester", "admin.academic.students.change-semester", "ChangeStudentSemester", of(
          positive("expectedVersion"), positive("currentSemester"))),
      route("POST", "/api/v1/admin/academic/students/{studentAcademicProfileId}/activate", "admin.academic.students.activate", "ActivateStudentAcademicProfile", EXPECTED),
      route("POST", "/api/v1/admin/academic/students/{studentAcademicProfileId}/deactivate", "admin.academic.students.deactivate", "DeactivateStudentAcademicProfile", EXPECTED),
      route("POST", "/api/v1/admin/academic/periods", "admin.academic.periods.create-and-activate", "CreateAndActivateAcademicPeriod", of(
          optional("academicPeriodId", UUID), required("code", STRING), required("name", STRING),
          required("startDate", DATE), required("endDate", DATE), required("warningAcknowledged", BOOLEAN)),
          Set.of("requestedByAccountId")),
      route("POST", "/api/v1/admin/academic/course-sections", "admin.academic.course-sections.register", "RegisterCourseSection", of(
          optional("courseSectionId", UUID), required("academicPeriodId", UUID), required("nrc", STRING), required("name", STRING))),
      route("PATCH", "/api/v1/admin/academic/course-sections/{courseSectionId}", "admin.academic.course-sections.update", "UpdateCourseSection", of(
          positive("expectedVersion"), required("name", STRING)), Set.of("requestedByAccountId")),
      route("POST", "/api/v1/admin/academic/course-sections/{courseSectionId}/activate", "admin.academic.course-sections.activate", "ActivateCourseSection", of(
          positive("expectedVersion"), required("academicPeriodId", UUID), required("name", STRING), optional("reason", STRING)),
          Set.of("requestedByAccountId")),
      route("POST", "/api/v1/admin/academic/course-sections/{courseSectionId}/deactivate", "admin.academic.course-sections.deactivate", "DeactivateCourseSection", of(
          positive("expectedVersion"), required("academicPeriodId", UUID), optional("reason", STRING)),
          Set.of("requestedByAccountId")),
      route("POST", "/api/v1/admin/academic/students/{studentAcademicProfileId}/enrollments", "admin.academic.enrollments.add", "AddStudentEnrollment", of(required("courseSectionId", UUID))),
      route("POST", "/api/v1/admin/academic/enrollments/{studentEnrollmentId}/reactivate", "admin.academic.enrollments.reactivate", "ReactivateStudentEnrollment", of(
          required("studentAcademicProfileId", UUID), required("courseSectionId", UUID), positive("expectedVersion")),
          Set.of("requestedByAccountId")),
      route("DELETE", "/api/v1/admin/academic/enrollments/{studentEnrollmentId}", "admin.academic.enrollments.remove", "RemoveStudentEnrollment", of(
          required("studentAcademicProfileId", UUID), required("courseSectionId", UUID), optional("reason", STRING))),
      route("POST", "/api/v1/admin/academic/imports", "admin.academic.imports.start", "StartStudentImport", of(
          required("importId", UUID), required("fileReference", UUID), required("fileChecksum", STRING), optional("confirmationAcknowledged", BOOLEAN)),
          Set.of("requestedByAccountId")),
      route("POST", "/api/v1/admin/academic/imports/{importId}/resume", "admin.academic.imports.resume", "ResumeStudentImport", of(),
          Set.of("requestedByAccountId")),

      route("POST", "/api/v1/admin/communications/email-deliveries/{emailDeliveryId}/retry", "admin.communications.email-deliveries.retry", "RetryEmailDelivery", of(optional("reason", STRING))),
      route("POST", "/api/v1/admin/communications/email-deliveries/{emailDeliveryId}/resolve-indeterminate", "admin.communications.email-deliveries.resolve-indeterminate", "ResolveIndeterminateEmailDelivery", of(
          enumValue("resolution", true, "RECONCILED_ACCEPTED", "RECONCILED_FAILED", "RESEND_AUTHORIZED"),
          required("riskAcknowledged", BOOLEAN), optional("reason", STRING))),

      route("POST", "/api/v1/admin/wash/calendars", "admin.wash.calendars.create", "CreateServiceCalendar", of(
          required("calendarId", UUID), required("code", STRING), required("name", STRING), required("timezone", STRING),
          required("effectiveFrom", DATE), optional("effectiveUntil", DATE))),
      route("PATCH", "/api/v1/admin/wash/calendars/{serviceCalendarId}", "admin.wash.calendars.update", "UpdateServiceCalendar", of(
          required("name", STRING), required("timezone", STRING), required("effectiveFrom", DATE), optional("effectiveUntil", DATE), positive("expectedVersion")), Map.of("serviceCalendarId", "calendarId")),
      route("POST", "/api/v1/admin/wash/calendars/{serviceCalendarId}/activate", "admin.wash.calendars.activate", "ActivateServiceCalendar", EXPECTED, Map.of("serviceCalendarId", "calendarId")),
      route("POST", "/api/v1/admin/wash/calendars/{serviceCalendarId}/deactivate", "admin.wash.calendars.deactivate", "DeactivateServiceCalendar", of(
          required("reason", STRING), positive("expectedVersion")), Map.of("serviceCalendarId", "calendarId")),
      route("POST", "/api/v1/admin/wash/calendars/{serviceCalendarId}/schedules", "admin.wash.schedules.configure", "ConfigureServiceSchedule", of(
          required("scheduleId", UUID), positive("dayOfWeek"), required("openingTime", STRING), required("closingTime", STRING), positive("slotDurationMinutes")), Map.of("serviceCalendarId", "calendarId")),
      route("PATCH", "/api/v1/admin/wash/schedules/{serviceScheduleId}", "admin.wash.schedules.update", "UpdateServiceSchedule", of(
          positive("dayOfWeek"), required("openingTime", STRING), required("closingTime", STRING), positive("slotDurationMinutes"), positive("expectedVersion")), Map.of("serviceScheduleId", "scheduleId")),
      route("POST", "/api/v1/admin/wash/schedules/{serviceScheduleId}/deactivate", "admin.wash.schedules.deactivate", "DeactivateServiceSchedule", of(
          required("reason", STRING), positive("expectedVersion")), Map.of("serviceScheduleId", "scheduleId")),
      route("POST", "/api/v1/admin/wash/administrative-closures", "admin.wash.closures.schedule", "ScheduleAdministrativeClosure", of(
          required("closureId", UUID), required("calendarId", UUID), enumValue("scope", true, "CALENDAR", "CABIN", "TANK"),
          optional("cabinId", UUID), optional("tankId", UUID), required("startsAt", DATE_TIME), required("endsAt", DATE_TIME), required("reason", STRING))),
      route("POST", "/api/v1/admin/wash/administrative-closures/{administrativeClosureId}/cancel", "admin.wash.closures.cancel", "CancelAdministrativeClosure", of(
          required("reason", STRING), positive("expectedVersion")), Map.of("administrativeClosureId", "closureId")),
      route("POST", "/api/v1/admin/wash/cabins", "admin.wash.cabins.register", "RegisterCabin", of(
          required("cabinId", UUID), required("code", STRING), required("name", STRING), required("cabinType", STRING))),
      route("PATCH", "/api/v1/admin/wash/cabins/{cabinId}", "admin.wash.cabins.update", "UpdateCabin", of(
          required("name", STRING), required("cabinType", STRING), positive("expectedVersion"))),
      route("POST", "/api/v1/admin/wash/cabins/{cabinId}/activate", "admin.wash.cabins.activate", "ActivateCabin", EXPECTED),
      route("POST", "/api/v1/admin/wash/cabins/{cabinId}/deactivate", "admin.wash.cabins.deactivate", "DeactivateCabin", of(required("reason", STRING), positive("expectedVersion"))),
      route("POST", "/api/v1/admin/wash/cabins/{cabinId}/retire", "admin.wash.cabins.retire", "RetireCabin", of(required("reason", STRING), positive("expectedVersion"))),
      route("POST", "/api/v1/admin/wash/cabins/{cabinId}/tanks", "admin.wash.tanks.register", "RegisterTank", of(
          required("tankId", UUID), required("code", STRING), required("name", STRING), positive("configuredCapacity"))),
      route("PATCH", "/api/v1/admin/wash/tanks/{tankId}", "admin.wash.tanks.update", "UpdateTank", of(
          required("name", STRING), positive("configuredCapacity"), positive("expectedVersion"))),
      route("POST", "/api/v1/admin/wash/tanks/{tankId}/activate", "admin.wash.tanks.activate", "ActivateTank", EXPECTED),
      route("POST", "/api/v1/admin/wash/tanks/{tankId}/deactivate", "admin.wash.tanks.deactivate", "DeactivateTank", of(required("reason", STRING), positive("expectedVersion"))),
      route("POST", "/api/v1/admin/wash/tanks/{tankId}/retire", "admin.wash.tanks.retire", "RetireTank", of(required("reason", STRING), positive("expectedVersion"))),
      routeToOwner("POST", "/api/v1/admin/wash/time-slots", "admin.wash.time-slots.create",
          "CreateAppointmentTimeSlot", "appointments", of(
              required("slotId", UUID), required("sourceScheduleId", UUID), required("serviceDate", DATE),
              required("startsAt", DATE_TIME), required("endsAt", DATE_TIME), required("timezone", STRING),
              required("bookingDeadlineAt", DATE_TIME), required("cancellationDeadlineAt", DATE_TIME),
              required("arrivalToleranceUntil", DATE_TIME))));

  private final PublicCommandAdapter commands;
  private final LocalAuthorizationService authorization;
  private final AuthenticationEdgeService authentication;

  public AdminController(PublicCommandAdapter commands, LocalAuthorizationService authorization,
      AuthenticationEdgeService authentication) {
    this.commands = commands;
    this.authorization = authorization;
    this.authentication = authentication;
  }

  @PostMapping({
      "/people", "/people/{personId}/activate", "/people/{personId}/deactivate", "/accounts",
      "/accounts/{accountId}/activate", "/accounts/{accountId}/deactivate", "/accounts/{accountId}/sessions/revoke-all",
      "/authorization/accounts/{accountId}/role", "/authorization/accounts/{accountId}/system-accesses",
      "/authorization/system-accesses/{systemAccessId}/suspend", "/authorization/system-accesses/{systemAccessId}/revoke",
      "/authorization/system-accesses/{systemAccessId}/restore", "/academic/students",
      "/academic/students/{studentAcademicProfileId}/activate", "/academic/students/{studentAcademicProfileId}/deactivate",
      "/academic/periods", "/academic/course-sections", "/academic/course-sections/{courseSectionId}/activate",
      "/academic/course-sections/{courseSectionId}/deactivate", "/academic/students/{studentAcademicProfileId}/enrollments",
      "/academic/enrollments/{studentEnrollmentId}/reactivate", "/academic/imports", "/academic/imports/{importId}/resume",
      "/communications/email-deliveries/{emailDeliveryId}/retry", "/communications/email-deliveries/{emailDeliveryId}/resolve-indeterminate",
      "/wash/calendars", "/wash/calendars/{serviceCalendarId}/activate", "/wash/calendars/{serviceCalendarId}/deactivate",
      "/wash/calendars/{serviceCalendarId}/schedules", "/wash/schedules/{serviceScheduleId}/deactivate",
      "/wash/administrative-closures", "/wash/administrative-closures/{administrativeClosureId}/cancel",
      "/wash/cabins", "/wash/cabins/{cabinId}/activate", "/wash/cabins/{cabinId}/deactivate", "/wash/cabins/{cabinId}/retire",
      "/wash/cabins/{cabinId}/tanks", "/wash/tanks/{tankId}/activate", "/wash/tanks/{tankId}/deactivate", "/wash/tanks/{tankId}/retire",
      "/wash/time-slots"})
  public ResponseEntity<AcceptedOperationResponse> post(
      @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body,
      @PathVariable Map<String, String> pathVariables, HttpServletRequest request) {
    return submit("POST", idempotencyKey, body, pathVariables, request);
  }

  @PatchMapping({
      "/people/{personId}", "/accounts/{accountId}/login-identifier", "/authorization/accounts/{accountId}/role",
      "/academic/students/{studentAcademicProfileId}/student-number", "/academic/students/{studentAcademicProfileId}/semester",
      "/academic/course-sections/{courseSectionId}", "/wash/calendars/{serviceCalendarId}",
      "/wash/schedules/{serviceScheduleId}", "/wash/cabins/{cabinId}", "/wash/tanks/{tankId}"})
  public ResponseEntity<AcceptedOperationResponse> patch(
      @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body,
      @PathVariable Map<String, String> pathVariables, HttpServletRequest request) {
    return submit("PATCH", idempotencyKey, body, pathVariables, request);
  }

  @DeleteMapping("/academic/enrollments/{studentEnrollmentId}")
  public ResponseEntity<AcceptedOperationResponse> delete(
      @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body,
      @PathVariable Map<String, String> pathVariables, HttpServletRequest request) {
    return submit("DELETE", idempotencyKey, body, pathVariables, request);
  }

  @PostMapping("/accounts/{accountId}/temporary-password")
  public ResponseEntity<TemporaryPasswordResponse> setTemporaryPassword(
      @PathVariable java.util.UUID accountId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    var result = authentication.setTemporaryPassword(authorization.requireSuperAdministrator(), accountId, idempotencyKey);
    return secret(new TemporaryPasswordResponse(result.temporaryPassword(), result.expiresAt()));
  }

  @PostMapping("/accounts/{accountId}/temporary-password/regenerate")
  public ResponseEntity<TemporaryPasswordResponse> regenerateTemporaryPassword(
      @PathVariable java.util.UUID accountId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    var result = authentication.regenerateTemporaryPassword(authorization.requireSuperAdministrator(), accountId, idempotencyKey);
    return secret(new TemporaryPasswordResponse(result.temporaryPassword(), result.expiresAt()));
  }

  private ResponseEntity<AcceptedOperationResponse> submit(String method, String idempotencyKey,
      Map<String, Object> body, Map<String, String> pathVariables, HttpServletRequest request) {
    String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    RouteContract route = routeFor(method, pattern);
    if (route == null) throw new IllegalArgumentException("unsupported administrative route");
    route.requestSchema().validate(body);
    Map<String, Object> idempotencyPayload = new LinkedHashMap<>(body);
    pathVariables.forEach((name, value) -> idempotencyPayload.put(
        route.pathBindings().getOrDefault(name, name), value));
    Map<String, Object> payload = new LinkedHashMap<>(idempotencyPayload);
    java.util.UUID aggregateId = aggregateId(route, payload);
    Integer aggregateVersion = AGGREGATE_ID_FIELDS.containsKey(route.messageType())
        ? integer(payload.get("expectedVersion")) : null;
    java.util.UUID actorAccountId = route.washScoped()
        ? authorization.requireWashAdministrator() : authorization.requireSuperAdministrator();
    route.serverDerivedActorFields().forEach(field -> payload.put(field, actorAccountId));
    AcceptedOperationResponse accepted = commands.submitEnriched(actorAccountId, route.publicOperation(),
        route.messageType(), payload, idempotencyPayload, idempotencyKey, aggregateId,
        aggregateVersion, null, null);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
  }

  private static java.util.UUID aggregateId(RouteContract route, Map<String, Object> payload) {
    String field = AGGREGATE_ID_FIELDS.get(route.messageType());
    if (field == null) return null;
    Object value = payload.get(field);
    if (value == null) return null;
    if (value instanceof java.util.UUID id) return id;
    return java.util.UUID.fromString(value.toString());
  }

  private static Integer integer(Object value) {
    if (value == null) return null;
    return Integer.valueOf(value.toString());
  }

  private static ResponseEntity<TemporaryPasswordResponse> secret(TemporaryPasswordResponse response) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(response);
  }

  /** Matrix used by the controller, OpenAPI and provider tests. Key format is {@code METHOD path}. */
  public static Map<String, RouteContract> routeMatrix() { return ROUTES; }

  public static RouteContract routeFor(String method, String path) { return ROUTES.get(key(method, path)); }

  private static Map.Entry<String, RouteContract> route(String method, String path, String publicOperation,
      String messageType, AdminRequestSchema schema) {
    return route(method, path, publicOperation, messageType, schema, Map.of(), Set.of());
  }

  private static Map.Entry<String, RouteContract> route(String method, String path, String publicOperation,
      String messageType, AdminRequestSchema schema, Set<String> serverDerivedActorFields) {
    return route(method, path, publicOperation, messageType, schema, Map.of(), serverDerivedActorFields);
  }

  private static Map.Entry<String, RouteContract> route(String method, String path, String publicOperation,
      String messageType, AdminRequestSchema schema, Map<String, String> pathBindings) {
    return route(method, path, publicOperation, messageType, schema, pathBindings, Set.of());
  }

  private static Map.Entry<String, RouteContract> route(String method, String path, String publicOperation,
      String messageType, AdminRequestSchema schema, Map<String, String> pathBindings,
      Set<String> serverDerivedActorFields) {
    return routeToOwner(method, path, publicOperation, messageType, ownerFor(path), schema,
        pathBindings, serverDerivedActorFields);
  }

  private static Map.Entry<String, RouteContract> routeToOwner(String method, String path,
      String publicOperation, String messageType, String owner, AdminRequestSchema schema) {
    return routeToOwner(method, path, publicOperation, messageType, owner, schema, Map.of(), Set.of());
  }

  private static Map.Entry<String, RouteContract> routeToOwner(String method, String path,
      String publicOperation, String messageType, String owner, AdminRequestSchema schema,
      Map<String, String> pathBindings, Set<String> serverDerivedActorFields) {
    return Map.entry(key(method, path), new RouteContract(method, path, publicOperation, messageType,
        owner, path.startsWith("/api/v1/admin/wash/"), schema, Map.copyOf(pathBindings),
        Set.copyOf(serverDerivedActorFields)));
  }

  private static String key(String method, String path) { return method + " " + path; }

  private static String ownerFor(String path) {
    if (path.startsWith("/api/v1/admin/people")) return "users";
    if (path.startsWith("/api/v1/admin/accounts")) return "authentication";
    if (path.startsWith("/api/v1/admin/authorization")) return "authorization";
    if (path.startsWith("/api/v1/admin/academic")) return "academic";
    if (path.startsWith("/api/v1/admin/communications")) return "communications";
    if (path.startsWith("/api/v1/admin/wash/")) return "wash-administration";
    throw new IllegalArgumentException("admin route does not have an owner: " + path);
  }

  public record RouteContract(String method, String path, String publicOperation, String messageType,
      String owner, boolean washScoped, AdminRequestSchema requestSchema, Map<String, String> pathBindings,
      Set<String> serverDerivedActorFields) {}

  public record TemporaryPasswordResponse(String temporaryPassword, java.time.Instant expiresAt) {}
}
