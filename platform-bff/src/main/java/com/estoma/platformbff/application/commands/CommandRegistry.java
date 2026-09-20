package com.estoma.platformbff.application.commands;

import com.estoma.platformbff.contracts.messaging.OwnerRoute;
import com.estoma.platformbff.contracts.messaging.OwnerRoute.ActorMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CommandRegistry {
  private static final Map<String, OwnerRoute> ROUTES = Map.ofEntries(
      route("CreatePerson", "users", ActorMode.ACCOUNT,
          "CreatePersonSucceeded", "CreatePersonRejected"),
      route("UpdatePerson", "users", ActorMode.ACCOUNT,
          "UpdatePersonSucceeded", "UpdatePersonRejected"),
      route("ActivatePerson", "users", ActorMode.ACCOUNT,
          "ActivatePersonSucceeded", "ActivatePersonRejected"),
      route("DeactivatePerson", "users", ActorMode.ACCOUNT,
          "DeactivatePersonSucceeded", "DeactivatePersonRejected"),

      route("CreateAccount", "authentication", ActorMode.ACCOUNT, "CreateAccountResult"),
      route("ActivateAccount", "authentication", ActorMode.ACCOUNT, "ActivateAccountResult"),
      route("DeactivateAccount", "authentication", ActorMode.ACCOUNT, "DeactivateAccountResult"),
      route("ChangeLoginIdentifier", "authentication", ActorMode.ACCOUNT,
          "ChangeLoginIdentifierResult"),
      route("RevokeAllAccountSessions", "authentication", ActorMode.ACCOUNT,
          "RevokeAllAccountSessionsResult"),
      route("RequestPasswordRecovery", "authentication", ActorMode.ANONYMOUS,
          "RequestPasswordRecoveryResult"),

      route("AssignAccountRole", "authorization", ActorMode.ACCOUNT, "AssignAccountRoleResult"),
      route("ChangeAccountRole", "authorization", ActorMode.ACCOUNT, "ChangeAccountRoleResult"),
      route("GrantSystemAccess", "authorization", ActorMode.ACCOUNT, "GrantSystemAccessResult"),
      route("SuspendSystemAccess", "authorization", ActorMode.ACCOUNT, "SuspendSystemAccessResult"),
      route("RevokeSystemAccess", "authorization", ActorMode.ACCOUNT, "RevokeSystemAccessResult"),
      route("RestoreSystemAccess", "authorization", ActorMode.ACCOUNT, "RestoreSystemAccessResult"),

      route("CreateStudentAcademicProfile", "academic", ActorMode.ACCOUNT,
          "CreateStudentAcademicProfileResult"),
      route("CorrectStudentNumber", "academic", ActorMode.ACCOUNT, "CorrectStudentNumberResult"),
      route("ChangeStudentSemester", "academic", ActorMode.ACCOUNT, "ChangeStudentSemesterResult"),
      route("ActivateStudentAcademicProfile", "academic", ActorMode.ACCOUNT,
          "ActivateStudentAcademicProfileResult"),
      route("DeactivateStudentAcademicProfile", "academic", ActorMode.ACCOUNT,
          "DeactivateStudentAcademicProfileResult"),
      route("CreateAndActivateAcademicPeriod", "academic", ActorMode.ACCOUNT,
          "CreateAndActivateAcademicPeriodResult"),
      route("RegisterCourseSection", "academic", ActorMode.ACCOUNT, "RegisterCourseSectionResult"),
      route("UpdateCourseSection", "academic", ActorMode.ACCOUNT, "UpdateCourseSectionResult"),
      route("ActivateCourseSection", "academic", ActorMode.ACCOUNT, "ActivateCourseSectionResult"),
      route("DeactivateCourseSection", "academic", ActorMode.ACCOUNT,
          "DeactivateCourseSectionResult"),
      route("AddStudentEnrollment", "academic", ActorMode.ACCOUNT, "AddStudentEnrollmentResult"),
      route("ReactivateStudentEnrollment", "academic", ActorMode.ACCOUNT,
          "ReactivateStudentEnrollmentResult"),
      route("RemoveStudentEnrollment", "academic", ActorMode.ACCOUNT,
          "RemoveStudentEnrollmentResult"),
      route("StartStudentImport", "academic", ActorMode.ACCOUNT, "StartStudentImportResult"),
      route("ResumeStudentImport", "academic", ActorMode.ACCOUNT, "ResumeStudentImportResult"),

      route("RegisterInboxVisit", "communications", ActorMode.ACCOUNT,
          "RegisterInboxVisitSucceeded", "RegisterInboxVisitRejected"),
      route("RetryEmailDelivery", "communications", ActorMode.ACCOUNT,
          "RetryEmailDeliverySucceeded", "RetryEmailDeliveryRejected"),
      route("ResolveIndeterminateEmailDelivery", "communications", ActorMode.ACCOUNT,
          "ResolveIndeterminateEmailDeliverySucceeded", "ResolveIndeterminateEmailDeliveryRejected"),

      route("CreateServiceCalendar", "wash-administration", ActorMode.ACCOUNT,
          "CreateServiceCalendarResult"),
      route("UpdateServiceCalendar", "wash-administration", ActorMode.ACCOUNT,
          "UpdateServiceCalendarResult"),
      route("ActivateServiceCalendar", "wash-administration", ActorMode.ACCOUNT,
          "ActivateServiceCalendarResult"),
      route("DeactivateServiceCalendar", "wash-administration", ActorMode.ACCOUNT,
          "DeactivateServiceCalendarResult"),
      route("ConfigureServiceSchedule", "wash-administration", ActorMode.ACCOUNT,
          "ConfigureServiceScheduleResult"),
      route("UpdateServiceSchedule", "wash-administration", ActorMode.ACCOUNT,
          "UpdateServiceScheduleResult"),
      route("DeactivateServiceSchedule", "wash-administration", ActorMode.ACCOUNT,
          "DeactivateServiceScheduleResult"),
      route("ScheduleAdministrativeClosure", "wash-administration", ActorMode.ACCOUNT,
          "ScheduleAdministrativeClosureResult"),
      route("CancelAdministrativeClosure", "wash-administration", ActorMode.ACCOUNT,
          "CancelAdministrativeClosureResult"),
      route("RegisterCabin", "wash-administration", ActorMode.ACCOUNT, "RegisterCabinResult"),
      route("UpdateCabin", "wash-administration", ActorMode.ACCOUNT, "UpdateCabinResult"),
      route("ActivateCabin", "wash-administration", ActorMode.ACCOUNT, "ActivateCabinResult"),
      route("DeactivateCabin", "wash-administration", ActorMode.ACCOUNT, "DeactivateCabinResult"),
      route("RetireCabin", "wash-administration", ActorMode.ACCOUNT, "RetireCabinResult"),
      route("RegisterTank", "wash-administration", ActorMode.ACCOUNT, "RegisterTankResult"),
      route("UpdateTank", "wash-administration", ActorMode.ACCOUNT, "UpdateTankResult"),
      route("ActivateTank", "wash-administration", ActorMode.ACCOUNT, "ActivateTankResult"),
      route("DeactivateTank", "wash-administration", ActorMode.ACCOUNT, "DeactivateTankResult"),
      route("RetireTank", "wash-administration", ActorMode.ACCOUNT, "RetireTankResult"),

      route("ScheduleAppointment", "appointments", ActorMode.ACCOUNT,
          "ScheduleAppointmentSucceeded", "ScheduleAppointmentRejected"),
      route("CancelAppointment", "appointments", ActorMode.ACCOUNT,
          "CancelAppointmentSucceeded", "CancelAppointmentRejected"),
      route("GrantExceptionalAppointmentAuthorization", "appointments", ActorMode.ACCOUNT,
          "GrantExceptionalAppointmentAuthorizationSucceeded",
          "GrantExceptionalAppointmentAuthorizationRejected"),
      route("CancelExceptionalAppointmentAuthorization", "appointments", ActorMode.ACCOUNT,
          "CancelExceptionalAppointmentAuthorizationSucceeded",
          "CancelExceptionalAppointmentAuthorizationRejected"),
      route("CreateAppointmentTimeSlot", "appointments", ActorMode.ACCOUNT,
          "CreateAppointmentTimeSlotSucceeded", "CreateAppointmentTimeSlotRejected"),

      route("RegisterWashArrival", "wash-execution", ActorMode.ACCOUNT,
          "RegisterWashArrivalSucceeded", "RegisterWashArrivalRejected"),
      route("DecideWashEntry", "wash-execution", ActorMode.ACCOUNT,
          "DecideWashEntrySucceeded", "DecideWashEntryRejected"),
      route("ResolvePendingReassignment", "wash-execution", ActorMode.ACCOUNT,
          "ResolvePendingReassignmentSucceeded", "ResolvePendingReassignmentRejected"),
      route("DisableOperationalResource", "wash-execution", ActorMode.ACCOUNT,
          "DisableOperationalResourceSucceeded", "DisableOperationalResourceRejected"),
      route("RestoreOperationalResource", "wash-execution", ActorMode.ACCOUNT,
          "RestoreOperationalResourceSucceeded", "RestoreOperationalResourceRejected"),
      route("SubmitWashExit", "wash-execution", ActorMode.ACCOUNT,
          "SubmitWashExitSucceeded", "SubmitWashExitRejected"),
      route("CompleteWashExecution", "wash-execution", ActorMode.ACCOUNT,
          "CompleteWashExecutionSucceeded", "CompleteWashExecutionRejected"),
      route("CancelWashExecutionByClinic", "wash-execution", ActorMode.ACCOUNT,
          "CancelWashExecutionByClinicSucceeded", "CancelWashExecutionByClinicRejected"),

      route("PublicarJornada", "jornadas", ActorMode.ACCOUNT, "PublicarJornadaResult"),
      route("EditarJornada", "jornadas", ActorMode.ACCOUNT, "EditarJornadaResult"),
      route("CancelarJornada", "jornadas", ActorMode.ACCOUNT, "CancelarJornadaResult"),
      route("ConfigurarTipoJornada", "jornadas", ActorMode.ACCOUNT, "ConfigurarTipoJornadaResult"),

      route("CrearRegistro", "registros", ActorMode.ACCOUNT, "CrearRegistroResult"),
      route("ConfirmarRegistro", "registros", ActorMode.ACCOUNT, "ConfirmarRegistroResult"),
      route("RechazarRegistro", "registros", ActorMode.ACCOUNT, "RechazarRegistroResult"),
      route("CancelarRegistroPorAlumno", "registros", ActorMode.ACCOUNT,
          "CancelarRegistroPorAlumnoResult"));

  public OwnerRoute require(String messageType) {
    OwnerRoute route = ROUTES.get(messageType);
    if (route == null) {
      throw new IllegalArgumentException("message type is not allowlisted: " + messageType);
    }
    return route;
  }

  public Set<String> messageTypes() {
    return ROUTES.keySet();
  }

  public boolean isKnownResultType(String messageType) {
    return messageType != null && ROUTES.values().stream()
        .anyMatch(route -> route.resultMessageVersions().containsKey(messageType));
  }

  private static Map.Entry<String, OwnerRoute> route(String messageType, String owner,
      ActorMode actorMode, String... resultTypes) {
    Map<String, Integer> versions = new LinkedHashMap<>();
    for (String resultType : resultTypes) {
      versions.put(resultType, 1);
    }
    return Map.entry(messageType, new OwnerRoute(owner, owner, 1, versions, actorMode));
  }
}
