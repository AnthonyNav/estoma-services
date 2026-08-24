package com.estoma.platformbff.infrastructure.security;

import com.estoma.platformbff.application.port.CurrentActor;
import com.estoma.platformbff.application.security.CurrentSecurityPrincipal;
import com.estoma.platformbff.application.security.CurrentSecurityPrincipal.Principal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentActor implements CurrentActor, CurrentSecurityPrincipal {
  @Override
  public Optional<UUID> accountId() {
    return current().map(Principal::accountId);
  }

  @Override
  public Optional<Principal> current() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return Optional.empty();
    }
    return authentication.getPrincipal() instanceof Principal principal
        ? Optional.of(principal)
        : Optional.empty();
  }
}
