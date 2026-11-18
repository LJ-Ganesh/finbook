-- Builds a realistic scenario and prints, per RPC/table the app reads, the JSON exactly
-- as PostgREST would return it. Output feeds app/src/test/resources/fixtures/.
\set QUIET on
\pset tuples_only on
\pset format unaligned
set timezone = 'UTC';
select set_config('fx.a', tests_a::text, false) from (select gen_random_uuid() tests_a) x \gset
insert into auth.users (id, email, raw_user_meta_data) values (current_setting('fx.a')::uuid, 'a@example.com', '{"full_name":"Asha","avatar_url":"https://example.com/a.png"}');
select set_config('fx.b', gen_random_uuid()::text, false);
insert into auth.users (id, email, raw_user_meta_data) values (current_setting('fx.b')::uuid, 'b@example.com', '{"full_name":"Bala"}');
select set_config('fx.c', gen_random_uuid()::text, false);
insert into auth.users (id, email, raw_user_meta_data) values (current_setting('fx.c')::uuid, 'c@example.com', '{"full_name":"Chitra"}');
update public.profiles set phone_optional = '+919812345678' where id = current_setting('fx.b')::uuid;
create function pg_temp.as_(u text) returns void language plpgsql as $$ begin
  perform set_config('role','authenticated',false); perform set_config('request.jwt.claims', json_build_object('sub',u)::text, false); end $$;
create function pg_temp.root() returns void language plpgsql as $$ begin perform set_config('role','postgres',false); end $$;

select pg_temp.as_(current_setting('fx.a'));
select set_config('fx.g', public.create_group('Family', 'Asia/Kolkata')::text, false);
select set_config('fx.code', public.create_invite(current_setting('fx.g')::uuid) ->> 'code', false);
select pg_temp.as_(current_setting('fx.b'));
select set_config('fx.req', public.redeem_invite(current_setting('fx.code'))::text, false);
select public.accept_join_terms(current_setting('fx.req')::uuid, true);
select pg_temp.as_(current_setting('fx.a'));
select set_config('fx.p', (public.create_proposal(current_setting('fx.g')::uuid, now() + interval '1 day', now() + interval '1 day 2 hours', 'BOTH', 0.3, 0.7, 'Happy Diwali', 1::smallint) ->> 'proposal_id'), false);
select pg_temp.root();
insert into storage.objects (bucket_id, name) select 'proposal-images', image_path from public.proposals where id = current_setting('fx.p')::uuid;
select pg_temp.as_(current_setting('fx.a'));
select public.confirm_proposal_image(current_setting('fx.p')::uuid);
select set_config('fx.p2', (public.create_proposal(current_setting('fx.g')::uuid, now() + interval '3 days', now() + interval '3 days 1 hour', 'HOME') ->> 'proposal_id'), false);
select pg_temp.root();
insert into storage.objects (bucket_id, name) select 'proposal-images', image_path from public.proposals where id = current_setting('fx.p2')::uuid;
select pg_temp.as_(current_setting('fx.a'));
select public.confirm_proposal_image(current_setting('fx.p2')::uuid);
select pg_temp.as_(current_setting('fx.b'));
select public.cast_vote(current_setting('fx.p')::uuid, 'APPROVE');
select public.suggest_time(current_setting('fx.p2')::uuid, now() + interval '4 days', now() + interval '4 days 1 hour');
select public.report_delivery(current_setting('fx.p')::uuid, 'READY');
select pg_temp.as_(current_setting('fx.a'));
select set_config('fx.act', public.create_admin_action(current_setting('fx.g')::uuid, 'RENAME', '{"name":"Family 2.0"}')::text, false);
select set_config('fx.code2', public.create_invite(current_setting('fx.g')::uuid) ->> 'code', false);
select pg_temp.as_(current_setting('fx.c'));
select set_config('fx.req2', public.redeem_invite(current_setting('fx.code2'))::text, false);
select pg_temp.as_(current_setting('fx.a'));

\o app/src/test/resources/fixtures/profiles.json
select json_agg(p) from public.profiles p;
\o app/src/test/resources/fixtures/groups.json
select json_agg(g) from public.groups g;
\o app/src/test/resources/fixtures/group_members.json
select json_agg(m) from public.group_members m;
\o app/src/test/resources/fixtures/proposals.json
select json_agg(p) from public.proposals p;
\o app/src/test/resources/fixtures/admin_actions.json
select json_agg(a) from public.admin_actions a;
\o app/src/test/resources/fixtures/notification_outbox.json
select json_agg(o) from public.notification_outbox o;
\o app/src/test/resources/fixtures/create_invite.json
select public.create_invite(current_setting('fx.g')::uuid);
\o app/src/test/resources/fixtures/my_pending_counts.json
select json_agg(x) from public.my_pending_counts() x;
\o app/src/test/resources/fixtures/proposal_vote_summary.json
select public.proposal_vote_summary(current_setting('fx.p')::uuid);
\o app/src/test/resources/fixtures/admin_action_summary.json
select public.admin_action_summary(current_setting('fx.act')::uuid);
\o app/src/test/resources/fixtures/group_join_requests.json
select json_agg(x) from public.group_join_requests(current_setting('fx.g')::uuid) x;
\o app/src/test/resources/fixtures/group_taken_slots.json
select json_agg(x) from public.group_taken_slots(current_setting('fx.g')::uuid, now(), now() + interval '30 days') x;
\o app/src/test/resources/fixtures/my_upcoming_commitments.json
select json_agg(x) from public.my_upcoming_commitments() x;
\o app/src/test/resources/fixtures/delivery_summary.json
select public.delivery_summary(current_setting('fx.p')::uuid);
\o app/src/test/resources/fixtures/proposal_conflicts.json
select public.proposal_conflicts(current_setting('fx.p2')::uuid);
\o app/src/test/resources/fixtures/server_now.json
select to_json(public.server_now());
\o app/src/test/resources/fixtures/create_proposal.json
select public.create_proposal(current_setting('fx.g')::uuid, now() + interval '9 days', now() + interval '9 days 1 hour', 'LOCK');
\o
select pg_temp.as_(current_setting('fx.b'));
\o app/src/test/resources/fixtures/my_schedules.json
select json_agg(x) from public.my_schedules() x;
\o app/src/test/resources/fixtures/time_suggestions.json
select json_agg(t) from public.time_suggestions t;
\o
select pg_temp.as_(current_setting('fx.c'));
\o app/src/test/resources/fixtures/join_request_preview.json
select public.join_request_preview(current_setting('fx.req2')::uuid);
\o app/src/test/resources/fixtures/my_join_requests.json
select json_agg(x) from public.my_join_requests() x;
\o
