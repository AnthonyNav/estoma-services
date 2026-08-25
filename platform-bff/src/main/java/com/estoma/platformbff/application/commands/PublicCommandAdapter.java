package com.estoma.platformbff.application.commands;

import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.CommandSubmission;
import com.estoma.platformbff.contracts.messaging.CommandSubmissionResult;
import com.estoma.platformbff.application.port.MessageSerializer;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Maps a validated public HTTP intent to exactly one allowlisted owner command. */
@Service
public class PublicCommandAdapter {
  private static final String ACCOUNT_SCOPE = "GLOBAL";

  private final CommandGatewayService gateway;
  private final MessageSerializer serializer;

  public PublicCommandAdapter(CommandGatewayService gateway, MessageSerializer serializer) {
    this.gateway = gateway;
    this.serializer = serializer;
  }

  public AcceptedOperationResponse submit(UUID actorAccountId, String publicOperation,
      String commandType, Map<String, ?> payload, String idempotencyKey, UUID aggregateId,
      Integer aggregateVersion) {
    CommandSubmissionResult submitted = gateway.submit(new CommandSubmission(
        publicOperation, commandType, write(payload), idempotencyKey, actorAccountId,
        ActorContext.account(actorAccountId, ACCOUNT_SCOPE, null), aggregateId, aggregateVersion));
    return new AcceptedOperationResponse(submitted.operationId(), submitted.status(),
        submitted.pollPath(), submitted.submittedAt(), submitted.reused());
  }

  private String write(Map<String, ?> payload) {
    return serializer.write(payload);
  }
}
