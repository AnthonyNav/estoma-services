package com.estoma.platformbff.api.admin;

import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Explicit public admin route matrix. Methods share a narrow transport implementation, but each
 * declared URI maps to exactly one allowlisted owner command (never to a client-selected command).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private static final Map<String, RouteContract> ROUTES = Map.ofEntries(
      route("/api/v1/admin/persons", "admin-create-person", "CreatePerson"),
      route("/api/v1/admin/persons/{personId}/update", "admin-update-person", "UpdatePerson"),
      route("/api/v1/admin/persons/{personId}/activate", "admin-activate-person", "ActivatePerson"),
      route("/api/v1/admin/persons/{personId}/deactivate", "admin-deactivate-person", "DeactivatePerson"),
      route("/api/v1/admin/accounts", "admin-create-account", "CreateAccount"),
      route("/api/v1/admin/accounts/{accountId}/activate", "admin-activate-account", "ActivateAccount"),
      route("/api/v1/admin/accounts/{accountId}/deactivate", "admin-deactivate-account", "DeactivateAccount"),
      route("/api/v1/admin/accounts/{accountId}/login-identifier", "admin-change-login-identifier", "ChangeLoginIdentifier"),
      route("/api/v1/admin/accounts/{accountId}/revoke-sessions", "admin-revoke-account-sessions", "RevokeAllAccountSessions"),
      route("/api/v1/admin/accounts/{accountId}/roles", "admin-assign-account-role", "AssignAccountRole"),
      route("/api/v1/admin/accounts/{accountId}/role", "admin-change-account-role", "ChangeAccountRole"),
      route("/api/v1/admin/accounts/{accountId}/accesses", "admin-grant-system-access", "GrantSystemAccess"),
      route("/api/v1/admin/accounts/{accountId}/accesses/{systemAccessId}/suspend", "admin-suspend-system-access", "SuspendSystemAccess"),
      route("/api/v1/admin/accounts/{accountId}/accesses/{systemAccessId}/revoke", "admin-revoke-system-access", "RevokeSystemAccess"),
      route("/api/v1/admin/accounts/{accountId}/accesses/{systemAccessId}/restore", "admin-restore-system-access", "RestoreSystemAccess"),
      route("/api/v1/admin/students", "admin-create-student-profile", "CreateStudentAcademicProfile"),
      route("/api/v1/admin/students/{accountId}/student-number", "admin-correct-student-number", "CorrectStudentNumber"),
      route("/api/v1/admin/students/{accountId}/semester", "admin-change-student-semester", "ChangeStudentSemester"),
      route("/api/v1/admin/students/{accountId}/activate", "admin-activate-student-profile", "ActivateStudentAcademicProfile"),
      route("/api/v1/admin/students/{accountId}/deactivate", "admin-deactivate-student-profile", "DeactivateStudentAcademicProfile"),
      route("/api/v1/admin/academic-periods", "admin-create-academic-period", "CreateAndActivateAcademicPeriod"),
      route("/api/v1/admin/course-sections", "admin-register-course-section", "RegisterCourseSection"),
      route("/api/v1/admin/course-sections/{courseSectionId}/update", "admin-update-course-section", "UpdateCourseSection"),
      route("/api/v1/admin/course-sections/{courseSectionId}/activate", "admin-activate-course-section", "ActivateCourseSection"),
      route("/api/v1/admin/course-sections/{courseSectionId}/deactivate", "admin-deactivate-course-section", "DeactivateCourseSection"),
      route("/api/v1/admin/students/{accountId}/enrollments", "admin-add-student-enrollment", "AddStudentEnrollment"),
      route("/api/v1/admin/students/{accountId}/enrollments/{courseSectionId}/reactivate", "admin-reactivate-student-enrollment", "ReactivateStudentEnrollment"),
      route("/api/v1/admin/students/{accountId}/enrollments/{courseSectionId}/remove", "admin-remove-student-enrollment", "RemoveStudentEnrollment"),
      route("/api/v1/admin/student-imports", "admin-start-student-import", "StartStudentImport"),
      route("/api/v1/admin/student-imports/{importId}/resume", "admin-resume-student-import", "ResumeStudentImport"),
      route("/api/v1/admin/mail-deliveries/{emailDeliveryId}/retry", "admin-retry-email-delivery", "RetryEmailDelivery"),
      route("/api/v1/admin/mail-deliveries/{emailDeliveryId}/resolve", "admin-resolve-email-delivery", "ResolveIndeterminateEmailDelivery"),
      route("/api/v1/admin/wash/calendars", "admin-create-wash-calendar", "CreateServiceCalendar"),
      route("/api/v1/admin/wash/calendars/{calendarId}/update", "admin-update-wash-calendar", "UpdateServiceCalendar"),
      route("/api/v1/admin/wash/calendars/{calendarId}/activate", "admin-activate-wash-calendar", "ActivateServiceCalendar"),
      route("/api/v1/admin/wash/calendars/{calendarId}/deactivate", "admin-deactivate-wash-calendar", "DeactivateServiceCalendar"),
      route("/api/v1/admin/wash/schedules", "admin-configure-wash-schedule", "ConfigureServiceSchedule"),
      route("/api/v1/admin/wash/schedules/{scheduleId}/update", "admin-update-wash-schedule", "UpdateServiceSchedule"),
      route("/api/v1/admin/wash/schedules/{scheduleId}/deactivate", "admin-deactivate-wash-schedule", "DeactivateServiceSchedule"),
      route("/api/v1/admin/wash/closures", "admin-schedule-wash-closure", "ScheduleAdministrativeClosure"),
      route("/api/v1/admin/wash/closures/{closureId}/cancel", "admin-cancel-wash-closure", "CancelAdministrativeClosure"),
      route("/api/v1/admin/wash/cabins", "admin-register-wash-cabin", "RegisterCabin"),
      route("/api/v1/admin/wash/cabins/{cabinId}/update", "admin-update-wash-cabin", "UpdateCabin"),
      route("/api/v1/admin/wash/cabins/{cabinId}/activate", "admin-activate-wash-cabin", "ActivateCabin"),
      route("/api/v1/admin/wash/cabins/{cabinId}/deactivate", "admin-deactivate-wash-cabin", "DeactivateCabin"),
      route("/api/v1/admin/wash/cabins/{cabinId}/retire", "admin-retire-wash-cabin", "RetireCabin"),
      route("/api/v1/admin/wash/tanks", "admin-register-wash-tank", "RegisterTank"),
      route("/api/v1/admin/wash/tanks/{tankId}/update", "admin-update-wash-tank", "UpdateTank"),
      route("/api/v1/admin/wash/tanks/{tankId}/activate", "admin-activate-wash-tank", "ActivateTank"),
      route("/api/v1/admin/wash/tanks/{tankId}/deactivate", "admin-deactivate-wash-tank", "DeactivateTank"),
      route("/api/v1/admin/wash/tanks/{tankId}/retire", "admin-retire-wash-tank", "RetireTank"));

  private final PublicCommandAdapter commands;
  private final LocalAuthorizationService authorization;

  public AdminController(PublicCommandAdapter commands, LocalAuthorizationService authorization) {
    this.commands = commands;
    this.authorization = authorization;
  }

  @PostMapping({
      "/persons", "/persons/{personId}/update", "/persons/{personId}/activate", "/persons/{personId}/deactivate",
      "/accounts", "/accounts/{accountId}/activate", "/accounts/{accountId}/deactivate",
      "/accounts/{accountId}/login-identifier", "/accounts/{accountId}/revoke-sessions",
      "/accounts/{accountId}/roles", "/accounts/{accountId}/role", "/accounts/{accountId}/accesses",
      "/accounts/{accountId}/accesses/{systemAccessId}/suspend", "/accounts/{accountId}/accesses/{systemAccessId}/revoke",
      "/accounts/{accountId}/accesses/{systemAccessId}/restore", "/students", "/students/{accountId}/student-number",
      "/students/{accountId}/semester", "/students/{accountId}/activate", "/students/{accountId}/deactivate",
      "/academic-periods", "/course-sections", "/course-sections/{courseSectionId}/update",
      "/course-sections/{courseSectionId}/activate", "/course-sections/{courseSectionId}/deactivate",
      "/students/{accountId}/enrollments", "/students/{accountId}/enrollments/{courseSectionId}/reactivate",
      "/students/{accountId}/enrollments/{courseSectionId}/remove", "/student-imports", "/student-imports/{importId}/resume",
      "/mail-deliveries/{emailDeliveryId}/retry", "/mail-deliveries/{emailDeliveryId}/resolve", "/wash/calendars",
      "/wash/calendars/{calendarId}/update", "/wash/calendars/{calendarId}/activate", "/wash/calendars/{calendarId}/deactivate",
      "/wash/schedules", "/wash/schedules/{scheduleId}/update", "/wash/schedules/{scheduleId}/deactivate",
      "/wash/closures", "/wash/closures/{closureId}/cancel", "/wash/cabins", "/wash/cabins/{cabinId}/update",
      "/wash/cabins/{cabinId}/activate", "/wash/cabins/{cabinId}/deactivate", "/wash/cabins/{cabinId}/retire",
      "/wash/tanks", "/wash/tanks/{tankId}/update", "/wash/tanks/{tankId}/activate", "/wash/tanks/{tankId}/deactivate",
      "/wash/tanks/{tankId}/retire"})
  public ResponseEntity<AcceptedOperationResponse> submit(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Map<String, Object> body,
      @PathVariable Map<String, String> pathVariables,
      HttpServletRequest request) {
    String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    RouteContract route = ROUTES.get(pattern);
    if (route == null) throw new IllegalArgumentException("unsupported administrative route");
    Map<String, Object> payload = new LinkedHashMap<>(body);
    pathVariables.forEach(payload::put);
    java.util.UUID actorAccountId = route.washScoped()
        ? authorization.requireWashAdministrator()
        : authorization.requireSuperAdministrator();
    AcceptedOperationResponse accepted = commands.submit(actorAccountId,
        route.publicOperation(), route.messageType(), payload, idempotencyKey, null, null);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
  }

  /** Testable route-to-owner matrix; it is also the source for the public contract document. */
  public static Map<String, RouteContract> routeMatrix() {
    return ROUTES;
  }

  private static Map.Entry<String, RouteContract> route(
      String path, String publicOperation, String commandType) {
    return Map.entry(path, new RouteContract(publicOperation, commandType, ownerFor(path),
        path.startsWith("/api/v1/admin/wash/")));
  }

  private static String ownerFor(String path) {
    if (path.startsWith("/api/v1/admin/persons")) return "users";
    if (path.startsWith("/api/v1/admin/accounts/")
        && (path.contains("/roles") || path.contains("/role") || path.contains("/accesses"))) {
      return "authorization";
    }
    if (path.startsWith("/api/v1/admin/accounts")) return "authentication";
    if (path.startsWith("/api/v1/admin/students") || path.startsWith("/api/v1/admin/academic-periods")
        || path.startsWith("/api/v1/admin/course-sections")
        || path.startsWith("/api/v1/admin/student-imports")) return "academic";
    if (path.startsWith("/api/v1/admin/mail-deliveries")) return "communications";
    if (path.startsWith("/api/v1/admin/wash/")) return "wash-administration";
    throw new IllegalArgumentException("admin route does not have an owner: " + path);
  }

  public record RouteContract(
      String publicOperation, String messageType, String owner, boolean washScoped) {}
}
