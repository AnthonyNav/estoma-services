# Student import upload gateway

The admin upload gateway freezes an XLSX object before StartStudentImport.
It is disabled unless BFF_STUDENT_IMPORT_UPLOAD_ENABLED=true.

Request:

    PUT /api/v1/admin/academic/import-files/{fileReference}
    Authorization: Bearer <SUPERADMIN token>
    Idempotency-Key: <stable key, 1..200 characters>
    Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet

    <raw XLSX bytes>

The response contains fileReference, lowercase SHA-256 fileChecksum,
sizeBytes, and replayed. Pass the first two fields to the existing
POST /api/v1/admin/academic/imports command.

The gateway:

- authenticates and authorizes the SUPERADMIN before reading the body;
- copies the request once to a bounded temporary file while computing SHA-256;
- rejects empty objects and objects above 20 MiB;
- writes <prefix>/<fileReference>.xlsx with S3 If-None-Match: *;
- stores only SHA-256 hashes for content and the idempotency key in object metadata;
- returns an exact replay when the reference, bytes and idempotency key match;
- returns 409 instead of overwriting a frozen reference.

For an operator upload, keep the bearer token in a mode-0600 file and run:

    BFF_BASE_URL=https://develop.example \
    BFF_TOKEN_FILE=/secure/path/admin.token \
      ops/upload-student-import.sh \
      14557249-bd65-58f7-a672-dc2a2d462c95 \
      9ef52ce0-35da-4ff2-9c06-3ed1ff15ce2d \
      /secure/path/demo-academic.xlsx

The helper does not print the token or place it in the curl process arguments.
Do not enable shell tracing. Uploading does not create a period or start an
import; those remain explicit durable admin commands.

Both BFF and Academic must use the same bucket, prefix, region and endpoint.
BFF needs PutObject and GetObject on the prefix (GetObject authorizes the HEAD
used only to reconcile a conditional-PUT replay), without ListBucket. Academic
needs read access and must set
ESTOMA_STUDENT_IMPORT_STORAGE_ENABLED=true. Use separate workload credentials.
