# communications-service — AWS SES Sandbox Validation Plan

## Goal

Prepare the operational validation path for Amazon SES before implementing or activating the real provider in `communications-service`.

This document complements:

- `docs/aws-ses-adapter-plan.md` — implementation plan for the SES adapter;
- `estomatologia-platform-docs/03-services/shared/communications-service/aws-ses-provider.md` — canonical provider contract.

It does **not** modify the Communications domain, provider port, runtime code, Maven dependencies, Kubernetes manifests, IAM resources, DNS or SES configuration.

The objective is to define a reproducible path from a new AWS account to a controlled SES Sandbox smoke test, and then to the future `communications-service -> SES` end-to-end validation.

---

## 1. Scope

This plan covers:

1. securing the AWS account used for Estoma;
2. selecting one SES region for sandbox validation;
3. verifying a sender identity;
4. verifying a test recipient while the account remains in the SES sandbox;
5. confirming SES works independently from Estoma using the AWS console;
6. preparing least-privilege programmatic access for the later service smoke test;
7. defining the application-level smoke tests that must pass once `SesEmailProvider` exists;
8. defining the gate before requesting SES production access.

This plan deliberately does **not** require production access from SES. The first application tests must run successfully while the account remains in the sandbox.

---

## 2. Important SES Sandbox Behavior

A new SES account starts in the sandbox **per AWS Region**.

While the selected region remains in the sandbox:

- the sender identity must be verified;
- normal destination email addresses must also be verified;
- the SES mailbox simulator may be used without verifying simulator destinations;
- the account has restricted sending quotas;
- leaving the sandbox in one region does not automatically remove sandbox restrictions in another region.

Therefore all validation in this plan must use one explicit region consistently.

### Initial sandbox region

Recommended for the first Estoma validation:

```text
us-east-1
```

This recommendation is only for the sandbox/integration phase and does not permanently lock the production region. If infrastructure later selects another region, identities and sandbox/production status must be validated again in that region.

---

## 3. AWS Account Bootstrap

### 3.1 Create the AWS account

Create a dedicated AWS account suitable for the Estoma project.

Required during registration:

- a root-user email address that can be recovered securely;
- a strong and unique password;
- contact/account information requested by AWS;
- a valid payment method when required by AWS account registration.

Official reference:

https://docs.aws.amazon.com/accounts/latest/reference/getting-started.html

### 3.2 Secure the root user immediately

Before creating SES resources:

- enable MFA for the root user;
- do not create root-user access keys;
- do not use the root user for routine development or service execution;
- keep account-recovery access protected.

Official references:

- https://docs.aws.amazon.com/IAM/latest/UserGuide/root-user-best-practices.html
- https://docs.aws.amazon.com/IAM/latest/UserGuide/getting-started-account-iam.html

### 3.3 Create normal administrative access

Use a non-root administrative identity for day-to-day AWS configuration. Prefer temporary credentials/federated access where practical.

The application runtime identity must be separate from the human administrative identity.

---

## 4. Enter Amazon SES

Open the Amazon SES console and switch explicitly to the chosen sandbox region.

For the initial validation:

```text
Region: us-east-1
```

SES console:

https://console.aws.amazon.com/ses/

Do not continue if a different region is selected accidentally. SES verified identities and sandbox status are region-specific.

---

## 5. Verify the Sender Identity

For the first test, use an email address that the project team controls.

Example logical shape:

```text
estoma test sender email
```

Do not encode this concrete address in application source code or repository documentation.

### Procedure

1. In SES, open `Configuration -> Identities`.
2. Choose `Create identity`.
3. Select `Email address` for the simplest sandbox proof.
4. Enter the test sender email address.
5. Create the identity.
6. Open the verification email sent by AWS.
7. Follow the verification link.
8. Confirm SES shows the identity as `Verified` in the selected region.

Official reference:

https://docs.aws.amazon.com/ses/latest/dg/creating-identities.html

### Future production preference

For production, prefer verifying a controlled domain and configuring DKIM instead of relying only on a single verified mailbox. That work belongs to the infrastructure/production-access phase and is not required for the first sandbox smoke test.

---

## 6. Verify a Sandbox Recipient

Because the account is still in the SES sandbox, a normal recipient must also be verified.

For the first human-visible test, it is acceptable to use the same verified address as both sender and recipient.

Alternatively:

1. create a second email identity in SES;
2. verify it from its inbox;
3. use that identity only as a test recipient.

No production recipient list is needed at this stage.

Official reference:

https://docs.aws.amazon.com/ses/latest/dg/verify-addresses-and-domains.html

---

## 7. Baseline Test Directly From SES Console

Before involving Estoma code, prove that the AWS account and SES identity are functional independently.

### Test A — Real verified inbox

From the verified SES identity:

1. choose `Send test email`;
2. select a simple/formatted message;
3. select `Custom` destination;
4. enter a verified recipient;
5. send the message;
6. confirm the message arrives in the recipient inbox.

Expected result:

```text
SES accepts message
-> recipient receives email
```

### Test B — SES mailbox simulator

Use the SES mailbox simulator to validate provider-side scenarios without using real recipients.

At minimum exercise:

- successful delivery simulation;
- bounce simulation;
- complaint simulation when preparing feedback handling.

Simulator traffic is suitable for controlled testing and does not require a real mailbox for the simulator destination.

Official reference:

https://docs.aws.amazon.com/ses/latest/dg/send-an-email-from-console.html

### Gate

Do not begin application-level debugging until Test A succeeds from the SES console. If SES itself cannot send from the selected identity/region, the issue is outside `communications-service`.

---

## 8. Programmatic Identity For `communications-service`

The service must never use root credentials.

The runtime identity must be independent from the human administrator and should expose only the permissions required for email delivery.

For the initial SES adapter scope, the required capability is effectively:

```text
ses:SendEmail
```

and only for the identity/resources required by the selected SES configuration where practical.

AWS recommends temporary credentials and IAM roles where possible. If the current on-premises K3s environment cannot yet obtain temporary AWS credentials through an approved mechanism, a dedicated restricted runtime credential may be used for the **develop sandbox only**, stored outside Git through the project's secret-management strategy.

Rules:

- never create access keys for the AWS root user;
- never commit AWS credentials;
- never expose credentials to Pull Request CI;
- never reuse a human administrator credential for the application;
- keep `develop` runtime credentials separate from future staging/production credentials;
- prefer migration to temporary credentials/runtime identity when the infrastructure mechanism is available.

Official IAM best-practices reference:

https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html

---

## 9. Application Configuration Target

After the future SES code PR is implemented, the connected develop environment should expose the equivalent logical configuration:

```text
COMM_EMAIL_PROVIDER=ses
COMM_EMAIL_SES_REGION=us-east-1
COMM_EMAIL_SES_CONFIGURATION_SET=<optional>
COMM_EMAIL_DEFAULT_SENDER_ADDRESS=<verified-sender>
```

AWS credentials are **not** represented by these business/provider configuration values. They are resolved independently by the AWS SDK credential provider chain/runtime environment.

The service must continue to use:

```text
COMM_EMAIL_PROVIDER=stub
```

for normal deterministic local/unit/integration tests that do not explicitly opt into AWS.

---

## 10. Application Smoke-Test Sequence

Once `SesEmailProvider` exists, validation must progress in this order.

### Stage 1 — Provider adapter only

Using the selected sandbox account and verified sender/recipient:

```text
SesEmailProvider
-> SES SendEmail
-> Accepted(providerMessageId)
```

Verify:

- SES accepts the request;
- `providerCode = ses`;
- the returned SES MessageId becomes the neutral `providerMessageId`;
- no AWS SDK type crosses `EmailProviderPort`.

### Stage 2 — Communications worker

Create a normal test email delivery and allow the real `EmailDeliveryWorker` to process it.

Expected lifecycle:

```text
PENDING
-> SENDING
-> ACCEPTED
```

Verify:

- exactly one provider attempt is created;
- the attempt records provider `ses`;
- `providerMessageId` is persisted;
- the worker does not mark the delivery `DELIVERED` merely because SES accepted it;
- the recipient receives the email.

### Stage 3 — Password recovery E2E

Exercise the real path:

```text
Authentication
-> PasswordRecoveryRequested
-> RabbitMQ
-> Communications
-> GetRecoveryCodeForDelivery Security RPC
-> EmailDeliveryWorker
-> SES
-> verified recipient
```

Verify:

- the email is received;
- the recovery code works;
- the recovery code is never present in Communications DB;
- the recovery code is never written to Outbox/Inbox/DLQ;
- logs and metrics contain no recovery code;
- the SES `MessageId` is retained only as neutral provider metadata.

### Stage 4 — Failure semantics

Use deterministic adapter tests plus controlled SES/simulator scenarios to validate:

- accepted send;
- deterministic rejection;
- throttling/retryable failure where rejection-before-acceptance is known;
- timeout/unknown outcome -> `INDETERMINATE`;
- no automatic resend after `INDETERMINATE`.

Live AWS testing must not replace deterministic unit/integration coverage.

---

## 11. Sandbox Acceptance Criteria

The SES sandbox phase is complete when all of the following are true:

- [ ] AWS root user is protected with MFA.
- [ ] No root access keys exist.
- [ ] One SES region is explicitly selected for testing.
- [ ] Sender identity is verified in that region.
- [ ] Test recipient is verified or SES mailbox simulator is used.
- [ ] A direct SES-console email succeeds.
- [ ] A dedicated non-root runtime identity strategy is defined for develop.
- [ ] No credentials are committed to Git.
- [ ] `SesEmailProvider` tests pass without requiring live AWS credentials in CI.
- [ ] A live provider smoke test produces `provider=ses` and a real `providerMessageId`.
- [ ] `EmailDelivery` reaches `ACCEPTED`, not fabricated `DELIVERED`.
- [ ] Password-recovery E2E succeeds without durable recovery secrets.
- [ ] Timeout/unknown outcomes remain `INDETERMINATE` with no automatic resend.

---

## 12. Production-Access Gate

Do **not** request SES production access merely to unblock initial implementation.

Request removal from the sandbox only after:

1. the SES-console baseline is green;
2. the application adapter is green;
3. the Communications worker smoke test is green;
4. password recovery through SES is green;
5. sender/domain ownership and DKIM strategy are defined;
6. bounce/complaint handling has an approved operational path;
7. runtime credentials are isolated and least-privilege;
8. observability exists for provider failures without sensitive data.

When production access is eventually requested, remember that sandbox status is region-specific.

Official reference:

https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html

---

## 13. Work Split

### Manual AWS/account work

The project/account owner performs:

- AWS account registration;
- root MFA setup;
- normal administrative access setup;
- SES region selection;
- sender/recipient identity verification;
- direct console send test;
- later approval of IAM/runtime credentials and production-access request.

### `estoma-services`

A future implementation PR performs:

- AWS SDK v2 `sesv2` dependency;
- `SesV2Client` configuration;
- `SesEmailProvider`;
- explicit provider selection;
- SES error normalization;
- deterministic adapter tests;
- provider smoke-test support without weakening normal CI isolation.

### `estoma-infra`

A later infrastructure PR performs:

- Communications RabbitMQ runtime configuration if still pending;
- runtime AWS identity/credential delivery;
- `COMM_EMAIL_PROVIDER=ses`;
- SES region/provider environment configuration;
- sender configuration;
- SOPS/Kubernetes secret integration when credentials are required;
- eventual DNS/DKIM/Configuration Set/event feedback provisioning.

---

## 14. Result of This Plan

After this sandbox plan is completed, the project should have proven two independent facts:

```text
AWS SES account/identity works
```

and

```text
communications-service can safely use SES through EmailProviderPort
```

Only after both are demonstrated should the project move to production SES access and broader recipient delivery.