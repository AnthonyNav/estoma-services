package com.estoma.authentication.application.projection;

import com.estoma.authentication.contracts.MessageEnvelope;
import java.util.List;

/**
 * One handler per group of consumed event {@code messageType}s that update
 * the same local projection. A handler may register for more than one
 * message type (e.g. {@code PersonCreated}/{@code PersonActivated}/{@code
 * PersonDeactivated} all update {@code person_status_projection} the same
 * way). Unlike {@code CommandHandler}, implementations do not throw a
 * functional rejection: events have no result channel to reject into; a
 * definitive conflict is instead recorded as its own event (e.g. {@code
 * StudentLoginIdentifierSynchronizationFailed}).
 */
public interface ProjectionHandler {

  List<String> consumedMessageTypes();

  ProjectionHandlerResult handle(MessageEnvelope envelope);
}
