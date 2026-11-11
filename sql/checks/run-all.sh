#!/usr/bin/env bash
#
# Runs every check in this directory and fails if any assertion is false.
#
# Each check is only worth anything if someone runs it, and a guarantee should
# not depend on being REMEMBERED. This turns them from documentation into
# something that can fail.
#
#   ./sql/checks/run-all.sh
#
# Every check follows the same contract: it returns one or more rows whose
# boolean columns must all read `t`. A single `f` anywhere fails the run.
#
# Reads the same defaults as the app, overridable the same way:
#   CARDTRACKER_DB_CONTAINER   (default: tcg-workspace-db)
#   CARDTRACKER_DB_USER        (default: cardtracker)
#   CARDTRACKER_DB_NAME        (default: cardtracker)
set -uo pipefail

CONTAINER="${CARDTRACKER_DB_CONTAINER:-tcg-workspace-db}"
DB_USER="${CARDTRACKER_DB_USER:-cardtracker}"
DB_NAME="${CARDTRACKER_DB_NAME:-cardtracker}"

cd "$(dirname "$0")/../.." || exit 2

if ! docker exec "$CONTAINER" true 2>/dev/null; then
  echo "FATAL: container '$CONTAINER' is not running. Start it with: docker compose up -d" >&2
  exit 2
fi

failed=0
ran=0

for file in sql/checks/*.sql; do
  [ -e "$file" ] || continue
  name="$(basename "$file" .sql)"
  ran=$((ran + 1))

  # -A unaligned, -t tuples only, -F'|' so every value is a discrete field.
  # ON_ERROR_STOP makes a broken query a failure rather than empty output that
  # would otherwise look like a pass.
  output="$(docker exec -i "$CONTAINER" \
      psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -A -F'|' -t -f - \
      < "$file" 2>&1)"
  status=$?

  if [ $status -ne 0 ]; then
    printf '  FAIL  %-46s (query error)\n' "$name"
    echo "$output" | sed 's/^/          /'
    failed=$((failed + 1))
    continue
  fi

  # A check that returns nothing has asserted nothing - treat that as a
  # failure rather than quietly reporting success.
  if [ -z "${output//[[:space:]]/}" ]; then
    printf '  FAIL  %-46s (no rows returned)\n' "$name"
    failed=$((failed + 1))
    continue
  fi

  # Any field that is exactly `f` is a false assertion. Field-exact rather than
  # a substring match, so a set legitimately named e.g. "Fossil" cannot trip it.
  if echo "$output" | tr '|' '\n' | grep -qx 'f'; then
    printf '  FAIL  %-46s\n' "$name"
    echo "$output" | sed 's/^/          /'
    failed=$((failed + 1))
  else
    printf '  pass  %-46s\n' "$name"
  fi
done

echo
if [ "$ran" -eq 0 ]; then
  echo "FATAL: no checks found in sql/checks/" >&2
  exit 2
fi

if [ "$failed" -ne 0 ]; then
  echo "$failed of $ran checks FAILED"
  exit 1
fi

echo "all $ran checks pass"
