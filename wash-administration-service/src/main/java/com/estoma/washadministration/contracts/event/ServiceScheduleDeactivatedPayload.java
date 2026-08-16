package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record ServiceScheduleDeactivatedPayload(UUID scheduleId, String status, int version, Instant occurredAt) {}
