# Supervisor directory and arrival preflight

`GET /api/v1/wash/supervision/appointments` is supervisor-only and read-only.
It lists the server's current America/Mexico_City service date, not all students.
Parameters: `query` (literal case-insensitive name/enrollment substring, <=120 chars),
`status` (ALL/SCHEDULED/IN_PROGRESS), `offset` (0..100000), `limit` (1..50, default 25).
Response: `serviceDate`, `items`, `nextOffset` (null at end). Search does not interpret SQL wildcards.
Selections must perform a fresh lookup; rows are not authorization receipts.

Lookup and directory items include `arrivalEligibility`: `status`, server `checkedAt`,
and nullable `opensAt`/`closesAt`. For ENTRY, the window is inclusive from slot start
minus 15 minutes through the projected owner `arrivalToleranceUntil`. Missing context
fails closed (503). OPEN permits the UI to begin review; TOO_EARLY, EXPIRED, WRONG_DATE,
or SLOT_INACTIVE block that transition. PENDING_ENTRY is ALREADY_REGISTERED: a timely
arrival remains reviewable after tolerance ends. Other workflow states are NOT_APPLICABLE.
No lookup or directory request publishes arrival or decision commands. WashExecution
retains final authoritative command validation and idempotency.

QR values remain opaque. Exact owner-issued representation lookup (indexed SHA-256 plus
exact comparison) is required; never extract appointment IDs or follow QR URLs. Unknown,
altered and tombstoned representations have the same 404. A still-recognized QR for another
date returns 409/BFF.WASH_QR_WRONG_DATE without student details. Once tombstoned it returns
the generic 404, not historical identity. Missing/inconsistent projection or recovery is
503, not a claim that a code is invalid. Never log or persist raw QR in diagnostic output.

Deploy BFF before frontend. The new real frontend fails closed for ENTRY responses without
eligibility. Preserve QR signing Secret and enabled recovery flags. These changes do not
alter business window lengths or replace the supervisor's physical identity check.
