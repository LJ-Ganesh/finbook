#!/usr/bin/env bash
# Runs all migrations + pgTAP tests against a throwaway database on a plain local
# Postgres (with pgtap and pg_cron installed). Use this when Docker / the Supabase CLI
# isn't available; otherwise prefer:  supabase db reset && supabase test db
set -euo pipefail
cd "$(dirname "$0")/.."
PGHOST=${PGHOST:-/var/tmp/pgsw}; PGPORT=${PGPORT:-54329}; PGUSER=${PGUSER:-postgres}
export PGHOST PGPORT PGUSER
DB=sw_test_$$
export PGOPTIONS='-c client_min_messages=warning'
psql -q -d postgres -c "create database $DB" >/dev/null
trap 'psql -q -d postgres -c "drop database if exists $DB" >/dev/null' EXIT
psql -q -v ON_ERROR_STOP=1 -d "$DB" -f scripts/native-bootstrap.sql >/dev/null
psql -q -v ON_ERROR_STOP=1 -d "$DB" -c "create schema if not exists extensions; create extension pgtap with schema extensions; alter database $DB set search_path = public, extensions" >/dev/null
for f in supabase/migrations/*.sql; do
  psql -q -v ON_ERROR_STOP=1 -d "$DB" -f "$f"
done
# pg_cron can only be created in cron.database_name; don't fail if it isn't this DB.
pg_prove -d "$DB" --ext .sql supabase/tests/ "$@"
