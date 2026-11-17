#!/usr/bin/env bash
# Real concurrency check with two connections (pgTAP runs in one session, so it can't).
#  1. Two members propose the same slot at the same moment → exactly one succeeds.
#  2. One user approves overlapping proposals in two groups at the same moment → one is refused.
# Uses the same plain-Postgres setup as test-db-native.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
export PGHOST=${PGHOST:-/var/tmp/pgsw} PGPORT=${PGPORT:-54329} PGUSER=${PGUSER:-postgres}
export PGOPTIONS='-c client_min_messages=warning'
DB=sw_race_$$
psql -q -d postgres -c "create database $DB"
trap 'psql -q -d postgres -c "drop database if exists $DB" >/dev/null' EXIT
psql -q -v ON_ERROR_STOP=1 -d "$DB" -f scripts/native-bootstrap.sql >/dev/null 2>&1
for f in supabase/migrations/*.sql; do psql -q -v ON_ERROR_STOP=1 -d "$DB" -f "$f"; done

q() { psql -X -q -tA -v ON_ERROR_STOP=1 -d "$DB" -c "$1"; }
as() { echo "select set_config('role','authenticated',true), set_config('request.jwt.claims', json_build_object('sub','$1')::text, true);"; }

A=$(q "insert into auth.users(email) values ('a@x.io') returning id")
B=$(q "insert into auth.users(email) values ('b@x.io') returning id")
C=$(q "insert into auth.users(email) values ('c@x.io') returning id")
G1=$(q "insert into groups(name,time_zone,admin_id) values ('One','UTC','$A') returning id")
G2=$(q "insert into groups(name,time_zone,admin_id) values ('Two','UTC','$C') returning id")
q "insert into group_members(group_id,user_id) values ('$G1','$A'),('$G1','$B'),('$G2','$C'),('$G2','$A')" >/dev/null
START="now() + interval '1 day'"; END="now() + interval '1 day 2 hours'"

echo "== 1. same slot, same moment"
run_propose() { # $1 user; holds its transaction open for a second before committing
  psql -X -q -tA -d "$DB" 2>&1 <<SQL || true
begin;
$(as "$1") 
select 'created ' || (public.create_proposal('$G1', $START, $END, 'HOME') ->> 'proposal_id');
select pg_sleep(1);
commit;
SQL
}
run_propose "$A" > /tmp/race_a.txt & run_propose "$B" > /tmp/race_b.txt & wait
cat /tmp/race_a.txt /tmp/race_b.txt | grep -E "created|ERROR" | sed 's/^/   /'
N=$(q "select count(*) from proposals where group_id = '$G1' and status = 'PENDING'")
[ "$N" = 1 ] && echo "   PASS: exactly one proposal holds the slot" || { echo "   FAIL: $N proposals"; exit 1; }

echo "== 2. one user approves overlapping proposals in two groups at once"
S2="now() + interval '3 days'"; E2="now() + interval '3 days 2 hours'"
P1=$(psql -X -q -tA -d "$DB" -c "$(as "$B") select public.create_proposal('$G1', $S2, $E2, 'HOME') ->> 'proposal_id';" | tail -1)
P2=$(psql -X -q -tA -d "$DB" -c "$(as "$C") select public.create_proposal('$G2', $S2, $E2, 'HOME') ->> 'proposal_id';" | tail -1)
q "update proposals set image_ready = true where id in ('$P1', '$P2')" >/dev/null
run_vote() { # $1 proposal
  psql -X -q -tA -d "$DB" 2>&1 <<SQL || true
begin;
$(as "$A")
select 'approved ' || (public.cast_vote('$1', 'APPROVE') ->> 'proposal_id');
select pg_sleep(1);
commit;
SQL
}
run_vote "$P1" > /tmp/race_c.txt & run_vote "$P2" > /tmp/race_d.txt & wait
cat /tmp/race_c.txt /tmp/race_d.txt | grep -E "approved|ERROR" | sed 's/^/   /'
COMMITTED=$(q "select count(*) from public._user_commitments('$A') where start_at > now() + interval '2 days'")
[ "$COMMITTED" = 1 ] && echo "   PASS: A holds exactly one of the overlapping commitments" || { echo "   FAIL: $COMMITTED commitments"; exit 1; }
