package com.estoma.users.domain;

/**
 * Global status of a {@link Person}, independent of any account, session,
 * role, or academic status those belong to other services.
 *
 * <p>Source: estomatologia-platform-docs
 * 03-services/shared/users-service/data-model.md §5, §8.
 */
public enum PersonStatus {
  ACTIVE,
  INACTIVE
}
