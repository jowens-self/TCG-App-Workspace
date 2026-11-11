#!/usr/bin/env bash
#
# Applies pending migrations from sql/migrations/ in filename order.
#
#   ./sql/migrate.sh            apply everything pending
#   ./sql/migrate.sh --pending  list what would run, change nothing
#
# Why this exists: schema.sql is mounted at docker-entrypoint-initdb.d and runs
# ONCE, on an empty data directory. Once a database holds real history it is
# never rebuilt, so schema.sql never runs against it again. Without a runner,
# every change after the first boot is a psql command somebody remembers to
# type, on one machine, undocumented.
#
# schema.sql stays the canonical definition for a FRESH database. A migration
# is the same change expressed for a database that already exists. Both must be
# written, or a new container and this one diverge - which is why the header of
# each migration says what it mirrors in schema.sql.
#
# Each file runs inside a single transaction with ON_ERROR_STOP, so a migration
# either lands completely or not at all; there is no half-applied state to
# unpick by hand. Migrations should still be written idempotently (IF NOT
# EXISTS, ON CONFLICT), because the cheapest recovery from any doubt is running
# it again.
#
# Reads the same defaults as the app, overridable the same way:
#   CARDTRACKER_DB_CONTAINER   (default: tcg-workspace-db)
#   CARDTRACKER_DB_USER        (default: cardtracker)
#   CARDTRACKER_DB_NAME        (default: cardtracker)
set -uo pipefail

CONTAINER="${CARDTRACKER_DB_CONTAINER:-tcg-workspace-db}"
DB_USER="${CARDTRACKER_DB_USER:-cardtracker}"
DB_NAME="${CARDTRACKER_DB_NAME:-cardtracker}"

cd "$(dirname "$0")/.." || exit 2

PENDING_ONLY=0
[ "${1:-}" = "--pending" ] && PENDING_ONLY=1

if ! docker exec "$CONTAINER" true 2>/dev/null; then
  echo "FATAL: container '$CONTAINER' is not running. Start it with: docker compose up -d" >&2
  exit 2
fi

psql_q() { docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -A -t -q "$@"; }

# The ledger has to exist before it can be consulted, and it is the one piece
# of schema that cannot itself be a migration.
psql_q -c "CREATE TABLE IF NOT EXISTS schema_migrations (
               version    TEXT PRIMARY KEY,
               applied_at TIMESTAMPTZ NOT NULL DEFAULT now());" >/dev/null || {
  echo "FATAL: could not create schema_migrations" >&2; exit 2; }

applied=0
skipped=0
failed=0

for file in sql/migrations/*.sql; do
  [ -e "$file" ] || continue
  version="$(basename "$file" .sql)"

  seen="$(psql_q -c "SELECT 1 FROM schema_migrations WHERE version = '$version';")"
  if [ -n "${seen//[[:space:]]/}" ]; then
    skipped=$((skipped + 1))
    continue
  fi

  if [ "$PENDING_ONLY" -eq 1 ]; then
    printf '  pending  %s\n' "$version"
    applied=$((applied + 1))
    continue
  fi

  # BEGIN/COMMIT wrap the file and the ledger write together: a migration that
  # applied but went unrecorded would be re-run on the next invocation, which
  # is exactly the situation idempotency is not guaranteed to survive.
  output="$( { echo "BEGIN;"; cat "$file";
               echo "INSERT INTO schema_migrations (version) VALUES ('$version');";
               echo "COMMIT;"; } \
             | docker exec -i "$CONTAINER" \
                 psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -q 2>&1 )"

  if [ $? -ne 0 ]; then
    printf '  FAIL     %s\n' "$version"
    echo "$output" | sed 's/^/           /'
    failed=$((failed + 1))
    break   # stop at the first failure: later migrations may assume this one
  fi

  printf '  applied  %s\n' "$version"
  applied=$((applied + 1))
done

echo
if [ "$failed" -ne 0 ]; then
  echo "migration FAILED; database left unchanged by the failing file"
  exit 1
fi

# functions.sql has the same problem schema.sql does - mounted at init, run
# once - but not the same solution. It is 18 CREATE OR REPLACE and 5
# DROP ... IF EXISTS, with no table DDL, so re-running the whole file is
# idempotent and always leaves the database matching the repo. That makes
# copying function bodies into migrations unnecessary, and copying them the
# thing most likely to drift.
if [ "$PENDING_ONLY" -eq 0 ]; then
  if docker exec -i "$CONTAINER" \
       psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -q \
       < sql/functions.sql >/dev/null 2>&1; then
    echo "  functions reapplied from sql/functions.sql"
  else
    echo "  FAIL     sql/functions.sql did not apply" >&2
    docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" \
        -d "$DB_NAME" -q < sql/functions.sql 2>&1 | sed 's/^/           /' >&2
    exit 1
  fi
fi

if [ "$PENDING_ONLY" -eq 1 ]; then
  [ "$applied" -eq 0 ] && echo "nothing pending ($skipped already applied)" \
                       || echo "$applied pending, $skipped already applied"
  exit 0
fi

[ "$applied" -eq 0 ] && echo "already up to date ($skipped migrations)" \
                     || echo "$applied applied, $skipped already up to date"
