#!/bin/sh

set -eu

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

: "${CI_COMMIT_TARGET_BRANCH:?CI_COMMIT_TARGET_BRANCH is required for pull-request validation}"

target_branch="$CI_COMMIT_TARGET_BRANCH"
if ! git check-ref-format --branch "$target_branch" >/dev/null 2>&1; then
  printf 'Invalid pull-request target branch: %s\n' "$target_branch" >&2
  exit 1
fi

target_ref="refs/remotes/origin/$target_branch"
git fetch --no-tags origin "+refs/heads/$target_branch:$target_ref"

if ! merge_base="$(git merge-base "$target_ref" HEAD)"; then
  shallow_file="$(git rev-parse --git-path shallow)"
  if [ ! -f "$shallow_file" ]; then
    printf 'Could not find a common ancestor with target branch %s.\n' "$target_branch" >&2
    exit 1
  fi

  git fetch --no-tags --unshallow origin "+refs/heads/$target_branch:$target_ref"
  if ! merge_base="$(git merge-base "$target_ref" HEAD)"; then
    printf 'Could not find a common ancestor with target branch %s after fetching its history.\n' "$target_branch" >&2
    exit 1
  fi
fi

changed_services=0
for service_dir in */; do
  [ -f "${service_dir}mvnw" ] || continue

  if ! git diff --quiet "$merge_base" HEAD -- "$service_dir"; then
    changed_services=1
    service_dir="${service_dir%/}"
    printf 'Running %s/./mvnw -B clean verify\n' "$service_dir"
    if ! (
      cd "$service_dir"
      ./mvnw -B clean verify
    ); then
      printf 'Validation failed for %s.\n' "$service_dir" >&2
      exit 1
    fi
  fi
done

if [ "$changed_services" -eq 0 ]; then
  printf 'No Maven service directories changed; skipping service validation.\n'
  exit 0
fi
