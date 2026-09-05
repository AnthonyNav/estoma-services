#!/bin/sh
set -eu

# Exercise the real wrapper/trap with inert external tools and fixture stubs.
case "$(basename "$0")" in
  docker)
    case "$*" in *E2E_PHASE=decision*) exit 9 ;; esac
    exit 0 ;;
  curl) printf '200'; exit 0 ;;
  jq)
    case "$*" in
      *'.accountId'*)
        case "$*" in *supervisor*) printf '00000000-0000-4000-8000-000000000002\n' ;; *) printf '00000000-0000-4000-8000-000000000001\n' ;; esac ;;
      *'.accessToken'*) printf 'synthetic-token\n' ;;
      *) printf '{"values":[]}\n' ;;
    esac
    exit 0 ;;
  fixture-db.sh)
    case "$0" in
      */wash-student/*) fixture_manifest=$E2E_FIXTURE_MANIFEST; fixture_kind=student ;;
      */wash-supervisor/*) fixture_manifest=$E2E_SUPERVISOR_FIXTURE_MANIFEST; fixture_kind=supervisor ;;
      */wash-supervisor-entry/*|*/wash-supervisor-authorized/*) fixture_manifest=$E2E_ENTRY_FIXTURE_MANIFEST; fixture_kind=entry ;;
    esac
    case "$1" in
      bootstrap)
        if [ "$fixture_kind" = entry ] && [ "$TEST_MODE" = bootstrap-failure ]; then exit 7; fi
        printf 'RUN_KEY=e2e-wash-runner\nCOURSE_SECTION_ID=test\nSLOT_ID=test\nNRC=E2E-test\nCABIN_ID=test\nTANK_ID=test\n' > "$fixture_manifest" ;;
      cleanup)
        printf '%s\n' "$fixture_kind" >> "$TEST_STATE/cleanup-order"
        if [ "$fixture_kind" = entry ] && [ "$TEST_MODE" = cleanup-failure ]; then exit 8; fi
        rm -f "$fixture_manifest" ;;
    esac
    exit 0 ;;
esac

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
test_script="$script_dir/$(basename "$0")"
test_root=$(mktemp -d)
trap 'rm -rf -- "$test_root"' EXIT INT TERM
mkdir -p "$test_root/e2e/runner" "$test_root/bin" "$test_root/private" "$test_root/e2e/postman/workflows"
cp "$script_dir/../runner/run-wash-supervisor-entry.sh" "$test_root/e2e/runner/"
for fixture_kind in wash-student wash-supervisor wash-supervisor-entry wash-supervisor-authorized; do
  mkdir -p "$test_root/e2e/fixtures/$fixture_kind"
  cp "$test_script" "$test_root/e2e/fixtures/$fixture_kind/fixture-db.sh"
  chmod +x "$test_root/e2e/fixtures/$fixture_kind/fixture-db.sh"
done
for mock in docker curl jq; do
  cp "$test_script" "$test_root/bin/$mock"
  chmod +x "$test_root/bin/$mock"
done

for outcome in rejected authorized; do
for test_mode in cleanup-failure bootstrap-failure decision-failure; do
  test_state="$test_root/$outcome-$test_mode"
  mkdir -p "$test_state"
  if PATH="$test_root/bin:$PATH" TMPDIR="$test_root/private" \
      TEST_MODE="$test_mode" TEST_STATE="$test_state" \
      E2E_ENTRY_OUTCOME="$outcome" E2E_ISOLATED_NAMESPACE=wash-e2e \
      BFF_BASE_URL=http://127.0.0.1:1 E2E_REPORT_DIR="$test_state" \
      E2E_STUDENT_LOGIN_IDENTIFIER=e2e-student E2E_STUDENT_PASSWORD=synthetic \
      E2E_SUPERVISOR_LOGIN_IDENTIFIER=e2e-supervisor E2E_SUPERVISOR_PASSWORD=synthetic \
      E2E_PSQL_EXEC="$test_root/bin/docker" \
      sh "$test_root/e2e/runner/run-wash-supervisor-entry.sh" > "$test_state/output" 2>&1; then
    printf 'runner unexpectedly succeeded in %s\n' "$test_mode" >&2; exit 1
  fi
  case "$test_mode" in
    cleanup-failure)
      [ "$(cat "$test_state/cleanup-order")" = entry ]
      [ -f "$test_state/student-fixture.env" ] && [ -f "$test_state/supervisor-fixture.env" ] && [ -f "$test_state/entry-fixture.env" ] ;;
    bootstrap-failure)
      [ ! -f "$test_state/cleanup-order" ]
      [ -f "$test_state/student-fixture.env" ] && [ -f "$test_state/supervisor-fixture.env" ] ;;
    decision-failure)
      [ "$(tr '\n' ',' < "$test_state/cleanup-order")" = 'entry,student,supervisor,' ]
      [ ! -f "$test_state/student-fixture.env" ] && [ ! -f "$test_state/supervisor-fixture.env" ] && [ ! -f "$test_state/entry-fixture.env" ] ;;
  esac
  [ -z "$(ls -A "$test_root/private")" ] || { printf 'runner leaked private runtime files\n' >&2; exit 1; }
done
done
if E2E_ENTRY_OUTCOME=authorized E2E_PSQL_EXEC=/bin/false \
  sh "$test_root/e2e/runner/run-wash-supervisor-entry.sh" > "$test_root/refused" 2>&1; then
  printf 'authorized runner accepted an executor that failed isolation checks\n' >&2; exit 1
fi
printf 'Rejected and authorized entry runner failure/recovery and isolation-refusal tests passed.\n'
