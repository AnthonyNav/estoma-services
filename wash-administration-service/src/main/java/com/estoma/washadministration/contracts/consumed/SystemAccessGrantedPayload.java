package com.estoma.washadministration.contracts.consumed;

import java.time.Instant;
import java.util.UUID;

public record SystemAccessGrantedPayload(
    UUID systemAccessId,
    UUID accountId,
    UUID systemId,
    String systemCode,
    UUID roleId,
    String roleCode,
    String status,
    Instant grantedAt) {}
