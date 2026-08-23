package com.estoma.platformbff.application.port;

public interface StructuredDataComparator {
  boolean equivalent(String firstJson, String secondJson);
}
