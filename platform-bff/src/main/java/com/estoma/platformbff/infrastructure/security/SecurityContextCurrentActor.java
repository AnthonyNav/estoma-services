package com.estoma.platformbff.infrastructure.security;

import com.estoma.platformbff.application.port.CurrentActor;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentActor implements CurrentActor {
  @Override
  public Optional<UUID> accountId() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(authentication.getName()));
    } catch (IllegalArgumentException invalidPrincipal) {
      return Optional.empty();
    }
  }
}
