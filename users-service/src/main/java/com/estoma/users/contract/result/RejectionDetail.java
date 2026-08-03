package com.estoma.users.contract.result;

/** One field-level detail attached to a {@link RejectedPayload}. */
public record RejectionDetail(String field, String code) {}
