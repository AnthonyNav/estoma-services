#!/bin/sh

set -eu

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
maven_settings="$repo_root/.woodpecker/maven-settings.xml"

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

changed_services=0
for service_dir in */; do
  [ -f "${service_dir}mvnw" ] || continue

  if ! git diff --quiet "$merge_base" HEAD -- "$service_dir"; then
    changed_services=1
    service_dir="${service_dir%/}"
    printf 'Running %s/mvn -s %s -B clean verify\n' "$service_dir" "$maven_settings"
    if ! (
      cd "$service_dir"
      mvn -s "$maven_settings" -B clean verify
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
