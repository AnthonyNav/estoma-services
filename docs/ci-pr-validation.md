# Pull Request Validation

The always-present Woodpecker `pr-validation` workflow determines which Maven
service directories changed in a pull request and validates each one. Configure
the `develop` branch ruleset only after a pull request has run successfully,
because GitHub can require only status check names that it has received.

## Configure The `develop` Ruleset

1. Open the repository in GitHub and go to **Settings** > **Rules** > **Rulesets**.
2. Create a new **Branch ruleset**, name it `develop-pr-validation`, and set its
   enforcement status to **Active**.
3. Under **Target branches**, select **Include by pattern** and enter `develop`.
4. Under **Branch rules**, enable **Require a pull request before merging**.
5. Enable **Require status checks to pass**, then enable **Require branches to
   be up to date before merging** if the repository policy requires it.
6. Open any pull request and wait for its Woodpecker `pr-validation` status to
   complete. In the status-check picker, select the exact `pr-validation` check
   name GitHub reports from that first pull request. Do not type or guess the
   name; Woodpecker's reported check name is the value GitHub will enforce.
7. Save the ruleset and verify that a subsequent pull request into `develop`
   cannot merge until `pr-validation` passes.

Existing service test workflows remain path-scoped and should not be required
by this ruleset. GitHub treats a required check that Woodpecker did not run as
pending.

## Credentials

Pull-request test workflows do not reference GHCR credentials. GHCR secrets
remain limited to the trusted `develop` push/manual build workflows; do not
make those secrets available to pull-request events.
