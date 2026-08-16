package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record ServiceCalendarDeactivatedPayload(UUID calendarId, String status, int version, Instant occurredAt) {}
