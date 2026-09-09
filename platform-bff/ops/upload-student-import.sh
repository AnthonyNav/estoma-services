#!/bin/sh
set -eu

usage() {
  echo "usage: BFF_BASE_URL=https://... BFF_TOKEN_FILE=/secure/token $0 FILE_REFERENCE IDEMPOTENCY_KEY WORKBOOK.xlsx" >&2
  exit 2
}

[ "$#" -eq 3 ] || usage
: "${BFF_BASE_URL:?BFF_BASE_URL is required}"
: "${BFF_TOKEN_FILE:?BFF_TOKEN_FILE is required}"

file_reference=$1
idempotency_key=$2
workbook=$3

case "$file_reference" in
  ????????-????-????-????-????????????) ;;
  *) echo "FILE_REFERENCE must be a UUID" >&2; exit 2 ;;
esac
[ -n "$idempotency_key" ] && [ "${#idempotency_key}" -le 200 ] || {
  echo "IDEMPOTENCY_KEY must contain 1..200 characters" >&2
  exit 2
}
[ -f "$workbook" ] || { echo "workbook is not a regular file" >&2; exit 2; }
[ -r "$BFF_TOKEN_FILE" ] || { echo "BFF_TOKEN_FILE is not readable" >&2; exit 2; }
token=$(cat "$BFF_TOKEN_FILE")
[ -n "$token" ] || { echo "BFF_TOKEN_FILE is empty" >&2; exit 2; }
[ "$(printf '%s' "$token" | wc -l)" -eq 0 ] || {
  echo "BFF_TOKEN_FILE must contain exactly one token" >&2
  exit 2
}
[ "$(printf '%s' "$token" | tr -d '\r' | wc -c)" -eq "$(printf '%s' "$token" | wc -c)" ] || {
  echo "BFF_TOKEN_FILE contains a carriage return" >&2
  exit 2
}

size=$(wc -c < "$workbook")
[ "$size" -gt 0 ] || { echo "workbook is empty" >&2; exit 2; }
[ "$size" -le 20971520 ] || { echo "workbook exceeds the 20 MiB V1 limit" >&2; exit 2; }

header_file=$(mktemp)
trap 'rm -f "$header_file"' EXIT HUP INT TERM
chmod 600 "$header_file"
{
  printf 'Authorization: Bearer %s\n' "$token"
} > "$header_file"
unset token

curl --silent --show-error --fail-with-body \
  --request PUT \
  --header "@$header_file" \
  --header "Idempotency-Key: $idempotency_key" \
  --header "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
  --data-binary "@$workbook" \
  "${BFF_BASE_URL%/}/api/v1/admin/academic/import-files/$file_reference"
printf '\n'
