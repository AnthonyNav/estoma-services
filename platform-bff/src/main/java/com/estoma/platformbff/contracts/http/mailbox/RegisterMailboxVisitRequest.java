package com.estoma.platformbff.contracts.http.mailbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record RegisterMailboxVisitRequest(
    @NotBlank @Size(max = 160) String systemCode,
    @NotNull Instant visitedAt) {}
