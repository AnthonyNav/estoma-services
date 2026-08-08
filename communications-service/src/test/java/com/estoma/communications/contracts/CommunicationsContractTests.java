package com.estoma.communications.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommunicationsContractTests {

  @Test
  void exposesCanonicalStatusesAndPriorities() {
    assertThat(CommunicationStatus.values())
        .containsExactly(
            CommunicationStatus.PENDING,
            CommunicationStatus.PROCESSING,
            CommunicationStatus.COMPLETED,
            CommunicationStatus.FAILED);
    assertThat(CommunicationPriority.values())
        .containsExactly(
            CommunicationPriority.CRITICAL, CommunicationPriority.NORMAL, CommunicationPriority.INFORMATIONAL);
    assertThat(InboxMessageStatus.values())
        .containsExactly(InboxMessageStatus.ACTIVE, InboxMessageStatus.EXPIRED);
    assertThat(EmailContentMode.values()).containsExactly(EmailContentMode.GENERIC, EmailContentMode.TEMPLATE);
    assertThat(EmailDeliveryStatus.values())
        .containsExactly(
            EmailDeliveryStatus.PENDING,
            EmailDeliveryStatus.SENDING,
            EmailDeliveryStatus.ACCEPTED,
            EmailDeliveryStatus.DELIVERED,
            EmailDeliveryStatus.RETRY_SCHEDULED,
            EmailDeliveryStatus.FAILED);
    assertThat(EmailAttemptStatus.values()).containsExactly(EmailAttemptStatus.ACCEPTED, EmailAttemptStatus.FAILED);
    assertThat(ProjectedStatus.values()).containsExactly(ProjectedStatus.ACTIVE, ProjectedStatus.INACTIVE);
  }

  @Test
  void exposesCanonicalDurableCommandNames() {
    assertThat(CommunicationsCommand.REQUEST_COMMUNICATION.wireName()).isEqualTo("RequestCommunication");
    assertThat(CommunicationsCommand.REGISTER_INBOX_VISIT.wireName()).isEqualTo("RegisterInboxVisit");
    assertThat(CommunicationsCommand.RETRY_EMAIL_DELIVERY.wireName()).isEqualTo("RetryEmailDelivery");
    assertThat(CommunicationsCommand.EXPIRE_INBOX_MESSAGES.wireName()).isEqualTo("ExpireInboxMessages");
  }

  @Test
  void exposesCanonicalEventNames() {
    assertThat(CommunicationsEvent.COMMUNICATION_REQUESTED.wireName()).isEqualTo("CommunicationRequested");
    assertThat(CommunicationsEvent.INBOX_MESSAGE_CREATED.wireName()).isEqualTo("InboxMessageCreated");
    assertThat(CommunicationsEvent.INBOX_VISITED.wireName()).isEqualTo("InboxVisited");
    assertThat(CommunicationsEvent.INBOX_MESSAGE_EXPIRED.wireName()).isEqualTo("InboxMessageExpired");
    assertThat(CommunicationsEvent.EMAIL_DELIVERY_ACCEPTED.wireName()).isEqualTo("EmailDeliveryAccepted");
    assertThat(CommunicationsEvent.EMAIL_DELIVERY_RETRY_SCHEDULED.wireName()).isEqualTo("EmailDeliveryRetryScheduled");
    assertThat(CommunicationsEvent.EMAIL_PROVIDER_FALLBACK_ACTIVATED.wireName())
        .isEqualTo("EmailProviderFallbackActivated");
    assertThat(CommunicationsEvent.EMAIL_DELIVERY_FAILED.wireName()).isEqualTo("EmailDeliveryFailed");
    assertThat(CommunicationsEvent.EMAIL_DELIVERY_DELIVERED.wireName()).isEqualTo("EmailDeliveryDelivered");
    assertThat(CommunicationsEvent.COMMUNICATION_COMPLETED.wireName()).isEqualTo("CommunicationCompleted");
    assertThat(CommunicationsEvent.COMMUNICATION_FAILED.wireName()).isEqualTo("CommunicationFailed");
  }

  @Test
  void exposesCanonicalMessagingNames() {
    assertThat(MessagingNames.COMMANDS_EXCHANGE).isEqualTo("platform.commands");
    assertThat(MessagingNames.RESULTS_EXCHANGE).isEqualTo("platform.results");
    assertThat(MessagingNames.EVENTS_EXCHANGE).isEqualTo("platform.events");
    assertThat(MessagingNames.DLX_EXCHANGE).isEqualTo("platform.dlx");
    assertThat(MessagingNames.COMMAND_QUEUE).isEqualTo("commands.communications");
  }

  @Test
  void actorCarriesExactlyOneIdentityShape() {
    Actor account = Actor.account(java.util.UUID.randomUUID());
    assertThat(account.type()).isEqualTo(ActorType.ACCOUNT);
    assertThat(account.accountId()).isNotNull();
    assertThat(account.service()).isNull();

    Actor system = Actor.system("communications");
    assertThat(system.type()).isEqualTo(ActorType.SYSTEM);
    assertThat(system.service()).isEqualTo("communications");
    assertThat(system.accountId()).isNull();
  }
}
