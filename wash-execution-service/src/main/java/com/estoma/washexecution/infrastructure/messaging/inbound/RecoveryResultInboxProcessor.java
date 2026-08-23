package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.port.ProcessedMessageRepository;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.MessagingProperties;
import com.estoma.washexecution.infrastructure.messaging.recovery.RecoveryResultProcessor;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class RecoveryResultInboxProcessor {
  private static final String CONSUMER="wash-execution.results";
  private final ProcessedMessageRepository inbox; private final RecoveryResultProcessor recovery; private final EnvelopeJsonCodec codec;
  private final MessagingProperties properties; private final TransactionTemplate transactions;
  public RecoveryResultInboxProcessor(ProcessedMessageRepository inbox,RecoveryResultProcessor recovery,EnvelopeJsonCodec codec,MessagingProperties properties,PlatformTransactionManager manager){this.inbox=inbox;this.recovery=recovery;this.codec=codec;this.properties=properties;this.transactions=new TransactionTemplate(manager);}
  public void process(MessageEnvelope e){transactions.executeWithoutResult(s->{
    var existing=inbox.find(CONSUMER,e.messageId()); if(existing.isPresent())return; Instant now=Instant.now();
    inbox.insertIfAbsent(new ProcessedMessageRepository.ProcessedMessage(CONSUMER,e.messageId(),ProcessedMessageRepository.Status.DEFERRED,e.messageType(),e.messageVersion(),e.messageKind().name(),e.producer(),e.correlationId(),e.causationId(),e.operationId(),e.idempotencyKey(),e.aggregateId(),e.aggregateVersion()==null?null:e.aggregateVersion().longValue(),codec.write(e.actor()),codec.write(e.payload()),now,e.occurredAt(),null,"RESULT_PROCESSING",now));
    UUID claim=UUID.randomUUID(); if(!inbox.claimDeferred(CONSUMER,e.messageId(),now,claim,now.plus(properties.inboxLease())))throw new IllegalStateException("RESULT_INBOX_CLAIM_FAILED");
    recovery.process(e); if(!inbox.markApplied(CONSUMER,e.messageId(),claim,Instant.now()))throw new IllegalStateException("RESULT_INBOX_FENCE_LOST");
  });}
}
