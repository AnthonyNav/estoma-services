package com.estoma.communications.application;

import com.estoma.communications.contracts.Actor;
import com.estoma.communications.domain.CommunicationsRejectedException;

/**
 * Checked by {@link CommandDispatcher} before dispatching to a handler.
 * {@code RequestCommunication} must come from a {@code SYSTEM} actor;
 * {@code RegisterInboxVisit} from the BFF; {@code RetryEmailDelivery} from
 * an authorized administrative operation; {@code ExpireInboxMessages} from
 * the service's own scheduler. The concrete allow-list of authorized
 * callers per command is configured by deployment (see {@link
 * ConfiguredCommunicationsAuthorizationPolicy}).
 *
 * @throws CommunicationsRejectedException with {@code ACTOR_NOT_AUTHORIZED} if the actor may not
 *     perform {@code messageType}.
 */
public interface CommunicationsAuthorizationPolicy {

  void check(Actor actor, String messageType);
}
