package com.estoma.communications.application;

import com.estoma.communications.contracts.Actor;
import org.springframework.stereotype.Component;

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

  @Override
  public void check(Actor actor, String messageType) {}
}
