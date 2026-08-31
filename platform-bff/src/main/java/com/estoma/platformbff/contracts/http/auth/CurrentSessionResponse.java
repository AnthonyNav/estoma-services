package com.estoma.platformbff.contracts.http.auth;

import com.estoma.platformbff.contracts.http.Required;
import java.util.List;
import java.util.UUID;

/** Closed V1 context for the authenticated normal session. */
public record CurrentSessionResponse(
    @Required UUID accountId,
    @Required UUID personId,
    @Required String displayName,
    @Required String loginType,
    @Required String loginIdentifier,
    @Required String institutionalEmail,
    @Required String roleCode,
    @Required List<String> availableSystemCodes) {}
