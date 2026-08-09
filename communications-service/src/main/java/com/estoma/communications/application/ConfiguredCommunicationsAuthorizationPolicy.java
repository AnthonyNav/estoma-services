package com.estoma.communications.application;

import com.estoma.communications.contracts.Actor;
import com.estoma.communications.contracts.ActorType;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Authorization policy for administrative communication commands. */
@Component
public class ConfiguredCommunicationsAuthorizationPolicy implements CommunicationsAuthorizationPolicy {
  private final Set<String> administrativeServices;

  public ConfiguredCommunicationsAuthorizationPolicy(@Value("${estoma.communications.admin.system-allowlist:}") String allowlist) {
    administrativeServices = Arrays.stream(allowlist == null ? new String[0] : allowlist.split(","))
        .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public void check(Actor actor, String messageType) {
    if (messageType.equals(CommunicationsCommand.RETRY_EMAIL_DELIVERY.wireName())
        || messageType.equals(CommunicationsCommand.RESOLVE_INDETERMINATE_EMAIL_DELIVERY.wireName())) {
      if (actor == null || actor.type() != ActorType.SYSTEM || !administrativeServices.contains(actor.service())) {
        throw new CommunicationsRejectedException(RejectionCode.ACTOR_NOT_AUTHORIZED, "administrative command actor is not allowed");
      }
    }
  }
}
