# Platform BFF contract artifacts

`openapi.yaml` is the complete, versioned OpenAPI 3.1 document for `/api/v1`. It is stored as
JSON, which is valid YAML, so its structural snapshot remains deterministic.

The runtime endpoint and the committed document are compared in both directions by
`OpenApiControllerTest`. Regenerate the snapshot after an intentional HTTP-contract change with:

```text
./mvnw -B -DupdateOpenApi=true -Dtest=OpenApiControllerTest test
```

Run the same test without `-DupdateOpenApi=true` before committing; it fails on any runtime or
versioned-document drift.

See [projection-genesis-runbook.md](projection-genesis-runbook.md) for the separate one-time
fresh-environment procedure required by the deployment manifests.
