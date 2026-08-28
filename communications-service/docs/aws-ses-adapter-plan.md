# communications-service — AWS SES Adapter Plan

## Goal

Integrate Amazon SES API v2 as the first real email provider for `communications-service` **without coupling domain or application code to AWS**.

The canonical business/architecture contract lives in `estomatologia-platform-docs/03-services/shared/communications-service/aws-ses-provider.md`.

This document translates that decision into concrete implementation work for this repository.

---

## 1. Current State

The service already has the correct outbound seam:

```text
EmailDeliveryWorker
        ↓
EmailProviderRouter
        ↓
EmailProviderPort
        ↓
StubEmailProvider
```

Existing strengths that must be preserved:

- `EmailProviderPort` exposes only neutral request/result types.
- `Accepted`, `Failed` and `Indeterminate` are provider-neutral.
- `providerMessageId` is already persisted per attempt.
- provider calls occur outside long database transactions.
- retry/backoff, leases/fencing and `INDETERMINATE` handling already exist.
- `ACCEPTED` and `DELIVERED` are separate states.
- password-recovery secrets exist only in memory immediately before the provider call.

The real-provider gap is therefore infrastructure work, not a domain redesign.

---

## 2. Mandatory Hexagonal Boundary

### Must remain unchanged/provider-neutral

The following packages must not import `software.amazon.awssdk.*`:

```text
com.estoma.communications.domain
com.estoma.communications.application
com.estoma.communications.contracts
```

No AWS type may appear in:

- `EmailProviderPort`;
- commands/events/results;
- domain aggregates;
- repositories;
- database schemas;
- RabbitMQ payloads.

AWS belongs only under:

```text
com.estoma.communications.infrastructure.email
```

A provider SDK exception must be translated before returning through `EmailProviderPort`.

---

## 3. Required Changes

### 3.1 Maven dependency

**File:** `communications-service/pom.xml`

Add AWS SDK for Java v2 SES support using dependency management/BOM so the provider dependency is versioned centrally.

Required module:

```text
software.amazon.awssdk:sesv2
```

Do not add AWS SDK types to any application/domain module or contract.

---

### 3.2 SES client configuration

**New infrastructure class, suggested:**

```text
infrastructure/email/SesClientConfig.java
```

Responsibilities:

- build/provide `SesV2Client`;
- read the configured AWS region;
- use the AWS SDK default credential provider chain or the runtime identity supplied by infrastructure;
- fail startup with a sanitized configuration error when `provider=ses` but required non-secret configuration is invalid.

Must not:

- embed access keys;
- log credentials;
- create IAM users/keys;
- know business rules;
- render templates.

Credentials/identity remain an `estoma-infra` responsibility.

---

### 3.3 SES adapter

**New infrastructure class:**

```text
infrastructure/email/SesEmailProvider.java
```

Implements:

```text
EmailProviderPort
```

Responsibilities:

```text
EmailSendRequest
        ↓
AWS SendEmailRequest
        ↓
SesV2Client.sendEmail(...)
        ↓
AWS response/error
        ↓
EmailSendResult
```

The adapter may use:

- destination address;
- resolved from name/address;
- reply-to address;
- subject;
- text body;
- HTML body;
- optional SES Configuration Set.

It must use SES `Simple` content. Server-side SES templates are not the source of truth and are out of scope.

On successful SES acceptance:

```text
SendEmailResponse.messageId()
→ EmailSendResult.Accepted("ses", providerMessageId)
```

No SES response object may escape the adapter.

---

### 3.4 Fix provider selection

**File:**

```text
infrastructure/email/EmailProviderRouter.java
```

This is a blocking prerequisite before registering SES.

Current behavior is unsafe for multiple adapters:

1. it selects via `ObjectProvider.getIfUnique()`;
2. adding both stub and SES makes the provider non-unique;
3. fallback then resolves to the stub;
4. `select()` currently labels the selected provider with `StubEmailProvider.PROVIDER_NAME`.

Therefore simply adding `SesEmailProvider` would not be sufficient.

Required behavior:

```text
estoma.communications.email.provider=stub|ses
        ↓
EmailProviderRouter
        ↓
lookup exact registered infrastructure adapter
        ↓
ProviderSelection(configuredProviderCode, selectedPort)
```

Suggested infrastructure-only pattern:

```text
Map<String, EmailProviderPort>
```

with explicit bean/provider codes such as:

```text
stub
ses
```

Rules:

- default local provider may remain `stub`;
- a configured unknown provider fails startup;
- selecting `ses` must produce provider code `ses` in attempts/events;
- `StubEmailProvider` is not a production fallback;
- provider selection is never accepted from a command/event payload.

The exact Spring registration mechanism is infrastructure detail; the application port remains unchanged.

---

### 3.5 Configuration

**File:**

```text
src/main/resources/application.yml
```

Add neutral/provider-specific runtime configuration under infrastructure configuration only.

Expected environment surface:

```text
COMM_EMAIL_PROVIDER=stub|ses
COMM_EMAIL_SES_REGION=<region>
COMM_EMAIL_SES_CONFIGURATION_SET=<optional>
```

Recommended defaults:

```text
local/test → stub
connected environment → explicitly configure ses
```

Do not add secrets as default values.

AWS credentials must be supplied by the standard AWS credential chain/runtime environment, not by business configuration objects.

---

## 4. Error Normalization

`SesEmailProvider` must translate SES/AWS failures into Communications-owned codes.

Minimum required V1 mapping:

| Normalized code | Result | Retryable |
| --- | --- | --- |
| `PROVIDER_THROTTLED` | `Failed` | Yes, only when rejection before acceptance is certain |
| `PROVIDER_UNAVAILABLE` | `Failed` | Yes, only when rejection before acceptance is certain |
| `PROVIDER_SENDING_PAUSED` | `Failed` | No |
| `PROVIDER_ACCOUNT_SUSPENDED` | `Failed` | No |
| `SENDER_NOT_VERIFIED` | `Failed` | No |
| `PROVIDER_INVALID_REQUEST` | `Failed` | No |
| `PROVIDER_MESSAGE_REJECTED` | `Failed` | No |
| `PROVIDER_TIMEOUT` | `Indeterminate` | Never automatic |
| `PROVIDER_EXCEPTION` | `Indeterminate` when remote outcome is unknown | Never automatic |

Core invariant:

> If the adapter cannot prove that SES rejected the request before accepting the message, it must not return a retryable `Failed`; it returns `Indeterminate`.

This preserves the existing duplicate-protection semantics of `ResolveIndeterminateEmailDelivery`.

Sanitized error detail must not contain:

- recipient when unnecessary for diagnosis;
- subject/body;
- recovery code;
- credentials/tokens;
- full raw AWS request/response dumps.

---

## 5. `ACCEPTED` vs `DELIVERED`

No domain-state redesign is required.

For this implementation phase:

```text
SES SendEmail accepted
→ EmailDelivery.ACCEPTED
```

The SES `MessageId` is stored as the neutral `providerMessageId` already supported by `EmailDeliveryAttempt`.

Do not mark `DELIVERED` after `SendEmail`.

SES delivery/bounce/complaint feedback is a later integration phase and must enter through a separate infrastructure adapter that translates provider events before touching Communications state.

---

## 6. Templates

No SES server-side template integration is required.

Preserve the existing flow:

```text
templateCode
→ EmailTemplateResolver
→ final subject/text/html
→ EmailProviderPort
→ SES Simple content
```

This is required to preserve provider portability.

`templateCode` remains an Estoma logical identifier, never an SES template name.

---

## 7. Tests Required

### `SesEmailProviderTest`

Unit tests with a mocked/fake `SesV2Client`; no live AWS account in normal CI.

Cover at least:

1. accepted request returns neutral `Accepted` with SES MessageId;
2. request maps from/to/reply-to/subject/text/html correctly;
3. throttling/deterministic transient rejection maps to retryable `Failed`;
4. sender/configuration rejection maps to terminal `Failed`;
5. ambiguous transport/provider exception maps to `Indeterminate`;
6. error detail is sanitized.

### `EmailProviderRouterTest`

Extend current tests to prove:

1. `stub` selects stub;
2. `ses` selects SES adapter;
3. selected provider code is not hardcoded to `stub`;
4. unknown provider fails fast;
5. timeout remains `Indeterminate` and is tagged with the actual selected provider.

### Worker regression tests

Existing worker tests must continue proving:

- provider call occurs with no open DB transaction;
- accepted result persists provider message id;
- retryable failure schedules retry;
- terminal failure fails delivery;
- indeterminate result does not auto-retry.

### CI rule

```text
./mvnw verify
```

must pass without AWS credentials and without internet access to SES.

A real SES smoke test is an operational/deployment check with isolated credentials and must not become a requirement for normal PR validation.

---

## 8. Files Expected To Change

Minimum implementation scope:

```text
communications-service/
├── pom.xml
├── src/main/resources/application.yml
├── src/main/java/com/estoma/communications/infrastructure/email/
│   ├── EmailProviderRouter.java
│   ├── SesClientConfig.java                 # new
│   ├── SesEmailProvider.java                # new
│   └── StubEmailProvider.java               # registration/config may change
└── src/test/java/com/estoma/communications/infrastructure/email/
    ├── EmailProviderRouterTest.java
    └── SesEmailProviderTest.java             # new
```

Potential additional tests may change, but domain/application/contracts should not require provider-specific edits.

---

## 9. Explicit Non-Goals

Do not include in the SES adapter PR:

- SES webhook/EventBridge/SNS ingestion;
- `ACCEPTED → DELIVERED` handling;
- bounce/complaint lifecycle changes;
- second provider/fallback implementation;
- SES server-side templates;
- IAM resource provisioning;
- DNS/DKIM provisioning;
- production-access request automation;
- changes to business commands/events simply to expose SES details.

These are separate concerns/PRs.

---

## 10. Infrastructure Follow-up

A subsequent `estoma-infra` change will be required to provide at runtime:

```text
COMM_EMAIL_PROVIDER=ses
COMM_EMAIL_SES_REGION
COMM_EMAIL_SES_CONFIGURATION_SET        # optional
AWS runtime identity/credentials
```

Infrastructure must also own:

- verified SES sending identity/domain;
- DKIM/DNS;
- least-privilege IAM permissions;
- secret/identity rotation;
- SES production access/quota operations.

The application repository must never commit those secret values.

---

## 11. Definition Of Done

The SES implementation PR is complete when:

- SES is implemented only as an infrastructure adapter;
- `EmailProviderPort` remains vendor-neutral;
- no AWS SDK type exists in domain/application/contracts;
- provider selection is explicit and no longer relies on `getIfUnique()`;
- `select()` reports the real provider code;
- stub remains local/test only;
- SES acceptance stores `MessageId` as `providerMessageId`;
- deterministic errors are normalized;
- ambiguous outcomes remain `INDETERMINATE`;
- no automatic resend occurs for ambiguous outcomes;
- all tests pass without real AWS credentials;
- no secrets or email/recovery content are added to logs, metrics or Git.
