package com.estoma.authentication.contracts.rpc;

/**
 * Response for {@code ValidateRecoveryCode} (commands.md §8.4): {@code
 * result} is one of {@code VALID}, {@code INVALID}, {@code EXPIRED}. Never
 * reveals additional detail about the account.
 */
public record ValidateRecoveryCodeResponse(String result) {}
