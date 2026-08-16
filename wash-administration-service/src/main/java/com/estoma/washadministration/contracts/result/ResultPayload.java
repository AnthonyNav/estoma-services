package com.estoma.washadministration.contracts.result;

import tools.jackson.databind.JsonNode;

public record ResultPayload(ResultStatus status, JsonNode data, ErrorPayload error) {}
