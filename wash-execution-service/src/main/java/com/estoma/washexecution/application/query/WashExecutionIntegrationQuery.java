package com.estoma.washexecution.application.query;

import java.time.LocalDate;
import java.util.UUID;

public sealed interface WashExecutionIntegrationQuery permits
    WashExecutionIntegrationQuery.GetContext, WashExecutionIntegrationQuery.ListPage {
  record GetContext(UUID appointmentId) implements WashExecutionIntegrationQuery {}
  record ListPage(LocalDate serviceDate,Integer pageSize,String pageToken) implements WashExecutionIntegrationQuery {}
}
