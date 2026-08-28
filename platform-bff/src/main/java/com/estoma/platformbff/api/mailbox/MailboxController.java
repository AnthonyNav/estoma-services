package com.estoma.platformbff.api.mailbox;

import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.mailbox.MailboxQueryService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxMessagesResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxSummaryResponse;
import com.estoma.platformbff.contracts.http.mailbox.RegisterMailboxVisitRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mailbox")
public class MailboxController {
  private final MailboxQueryService mailbox;
  private final LocalAuthorizationService authorization;
  private final PublicCommandAdapter commands;
  private final Clock clock;

  public MailboxController(MailboxQueryService mailbox, LocalAuthorizationService authorization,
      PublicCommandAdapter commands, Clock clock) {
    this.mailbox = mailbox;
    this.authorization = authorization;
    this.commands = commands;
    this.clock = clock;
  }

  @GetMapping("/messages")
  public MailboxMessagesResponse messages() {
    return mailbox.messages();
  }

  @GetMapping("/summary")
  public MailboxSummaryResponse summary() {
    return mailbox.summary();
  }

  @PostMapping("/visits")
  public ResponseEntity<AcceptedOperationResponse> registerVisit(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody RegisterMailboxVisitRequest request) {
    var accountId = authorization.requireAuthenticatedAccount();
    Instant visitedAt = clock.instant();
    Map<String, Object> intent = Map.of("systemCode", request.systemCode());
    Map<String, Object> owner = Map.of("accountId", accountId, "systemCode", request.systemCode(),
        "visitedAt", visitedAt);
    var response = commands.submitEnriched(accountId, "mailbox-register-visit", "RegisterInboxVisit",
        owner, intent, idempotencyKey, null, null, null, null);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }
}
