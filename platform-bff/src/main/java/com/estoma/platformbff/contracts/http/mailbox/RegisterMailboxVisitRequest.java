package com.estoma.platformbff.contracts.http.mailbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterMailboxVisitRequest(
    @NotBlank @Size(max = 160) String systemCode) {}
