package com.estoma.authentication.contracts.rpc;

import com.estoma.authentication.contracts.LoginType;

/** Request for {@code AuthenticateAccount} (commands.md §8.1). Device context fields are optional. */
public record AuthenticateAccountRequest(
    LoginType loginType, String loginIdentifier, String password, String deviceName, String ipAddress, String userAgent) {}
