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
if ! git rev-parse --verify --quiet "${target_ref}^{commit}" >/dev/null; then
  printf 'Target branch %s was not fetched by the clone step.\n' "$target_branch" >&2
  exit 1
fi

if ! merge_base="$(git merge-base "$target_ref" HEAD)"; then
  printf 'Could not find a common ancestor with target branch %s.\n' "$target_branch" >&2
  exit 1
fi

git diff --check "$merge_base" HEAD

changed_services=0
for service_dir in */; do
  [ -f "${service_dir}mvnw" ] || continue

  if ! git diff --quiet "$merge_base" HEAD -- "$service_dir"; then
    changed_services=1
    service_dir="${service_dir%/}"
    workflow=".woodpecker/${service_dir}-test.yaml"
    if [ ! -f "$workflow" ]; then
      printf 'Changed Maven service %s has no dedicated test workflow (%s).\n' "$service_dir" "$workflow" >&2
      exit 1
    fi
    printf 'Maven validation for %s is provided by %s.\n' "$service_dir" "$workflow"
  fi
done

if [ "$changed_services" -eq 0 ]; then
  printf 'No Maven service directories changed; checked pull-request diff only.\n'
  exit 0
fi
