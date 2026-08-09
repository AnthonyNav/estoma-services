package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.domain.communication.Communication;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation of {@link CommunicationRepository}. {@link #insert}
 * runs under {@code Propagation.NESTED} for the same reason {@code
 * authentication-service}'s {@code JdbcAccountRepository} does: a
 * constraint-violation-translated rejection must not abort the enclosing
 * dispatcher transaction, since both the durable command path (which
 * appends a Rejected result) and the {@code AppointmentCancelled} adapter
 * path (which treats the conflict as an idempotent no-op) need to keep
 * writing in the same transaction right after catching it.
 */
@Repository
class JdbcCommunicationRepository implements CommunicationRepository {

  private static final String COLUMNS =
      """
      communication_id, recipient_account_id, system_code, communication_type, priority,
      source_service, source_reference_type, source_reference_id, initiated_by_account_id,
       status, created_at, completed_at, failed_at, requested_channels, source_message_id, idempotency_key,
       request_fingerprint, correlation_id, causation_id
      """;

  private final JdbcClient jdbc;

  JdbcCommunicationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Communication> findById(UUID communicationId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM communications WHERE communication_id = :communicationId")
        .param("communicationId", communicationId)
        .query(CommunicationRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<Communication> findBySourceAndIdempotency(String sourceService, UUID idempotencyKey) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM communications WHERE source_service = :sourceService AND idempotency_key = :idempotencyKey")
        .param("sourceService", sourceService).param("idempotencyKey", idempotencyKey.toString()).query(CommunicationRowMapper.INSTANCE).optional();
  }

  @Override
  public Optional<Communication> findLegacyByOrigin(Communication c) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM communications WHERE idempotency_key IS NULL AND source_service = :sourceService AND source_reference_type = :sourceType AND source_reference_id = :sourceId AND recipient_account_id = :recipient AND system_code = :system AND communication_type = :type")
        .param("sourceService", c.sourceService()).param("sourceType", c.sourceReferenceType()).param("sourceId", c.sourceReferenceId())
        .param("recipient", c.recipientAccountId()).param("system", c.systemCode()).param("type", c.communicationType())
        .query(CommunicationRowMapper.INSTANCE).optional();
  }

  @Override
  @Transactional(propagation = Propagation.NESTED)
  public void insert(Communication communication) {
    try {
      if (communication.idempotencyKey() == null && findLegacyByOrigin(communication).isPresent())
        throw new CommunicationsRejectedException(RejectionCode.COMMUNICATION_ALREADY_EXISTS, "legacy communication origin already exists");
      jdbc.sql(
              """
              INSERT INTO communications (
                communication_id, recipient_account_id, system_code, communication_type, priority,
                source_service, source_reference_type, source_reference_id, initiated_by_account_id,
                 status, created_at, completed_at, failed_at, requested_channels, source_message_id, idempotency_key,
                 request_fingerprint, correlation_id, causation_id
              ) VALUES (
                :communicationId, :recipientAccountId, :systemCode, :communicationType, CAST(:priority AS communication_priority),
                :sourceService, :sourceReferenceType, :sourceReferenceId, :initiatedByAccountId,
                 CAST(:status AS communication_status), :createdAt, :completedAt, :failedAt, :requestedChannels,
                 :sourceMessageId, :idempotencyKey, :requestFingerprint, :correlationId, :causationId
              )
              """)
          .param("communicationId", communication.communicationId())
          .param("recipientAccountId", communication.recipientAccountId())
          .param("systemCode", communication.systemCode())
          .param("communicationType", communication.communicationType())
          .param("priority", communication.priority().name())
          .param("sourceService", communication.sourceService())
          .param("sourceReferenceType", communication.sourceReferenceType())
          .param("sourceReferenceId", communication.sourceReferenceId())
          .param("initiatedByAccountId", communication.initiatedByAccountId())
          .param("status", communication.status().name())
          .param("createdAt", Timestamps.toTimestamp(communication.createdAt()))
          .param("completedAt", Timestamps.toTimestamp(communication.completedAt()))
          .param("failedAt", Timestamps.toTimestamp(communication.failedAt()))
          .param("requestedChannels", communication.requestedChannels().toArray(String[]::new))
          .param("sourceMessageId", communication.sourceMessageId())
           .param("idempotencyKey", communication.idempotencyKey() == null ? null : communication.idempotencyKey().toString())
          .param("requestFingerprint", communication.requestFingerprint())
          .param("correlationId", communication.correlationId())
          .param("causationId", communication.causationId())
          .update();
    } catch (DuplicateKeyException e) {
      throw new CommunicationsRejectedException(
          RejectionCode.COMMUNICATION_ALREADY_EXISTS, "a communication with this origin, recipient, system, and type already exists");
    }
  }

  @Override
  public void update(Communication communication) {
    jdbc.sql(
            """
            UPDATE communications SET
              status = CAST(:status AS communication_status),
              completed_at = :completedAt,
              failed_at = :failedAt
            WHERE communication_id = :communicationId
            """)
        .param("status", communication.status().name())
        .param("completedAt", Timestamps.toTimestamp(communication.completedAt()))
        .param("failedAt", Timestamps.toTimestamp(communication.failedAt()))
        .param("communicationId", communication.communicationId())
        .update();
  }
}
