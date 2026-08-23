package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.PasswordRecoveryDeliveryPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPasswordRecoveryDeliveryRepository implements PasswordRecoveryDeliveryPort {

  private final JdbcClient jdbc;

  JdbcPasswordRecoveryDeliveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(
      UUID emailDeliveryId,
      UUID communicationId,
      UUID recoveryId,
      Instant expiresAt,
      String recipientAddress,
      String recipientName,
      String fromName,
      String fromAddress,
      String senderCode,
      boolean waitingForRecipient,
      Instant now) {
    jdbc.sql("""
        INSERT INTO email_deliveries (
          email_delivery_id,communication_id,recipient_address,recipient_name,
          from_name,from_address,sender_code,content_mode,template_code,
          status,attempt_count,retry_cycle,cycle_attempt_count,
          recovery_id,recovery_expires_at,created_at,updated_at)
        VALUES (
          :delivery,:communication,:address,:name,
          :fromName,:fromAddress,:senderCode,CAST('TEMPLATE' AS email_content_mode),'PASSWORD_RECOVERY',
          CAST(:status AS email_delivery_status),0,1,0,
          :recovery,:expires,:now,:now)
        """)
        .param("delivery", emailDeliveryId)
        .param("communication", communicationId)
        .param("address", recipientAddress)
        .param("name", recipientName)
        .param("fromName", fromName)
        .param("fromAddress", fromAddress)
        .param("senderCode", senderCode)
        .param("status", waitingForRecipient ? "WAITING_FOR_RECIPIENT" : "PENDING")
        .param("recovery", recoveryId)
        .param("expires", Timestamps.toTimestamp(expiresAt))
        .param("now", Timestamps.toTimestamp(now))
        .update();
  }

  @Override
  public Optional<RecoveryMetadata> findMetadata(UUID emailDeliveryId) {
    return jdbc.sql("""
        SELECT email_delivery_id,communication_id,recovery_id,recovery_expires_at
        FROM email_deliveries
        WHERE email_delivery_id=:id AND recovery_id IS NOT NULL
        """)
        .param("id", emailDeliveryId)
        .query((rs, n) -> new RecoveryMetadata(
            rs.getObject(1, UUID.class),
            rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class),
            rs.getTimestamp(4).toInstant()))
        .optional();
  }

  @Override
  public List<WaitingRecovery> waiting(int limit) {
    return jdbc.sql("""
        SELECT d.email_delivery_id,d.communication_id,c.recipient_account_id,d.recovery_expires_at,
               p.institutional_email,p.display_name,p.account_status::text,p.person_status::text
        FROM email_deliveries d
        JOIN communications c ON c.communication_id=d.communication_id
        LEFT JOIN recipient_projection p ON p.account_id=c.recipient_account_id
        WHERE d.status=CAST('WAITING_FOR_RECIPIENT' AS email_delivery_status)
          AND d.recovery_id IS NOT NULL
        ORDER BY d.created_at,d.email_delivery_id
        LIMIT :limit
        """)
        .param("limit", limit)
        .query((rs, n) -> new WaitingRecovery(
            rs.getObject(1, UUID.class),
            rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class),
            rs.getTimestamp(4).toInstant(),
            rs.getString(5),
            rs.getString(6),
            rs.getString(7),
            rs.getString(8)))
        .list();
  }

  @Override
  public int resolveRecipient(UUID emailDeliveryId, String recipientAddress, String recipientName, Instant now) {
    return jdbc.sql("""
        UPDATE email_deliveries
        SET recipient_address=:address,recipient_name=:name,
            status=CAST('PENDING' AS email_delivery_status),next_attempt_at=:now,updated_at=:now
        WHERE email_delivery_id=:id
          AND status=CAST('WAITING_FOR_RECIPIENT' AS email_delivery_status)
          AND recovery_expires_at > :now
        """)
        .param("address", recipientAddress)
        .param("name", recipientName)
        .param("now", Timestamps.toTimestamp(now))
        .param("id", emailDeliveryId)
        .update();
  }

  @Override
  public int failWaiting(UUID emailDeliveryId, String code, Instant now) {
    return jdbc.sql("""
        UPDATE email_deliveries
        SET status=CAST('FAILED' AS email_delivery_status),failed_at=:now,
            last_error_code=:code,last_error_message='recovery expired before recipient projection became usable',
            updated_at=:now
        WHERE email_delivery_id=:id
          AND status=CAST('WAITING_FOR_RECIPIENT' AS email_delivery_status)
        """)
        .param("now", Timestamps.toTimestamp(now))
        .param("code", code)
        .param("id", emailDeliveryId)
        .update();
  }

  @Override
  public int requeueBeforeProviderAttempt(UUID emailDeliveryId, UUID claimToken, Instant nextAttemptAt, Instant now) {
    return jdbc.sql("""
        UPDATE email_deliveries
        SET status=CAST('PENDING' AS email_delivery_status),next_attempt_at=:next,
            claim_token=NULL,claimed_at=NULL,lease_until=NULL,worker_id=NULL,updated_at=:now
        WHERE email_delivery_id=:id AND claim_token=:token
          AND status=CAST('SENDING' AS email_delivery_status)
        """)
        .param("next", Timestamps.toTimestamp(nextAttemptAt))
        .param("now", Timestamps.toTimestamp(now))
        .param("id", emailDeliveryId)
        .param("token", claimToken)
        .update();
  }
}
