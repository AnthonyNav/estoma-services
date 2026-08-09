package com.estoma.communications.application;

import com.estoma.communications.contracts.Actor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import com.estoma.communications.contracts.ActorType;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;

/**
 * v1 default: every actor is authorized for every command. The concrete
 * allow-list (which microservices/processes may issue {@code
 * RequestCommunication}, which BFF operation issues {@code
 * RegisterInboxVisit}, who may issue {@code RetryEmailDelivery}) is not
 * fixed by the platform docs yet -- same YAGNI stance {@code
 * authentication-service} took with its own {@code
 * AllowAllAccountAuthorizationPolicy}.
 */
@Component
public class AllowAllCommunicationsAuthorizationPolicy implements CommunicationsAuthorizationPolicy {
  private final Set<String> administrativeServices;

  public AllowAllCommunicationsAuthorizationPolicy(@Value("${estoma.communications.admin.system-allowlist:bff,communications-admin}") String allowlist) {
    administrativeServices = Arrays.stream(allowlist.split(",")).map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public void check(Actor actor, String messageType) {
    if (messageType.equals(CommunicationsCommand.RETRY_EMAIL_DELIVERY.wireName())
        || messageType.equals(CommunicationsCommand.RESOLVE_INDETERMINATE_EMAIL_DELIVERY.wireName())) {
      if (actor == null || actor.type() != ActorType.SYSTEM || !administrativeServices.contains(actor.service()))
        throw new CommunicationsRejectedException(RejectionCode.ACTOR_NOT_AUTHORIZED, "administrative command actor is not allowed");
    }
  }
}
