package com.estoma.communications.application.port;

/**
 * Resolves a logical {@code senderCode} (commands.md §3, README.md §7) to a
 * concrete sender identity. {@code senderCode} is never a physical
 * mailbox/credential name -- the domain and the wire contract only ever see
 * the logical code.
 *
 * <p>The definitive {@code senderCode} catalog is a pending decision
 * (communications-service/docs/pending_decisions.md); v1 resolves every
 * code to a single configured platform sender.
 */
public interface EmailSenderDirectory {

  ResolvedSender resolve(String senderCode);

  record ResolvedSender(String fromName, String fromAddress) {}
}
