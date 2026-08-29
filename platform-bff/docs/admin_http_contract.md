# Platform BFF — Admin HTTP Contract V1

This document closes the concrete `/api/v1/admin/**` surface implemented by PR6. Every route is
`POST`, requires a normal authenticated JWT and `Idempotency-Key`, sends one durable owner Command,
and returns the canonical `202 Accepted` operation response. The JSON body contains only the
owner-defined functional fields; path identifiers are injected by the BFF and override any body
duplicate. No route exposes AMQP metadata or chains multiple Commands.

The BFF authorizes this surface against its local authorization projection before accepting a
Command: routes outside `/admin/wash/**` require `SUPERADMIN`; wash-administration routes require
`ADMINISTRADOR_LAVADO` (or `SUPERADMIN`) plus an active `LAVADO_ULTRASONICO` access. The owner is
still the authority for the specific static `wash.admin.*` permission and its domain invariants.

| Route | Command | Owner |
| --- | --- | --- |
| `/persons` | CreatePerson | users |
| `/persons/{personId}/update`, `/activate`, `/deactivate` | UpdatePerson, ActivatePerson, DeactivatePerson | users |
| `/accounts` | CreateAccount | authentication |
| `/accounts/{accountId}/activate`, `/deactivate`, `/login-identifier`, `/revoke-sessions` | ActivateAccount, DeactivateAccount, ChangeLoginIdentifier, RevokeAllAccountSessions | authentication |
| `/accounts/{accountId}/roles`, `/role` | AssignAccountRole, ChangeAccountRole | authorization |
| `/accounts/{accountId}/accesses` | GrantSystemAccess | authorization |
| `/accounts/{accountId}/accesses/{systemAccessId}/suspend`, `/revoke`, `/restore` | SuspendSystemAccess, RevokeSystemAccess, RestoreSystemAccess | authorization |
| `/students` | CreateStudentAcademicProfile | academic |
| `/students/{accountId}/student-number`, `/semester`, `/activate`, `/deactivate` | CorrectStudentNumber, ChangeStudentSemester, ActivateStudentAcademicProfile, DeactivateStudentAcademicProfile | academic |
| `/academic-periods` | CreateAndActivateAcademicPeriod | academic |
| `/course-sections`, `/{courseSectionId}/update`, `/{courseSectionId}/activate`, `/{courseSectionId}/deactivate` | RegisterCourseSection, UpdateCourseSection, ActivateCourseSection, DeactivateCourseSection | academic |
| `/students/{accountId}/enrollments`, `/{courseSectionId}/reactivate`, `/{courseSectionId}/remove` | AddStudentEnrollment, ReactivateStudentEnrollment, RemoveStudentEnrollment | academic |
| `/student-imports`, `/{importId}/resume` | StartStudentImport, ResumeStudentImport | academic |
| `/mail-deliveries/{emailDeliveryId}/retry`, `/resolve` | RetryEmailDelivery, ResolveIndeterminateEmailDelivery | communications |
| `/wash/calendars`, `/{calendarId}/update`, `/{calendarId}/activate`, `/{calendarId}/deactivate` | CreateServiceCalendar, UpdateServiceCalendar, ActivateServiceCalendar, DeactivateServiceCalendar | wash-administration |
| `/wash/schedules`, `/{scheduleId}/update`, `/{scheduleId}/deactivate` | ConfigureServiceSchedule, UpdateServiceSchedule, DeactivateServiceSchedule | wash-administration |
| `/wash/closures`, `/{closureId}/cancel` | ScheduleAdministrativeClosure, CancelAdministrativeClosure | wash-administration |
| `/wash/cabins`, `/{cabinId}/update`, `/{cabinId}/activate`, `/{cabinId}/deactivate`, `/{cabinId}/retire` | RegisterCabin, UpdateCabin, ActivateCabin, DeactivateCabin, RetireCabin | wash-administration |
| `/wash/tanks`, `/{tankId}/update`, `/{tankId}/activate`, `/{tankId}/deactivate`, `/{tankId}/retire` | RegisterTank, UpdateTank, ActivateTank, DeactivateTank, RetireTank | wash-administration |

`RequestPasswordRecovery` and `RegisterInboxVisit` retain their dedicated Auth/Mailbox routes;
they are not duplicated under Admin. Commands internal to owners remain absent.
