package com.estoma.washadministration.contracts.consumed;

import java.util.UUID;

public record AuthorizationAccessSnapshot(
    UUID systemAccessId,
    UUID systemId,
    String systemCode,
    String status,
    int accessVersion) {}
