package com.estoma.washexecution.application.query;

import com.estoma.washexecution.application.command.ActorContext;
import java.util.UUID;

public record IntegrationQueryMetadata(UUID messageId,String messageType,String producer,UUID correlationId,
    UUID operationId,ActorContext actor) {}
