package com.estoma.platformbff.api.mailbox;

import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.mailbox.MailboxQueryService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxMessagesResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxSummaryResponse;
import com.estoma.platformbff.contracts.http.mailbox.RegisterMailboxVisitRequest;
import jakarta.validation.Valid;
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

  public MailboxController(MailboxQueryService mailbox, LocalAuthorizationService authorization,
      PublicCommandAdapter commands) {
    this.mailbox = mailbox;
    this.authorization = authorization;
    this.commands = commands;
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
    var response = commands.submit(accountId, "mailbox-register-visit", "RegisterInboxVisit",
        Map.of("accountId", accountId, "systemCode", request.systemCode(), "visitedAt", request.visitedAt()),
        idempotencyKey, accountId, null);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }
}
