package com.estoma.washadministration.infrastructure.messaging.inbound;

import tools.jackson.databind.JsonNode;

public record AuthorizationRecoveryResultPayload(String status, JsonNode data, JsonNode error) {}
