#!/bin/sh

set -eu

service_name="$1"
image_name="$2"
overlay_path="$3"

: "${ESTOMA_INFRA_TOKEN:?ESTOMA_INFRA_TOKEN is required to update estoma-infra}"
: "${CI_COMMIT_SHA:?CI_COMMIT_SHA is required to derive the immutable image tag}"

apk add --no-cache curl git jq python3 >/dev/null

short_sha="$(printf '%s' "$CI_COMMIT_SHA" | cut -c1-7)"
image_tag="develop-${short_sha}"
branch="chore/deploy-${service_name}-${image_tag}"
repo="AnthonyNav/estoma-infra"

existing_prs="$(curl --fail-with-body --silent --show-error \
  --header "Accept: application/vnd.github+json" \
  --header "Authorization: Bearer ${ESTOMA_INFRA_TOKEN}" \
  --header "X-GitHub-Api-Version: 2022-11-28" \
  "https://api.github.com/repos/${repo}/pulls?state=open&head=AnthonyNav:${branch}" \
  | jq 'length')"

if [ "$existing_prs" != "0" ]; then
  echo "An open infrastructure pull request already exists for ${service_name}:${image_tag}."
  exit 0
fi

workspace="$(mktemp -d)"
trap 'rm -rf "$workspace"' EXIT

git config --global user.name "estoma-ci"
git config --global user.email "estoma-ci@users.noreply.github.com"
git config --global url."https://x-access-token:${ESTOMA_INFRA_TOKEN}@github.com/".insteadOf "https://github.com/"
git clone --depth 1 --branch main "https://github.com/${repo}.git" "$workspace/estoma-infra" >/dev/null

cd "$workspace/estoma-infra"

python3 - "$overlay_path" "$image_name" "$image_tag" <<'PY'
import sys
from pathlib import Path

overlay_path = Path(sys.argv[1])
image_name = sys.argv[2]
image_tag = sys.argv[3]

lines = overlay_path.read_text().splitlines(keepends=True)
changed = False

for index, line in enumerate(lines):
    if line.strip() != f"- name: {image_name}":
        continue

    for cursor in range(index + 1, len(lines)):
        if lines[cursor].lstrip().startswith("- name:"):
            break
        if lines[cursor].strip().startswith("newTag:"):
            indent = lines[cursor][: len(lines[cursor]) - len(lines[cursor].lstrip())]
            lines[cursor] = f"{indent}newTag: {image_tag}\n"
            changed = True
            break
    break

if not changed:
    raise SystemExit(f"Could not update {image_name} in {overlay_path}")

overlay_path.write_text("".join(lines))
PY

if git diff --quiet -- "$overlay_path"; then
  echo "The ${service_name} develop overlay already references ${image_tag}."
  exit 0
fi

git switch -c "$branch" >/dev/null
git add -- "$overlay_path"
git commit -m "chore(deploy): update ${service_name} develop image to ${image_tag}" >/dev/null
# This branch is CI-owned and deterministic for one immutable image. Force is
# intentional so a retry can recreate a previously closed automation branch.
git push --force origin "$branch" >/dev/null

payload="$(jq -n \
  --arg title "chore(deploy): update ${service_name} develop image to ${image_tag}" \
  --arg head "$branch" \
  --arg body "Automated image tag update for ${service_name}." \
  '{title: $title, head: $head, base: "main", body: $body}')"

curl --fail-with-body --silent --show-error --output /dev/null \
  --request POST \
  --header "Accept: application/vnd.github+json" \
  --header "Authorization: Bearer ${ESTOMA_INFRA_TOKEN}" \
  --header "X-GitHub-Api-Version: 2022-11-28" \
  --header "Content-Type: application/json" \
  --data "$payload" \
  "https://api.github.com/repos/${repo}/pulls"

echo "Created the infrastructure pull request for ${service_name}:${image_tag}."
