# Platform BFF — Admin HTTP Contract V1

This is the BFF-side copy of the canonical Admin HTTP Contract V1 in
`estomatologia-platform-docs#51`. The executable source of truth is
`AdminController.routeMatrix()`: it owns the method/path → public operation → owner command
mapping, its client-oriented request schema and its authorization scope. The provider test checks
every route against `CommandRegistry` to prevent route-to-owner drift.

Every listed route is a durable command: it requires `Bearer` authorization and
`Idempotency-Key`, returns `202 Accepted`, and persists only one owner command. Non-wash routes
require `SUPERADMIN`; wash administration requires `ADMINISTRADOR_LAVADO` (or `SUPERADMIN`) plus
active `LAVADO_ULTRASONICO` access. The BFF rejects undeclared request fields before it injects
path IDs into the owner payload.

| Area | Method and path | publicOperation | Owner operation |
| --- | --- | --- | --- |
| Users | `POST /people` | `admin.people.create` | CreatePerson |
| Users | `PATCH /people/{personId}` | `admin.people.update` | UpdatePerson |
| Users | `POST /people/{personId}/activate`, `/deactivate` | `admin.people.activate`, `admin.people.deactivate` | ActivatePerson, DeactivatePerson |
| Authentication | `POST /accounts` | `admin.accounts.create` | CreateAccount |
| Authentication | `POST /accounts/{accountId}/activate`, `/deactivate` | `admin.accounts.activate`, `admin.accounts.deactivate` | ActivateAccount, DeactivateAccount |
| Authentication | `PATCH /accounts/{accountId}/login-identifier` | `admin.accounts.change-login-identifier` | ChangeLoginIdentifier |
| Authentication | `POST /accounts/{accountId}/sessions/revoke-all` | `admin.accounts.revoke-all-sessions` | RevokeAllAccountSessions |
| Authorization | `POST /authorization/accounts/{accountId}/role` | `admin.authorization.assign-role` | AssignAccountRole |
| Authorization | `PATCH /authorization/accounts/{accountId}/role` | `admin.authorization.change-role` | ChangeAccountRole |
| Authorization | `POST /authorization/accounts/{accountId}/system-accesses` | `admin.authorization.grant-system-access` | GrantSystemAccess |
| Authorization | `POST /authorization/system-accesses/{systemAccessId}/suspend`, `/revoke`, `/restore` | `admin.authorization.suspend-system-access`, `admin.authorization.revoke-system-access`, `admin.authorization.restore-system-access` | SuspendSystemAccess, RevokeSystemAccess, RestoreSystemAccess |
| Academic | `POST /academic/students` | `admin.academic.students.create` | CreateStudentAcademicProfile |
| Academic | `PATCH /academic/students/{studentAcademicProfileId}/student-number`, `/semester` | `admin.academic.students.correct-number`, `admin.academic.students.change-semester` | CorrectStudentNumber, ChangeStudentSemester |
| Academic | `POST /academic/students/{studentAcademicProfileId}/activate`, `/deactivate` | `admin.academic.students.activate`, `admin.academic.students.deactivate` | ActivateStudentAcademicProfile, DeactivateStudentAcademicProfile |
| Academic | `POST /academic/periods` | `admin.academic.periods.create-and-activate` | CreateAndActivateAcademicPeriod |
| Academic | `POST /academic/course-sections`; `PATCH /academic/course-sections/{courseSectionId}` | `admin.academic.course-sections.register`, `admin.academic.course-sections.update` | RegisterCourseSection, UpdateCourseSection |
| Academic | `POST /academic/course-sections/{courseSectionId}/activate`, `/deactivate` | `admin.academic.course-sections.activate`, `admin.academic.course-sections.deactivate` | ActivateCourseSection, DeactivateCourseSection |
| Academic | `POST /academic/students/{studentAcademicProfileId}/enrollments`; `POST /academic/enrollments/{studentEnrollmentId}/reactivate`; `DELETE /academic/enrollments/{studentEnrollmentId}` | `admin.academic.enrollments.add`, `admin.academic.enrollments.reactivate`, `admin.academic.enrollments.remove` | AddStudentEnrollment, ReactivateStudentEnrollment, RemoveStudentEnrollment |
| Academic | `POST /academic/imports`, `/academic/imports/{importId}/resume` | `admin.academic.imports.start`, `admin.academic.imports.resume` | StartStudentImport, ResumeStudentImport |
| Communications | `POST /communications/email-deliveries/{emailDeliveryId}/retry`, `/resolve-indeterminate` | `admin.communications.email-deliveries.retry`, `admin.communications.email-deliveries.resolve-indeterminate` | RetryEmailDelivery, ResolveIndeterminateEmailDelivery |
| Wash admin | `POST /wash/calendars`; `PATCH /wash/calendars/{serviceCalendarId}`; `POST /activate`, `/deactivate` | `admin.wash.calendars.*` | Create/Update/Activate/DeactivateServiceCalendar |
| Wash admin | `POST /wash/calendars/{serviceCalendarId}/schedules`; `PATCH /wash/schedules/{serviceScheduleId}`; `POST /deactivate` | `admin.wash.schedules.*` | Configure/Update/DeactivateServiceSchedule |
| Wash admin | `POST /wash/administrative-closures`, `/{administrativeClosureId}/cancel` | `admin.wash.closures.*` | ScheduleAdministrativeClosure, CancelAdministrativeClosure |
| Wash admin | `POST /wash/cabins`; `PATCH /wash/cabins/{cabinId}`; `POST /activate`, `/deactivate`, `/retire` | `admin.wash.cabins.*` | Register/Update/Activate/Deactivate/RetireCabin |
| Wash admin | `POST /wash/cabins/{cabinId}/tanks`; `PATCH /wash/tanks/{tankId}`; `POST /activate`, `/deactivate`, `/retire` | `admin.wash.tanks.*` | Register/Update/Activate/Deactivate/RetireTank |

## Security RPC exceptions

`POST /accounts/{accountId}/temporary-password` and
`POST /accounts/{accountId}/temporary-password/regenerate` require `SUPERADMIN` and
`Idempotency-Key`, but are deliberately not durable operations: they call the allowlisted
Authentication Security RPC synchronously. Responses use `Cache-Control: no-store` and
`Pragma: no-cache`; the temporary secret is neither persisted nor logged by the BFF.
