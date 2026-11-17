-- Admin actions: only the admin starts them, everyone else must approve.
begin;
\ir _helpers.psql
select plan(25);

select tests.set_id('admin', tests.new_user('admin@example.com', 'Asha'));
select tests.set_id('m1', tests.new_user('m1@example.com', 'Bala'));   -- longest-standing after admin
select tests.set_id('m2', tests.new_user('m2@example.com', 'Chitra'));
select tests.set_id('m3', tests.new_user('m3@example.com', 'Dev'));
select tests.set_id('g', tests.group_with(tests.id('admin'), array[tests.id('m1'), tests.id('m2'), tests.id('m3')]));

-- Only the admin --------------------------------------------------------------------
select throws_ok($$ select tests.as_user(tests.id('m1'), $q$ select public.create_admin_action(tests.id('g'), 'RENAME', '{"name":"Hacked"}') $q$) $$,
  'P0001', 'NOT_ADMIN', 'a non-admin calling create_admin_action directly is refused');
select throws_ok($$ select tests.as_user(tests.id('m1'), $q$ insert into public.admin_actions (group_id, type, created_by, deadline) values (tests.id('g'), 'RENAME', tests.id('m1'), now()) $q$) $$,
  '42501', null, 'an authenticated client cannot insert admin actions directly');

-- Rename: needs all other members --------------------------------------------------
select tests.set_id('a_rename', tests.as_user_json(tests.id('admin'), $q$ select to_jsonb(public.create_admin_action(tests.id('g'), 'RENAME', '{"name":"Family 2.0"}')) $q$) #>> '{}');
select is((select payload ->> 'previous' from public.admin_actions where id = tests.id('a_rename')), 'Family', 'the payload keeps the before value');
select throws_ok($$ select tests.as_user(tests.id('admin'), $q$ select public.create_admin_action(tests.id('g'), 'RENAME', '{"name":"Other"}') $q$) $$,
  'P0001', 'ACTION_ALREADY_PENDING', 'one pending action of each type');
select throws_ok($$ select tests.as_user(tests.id('admin'), $q$ select public.cast_admin_vote(tests.id('a_rename'), 'APPROVE') $q$) $$,
  'P0001', 'NOT_A_VOTER', 'the admin does not vote on their own action');
select tests.as_user(tests.id('m1'), $q$ select public.cast_admin_vote(tests.id('a_rename'), 'APPROVE') $q$);
select tests.as_user(tests.id('m2'), $q$ select public.cast_admin_vote(tests.id('a_rename'), 'APPROVE') $q$);
select is((select name from public.groups where id = tests.id('g')), 'Family', 'not applied before everyone approved');
select tests.as_user(tests.id('m3'), $q$ select public.cast_admin_vote(tests.id('a_rename'), 'APPROVE') $q$);
select is((select name from public.groups where id = tests.id('g')), 'Family 2.0', 'applied after unanimous approval');

-- Remove member; one member rejects → the member stays --------------------------------
select tests.set_id('a_rm', tests.as_user_json(tests.id('admin'),
  format($q$ select to_jsonb(public.create_admin_action(tests.id('g'), 'REMOVE_MEMBER', '{"user_id":"%s"}')) $q$, tests.id('m3'))) #>> '{}');
select is(tests.as_user_int(tests.id('m3'), $q$ select count(*) from public.admin_actions where id = tests.id('a_rm') $q$), 0::bigint,
  'the member being removed cannot see the pending action');
select ok(not exists (select 1 from public.notification_outbox where user_id = tests.id('m3') and payload ->> 'action_id' = tests.id('a_rm')::text),
  'and is not notified of it');
select throws_ok($$ select tests.as_user(tests.id('m3'), $q$ select public.cast_admin_vote(tests.id('a_rm'), 'REJECT') $q$) $$,
  'P0001', 'NOT_A_VOTER', 'the member being removed does not vote');
select tests.as_user(tests.id('m1'), $q$ select public.cast_admin_vote(tests.id('a_rm'), 'APPROVE') $q$);
select tests.as_user(tests.id('m2'), $q$ select public.cast_admin_vote(tests.id('a_rm'), 'REJECT', 'They are fine') $q$);
select ok(public.is_member(tests.id('g'), tests.id('m3')), 'one rejection → the member stays');
select is((select status from public.admin_actions where id = tests.id('a_rm')), 'REJECTED', 'action is REJECTED');

-- Remove member; target leaves before the vote ends → closed as no longer needed ---------
select tests.set_id('a_rm2', tests.as_user_json(tests.id('admin'),
  format($q$ select to_jsonb(public.create_admin_action(tests.id('g'), 'REMOVE_MEMBER', '{"user_id":"%s"}')) $q$, tests.id('m3'))) #>> '{}');
select tests.as_user(tests.id('m3'), $q$ select public.leave_group(tests.id('g')) $q$);
select is((select result_note from public.admin_actions where id = tests.id('a_rm2')), 'NO_LONGER_NEEDED', 'the action closes as no longer needed');

-- Time zone change: instants stay, display changes ------------------------------------
select tests.set_id('p', tests.propose(tests.id('m1'), tests.id('g'), interval '2 days'));
select tests.vote(tests.id('admin'), tests.id('p'), 'APPROVE');
select tests.vote(tests.id('m2'), tests.id('p'), 'APPROVE');
select is(tests.status(tests.id('p')), 'APPROVED', 'schedule approved (3 members now)');
create temp table before_tz as select start_at, end_at from public.proposals where id = tests.id('p');
select tests.set_id('a_tz', tests.as_user_json(tests.id('admin'), $q$ select to_jsonb(public.create_admin_action(tests.id('g'), 'TIMEZONE', '{"time_zone":"Europe/London"}')) $q$) #>> '{}');
select tests.as_user(tests.id('m1'), $q$ select public.cast_admin_vote(tests.id('a_tz'), 'APPROVE') $q$);
select tests.as_user(tests.id('m2'), $q$ select public.cast_admin_vote(tests.id('a_tz'), 'APPROVE') $q$);
select is((select time_zone from public.groups where id = tests.id('g')), 'Europe/London', 'time zone changed');
select ok((select p.start_at = b.start_at and p.end_at = b.end_at from public.proposals p, before_tz b where p.id = tests.id('p')),
  'upcoming schedules keep the same absolute instants');
select throws_ok($$ select tests.as_user(tests.id('admin'), $q$ select public.create_admin_action(tests.id('g'), 'TIMEZONE', '{"time_zone":"Mars/Olympus"}') $q$) $$,
  'P0001', 'INVALID_TIME_ZONE', 'unknown zones are refused');

-- Member limit must be between the member count and 20 -------------------------------
select throws_ok($$ select tests.as_user(tests.id('admin'), $q$ select public.create_admin_action(tests.id('g'), 'MEMBER_LIMIT', '{"max_members":2}') $q$) $$,
  'P0001', 'INVALID_MEMBER_LIMIT', 'cannot go below the current member count');
select throws_ok($$ select tests.as_user(tests.id('admin'), $q$ select public.create_admin_action(tests.id('g'), 'MEMBER_LIMIT', '{"max_members":21}') $q$) $$,
  'P0001', 'INVALID_MEMBER_LIMIT', 'cannot exceed MAX_MEMBERS');

-- Transfer admin: the new admin must accept (their approval is required) ----------------
select tests.set_id('a_tr', tests.as_user_json(tests.id('admin'),
  format($q$ select to_jsonb(public.create_admin_action(tests.id('g'), 'TRANSFER_ADMIN', '{"user_id":"%s"}')) $q$, tests.id('m2'))) #>> '{}');
select tests.as_user(tests.id('m1'), $q$ select public.cast_admin_vote(tests.id('a_tr'), 'APPROVE') $q$);
select is((select admin_id from public.groups where id = tests.id('g')), tests.id('admin'), 'not transferred until the new admin accepts');
select tests.as_user(tests.id('m2'), $q$ select public.cast_admin_vote(tests.id('a_tr'), 'APPROVE') $q$);
select is((select admin_id from public.groups where id = tests.id('g')), tests.id('m2'), 'transferred once the new admin accepted');

-- Admin leaves → longest-standing member; they decline → the next one ------------------
select tests.as_user(tests.id('m2'), $q$ select public.leave_group(tests.id('g')) $q$);
select is((select admin_id from public.groups where id = tests.id('g')), tests.id('admin'),
  'admin passes to the longest-standing member');
select is(tests.outbox_count(tests.id('admin'), 'YOU_ARE_ADMIN'), 1, 'the new admin is told');
select tests.as_user(tests.id('admin'), $q$ select public.decline_admin(tests.id('g')) $q$);
select is((select admin_id from public.groups where id = tests.id('g')), tests.id('m1'), 'declining passes it to the next longest-standing member');

-- A group of 1 applies admin actions instantly -----------------------------------------
select tests.set_id('solo', tests.as_user_json(tests.id('m3'), $q$ select to_jsonb(public.create_group('Me time', 'Asia/Kolkata')) $q$) #>> '{}');
select tests.as_user(tests.id('m3'), $q$ select public.create_admin_action(tests.id('solo'), 'RENAME', '{"name":"Routine"}') $q$);
select is((select name from public.groups where id = tests.id('solo')), 'Routine', 'group of 1: applied instantly');

select * from finish();
rollback;
