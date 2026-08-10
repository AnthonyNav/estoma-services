package com.estoma.communications.contracts;

public enum CommunicationsCommand {
  REQUEST_COMMUNICATION("RequestCommunication"),
  REGISTER_INBOX_VISIT("RegisterInboxVisit"),
  RETRY_EMAIL_DELIVERY("RetryEmailDelivery"),
  RESOLVE_INDETERMINATE_EMAIL_DELIVERY("ResolveIndeterminateEmailDelivery"),
  EXPIRE_INBOX_MESSAGES("ExpireInboxMessages");

  private final String wireName;

  CommunicationsCommand(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
