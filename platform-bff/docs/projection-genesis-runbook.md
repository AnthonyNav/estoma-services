# Projection genesis runbook

`platform-bff` starts with projection genesis disabled. This is intentional: a normal
deployment must never promote an existing or unknown database to `READY`.

## One-time fresh-environment procedure

1. Create and attest the empty `platform_bff_db` environment before starting the BFF workload.
2. Run a single, auditable workload with both environment variables set:

   ```text
   BFF_GENESIS_ENABLED=true
   BFF_GENESIS_ENVIRONMENT_FRESH=true
   ```

3. Wait for the workload to finish successfully and verify the BFF readiness endpoint only after
   the required projections have reached their expected baseline.
4. Remove both variables (or the one-shot workload) before enabling the regular Deployment.

The production manifest, fresh-environment attestation, readiness verification and rollback hook
belong to the `estoma-infra` repository. This application repository deliberately does not enable
either flag by default.

## Rollback

If the one-time workload fails, stop it and keep the normal BFF deployment disabled. Do not retry
against an environment whose freshness can no longer be attested. Restore the database from the
pre-genesis backup or recreate the explicitly fresh environment, then repeat the procedure with a
new attestation.
