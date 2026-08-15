package com.estoma.authorization.application.projection;

public sealed interface ProjectionEvent
    permits PersonProjectionEvent, AccountProjectionEvent, AcademicEligibilityProjectionEvent {

  ProjectionEventMetadata metadata();

  String payloadJson();

  boolean causallyApplicable();
}
