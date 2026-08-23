package com.estoma.washexecution.contracts;

import java.util.UUID;

public record Actor(String type, UUID accountId, String service) {}
