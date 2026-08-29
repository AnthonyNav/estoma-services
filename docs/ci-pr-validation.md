# Pull Request Validation

Woodpecker test workflows publish pull-request statuses for changes to their
respective service paths. Configure the `develop` branch ruleset only after a
test pull request has run successfully, because GitHub can require only status
check names that it has received.

## Configure The `develop` Ruleset

1. Open the repository in GitHub and go to **Settings** > **Rules** > **Rulesets**.
2. Create a new **Branch ruleset**, name it `develop-pr-validation`, and set its
   enforcement status to **Active**.
3. Under **Target branches**, select **Include by pattern** and enter `develop`.
4. Under **Branch rules**, enable **Require a pull request before merging**.
5. Enable **Require status checks to pass**, then enable **Require branches to
   be up to date before merging** if the repository policy requires it.
6. Open a pull request that changes each service path at least once and wait for
   its Woodpecker test status to complete. In the status-check picker, select
   the exact check names GitHub reports from that first pull request. Do not
   type or guess names such as pipeline file names: Woodpecker's reported check
   name is the value GitHub will enforce.
7. Save the ruleset and verify that a subsequent pull request into `develop`
   cannot merge until every selected Woodpecker check passes.

The test workflows are path-scoped. Configure only checks that should be
required for every pull request, or use separate rulesets/path policy where
available; GitHub treats a required check that Woodpecker did not run as
pending.

## Credentials

Pull-request test workflows do not reference GHCR credentials. GHCR secrets
remain limited to the trusted `develop` push/manual build workflows; do not
make those secrets available to pull-request events.
