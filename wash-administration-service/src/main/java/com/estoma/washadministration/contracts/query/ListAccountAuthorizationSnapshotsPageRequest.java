package com.estoma.washadministration.contracts.query;

public record ListAccountAuthorizationSnapshotsPageRequest(String cursor, int limit, String systemCode) {}
