-- Invites and joining: unanimous approval + the joiner accepts upcoming schedules.
begin;
\ir _helpers.psql
select plan(17);

select tests.set_id('admin', tests.new_user('admin@example.com', 'Asha'));
select tests.set_id('m1', tests.new_user('m1@example.com', 'Bala'));
select tests.set_id('joiner', tests.new_user('joiner@example.com', 'Jai'));
select tests.set_id('g', tests.group_with(tests.id('admin'), array[tests.id('m1')]));
select tests.set_id('other', tests.group_with(tests.id('joiner'), '{}', 'Work'));

-- Only the admin creates invites -------------------------------------------------------
select throws_ok($$ select tests.as_user(tests.id('m1'), $q$ select public.create_invite(tests.id('g')) $q$) $$,
  'P0001', 'NOT_ADMIN', 'members cannot create invites');
select set_config('tests.code', tests.as_user_json(tests.id('admin'), $q$ select public.create_invite(tests.id('g')) $q$) ->> 'code', true);
select ok(current_setting('tests.code') ~ '^[A-HJ-NP-Z2-9]{8}$', 'codes are 8 unambiguous characters');

-- An upcoming schedule the joiner must accept; it overlaps the joiner's own commitment.
select tests.set_id('sched', tests.propose(tests.id('admin'), tests.id('g'), interval '2 days'));
select tests.vote(tests.id('m1'), tests.id('sched'), 'APPROVE');
select tests.set_id('mine', tests.propose(tests.id('joiner'), tests.id('other'), interval '2 days 1 hour'));
select is(tests.status(tests.id('mine')), 'APPROVED', 'the joiner has an approved schedule in their own group (group of 1)');

select tests.set_id('req', tests.as_user_json(tests.id('joiner'), $q$ select to_jsonb(public.redeem_invite(current_setting('tests.code'))) $q$) #>> '{}');
select throws_ok($$ select tests.as_user(tests.new_user('late@example.com'), $q$ select public.redeem_invite(current_setting('tests.code')) $q$) $$,
  'P0001', 'INVITE_USED', 'codes are single use');
select is(tests.outbox_count(tests.id('m1'), 'JOIN_REQUEST'), 1, 'members are asked to approve');
select is(jsonb_array_length(tests.as_user_json(tests.id('joiner'), $q$ select public.join_request_preview(tests.id('req')) $q$) -> 'schedules'), 1,
  'the joiner sees all upcoming schedules');
select is(tests.as_user_int(tests.id('joiner'), $q$ select count(*) from public.proposals $q$), 1::bigint,
  'but cannot read the group''s proposal rows (only their own group''s)');
select ok(tests.as_user_json(tests.id('joiner'), format($q$ select to_jsonb(public.can_read_image(%L)) $q$,
  (select image_path from public.proposals where id = tests.id('sched'))))::boolean,
  'the joiner may read the images of schedules they are asked to accept');
select throws_ok($$ select tests.as_user(tests.id('joiner'), $q$ select public.accept_join_terms(tests.id('req'), true) $q$) $$,
  'P0001', 'PERSONAL_CONFLICT', 'accepting is blocked while schedules overlap the joiner''s commitments');

-- Resolve by skipping the other schedule, then accept.
select tests.as_user(tests.id('joiner'), $q$ select public.skip_on_my_phone(tests.id('mine')) $q$);
select lives_ok($$ select tests.as_user(tests.id('joiner'), $q$ select public.accept_join_terms(tests.id('req'), true) $q$) $$,
  'after skipping the overlap, the joiner can accept');
select ok(not public.is_member(tests.id('g'), tests.id('joiner')), 'not a member until every member approves');
select tests.as_user(tests.id('m1'), $q$ select public.vote_join(tests.id('req'), 'APPROVE') $q$);
select ok(public.is_member(tests.id('g'), tests.id('joiner')), 'joined after unanimous approval (inviter approved by inviting)');

-- A reject anywhere stops a join ---------------------------------------------------------
select tests.set_id('x', tests.new_user('x@example.com', 'Xavi'));
select set_config('tests.code2', tests.as_user_json(tests.id('admin'), $q$ select public.create_invite(tests.id('g')) $q$) ->> 'code', true);
select tests.set_id('req2', tests.as_user_json(tests.id('x'), $q$ select to_jsonb(public.redeem_invite(current_setting('tests.code2'))) $q$) #>> '{}');
select tests.as_user(tests.id('x'), $q$ select public.accept_join_terms(tests.id('req2'), true) $q$);
select tests.as_user(tests.id('joiner'), $q$ select public.vote_join(tests.id('req2'), 'REJECT', 'Don''t know them') $q$);
select is((select status from public.join_requests where id = tests.id('req2')), 'REJECTED', 'one reject → join rejected');
select ok(not exists (select 1 from public.notification_outbox where user_id = tests.id('x') and payload::text ~ tests.id('joiner')::text),
  'the joiner is not told who rejected');

-- Declining the terms cancels the join ----------------------------------------------------
select tests.set_id('y', tests.new_user('y@example.com', 'Yami'));
select set_config('tests.code3', tests.as_user_json(tests.id('admin'), $q$ select public.create_invite(tests.id('g')) $q$) ->> 'code', true);
select tests.set_id('req3', tests.as_user_json(tests.id('y'), $q$ select to_jsonb(public.redeem_invite(current_setting('tests.code3'))) $q$) #>> '{}');
select tests.as_user(tests.id('y'), $q$ select public.accept_join_terms(tests.id('req3'), false) $q$);
select is((select status from public.join_requests where id = tests.id('req3')), 'CANCELLED', 'declining the schedules cancels the join');

-- Expired code ------------------------------------------------------------------------------
select set_config('tests.code4', tests.as_user_json(tests.id('admin'), $q$ select public.create_invite(tests.id('g')) $q$) ->> 'code', true);
update public.invites set expires_at = now() - interval '1 second' where code = current_setting('tests.code4');
select throws_ok($$ select tests.as_user(tests.id('y'), $q$ select public.redeem_invite(current_setting('tests.code4')) $q$) $$,
  'P0001', 'INVITE_EXPIRED', 'codes expire after 24h');

-- Email invite before sign-up → appears after sign-up ---------------------------------------
select tests.as_user(tests.id('admin'), $q$ select public.create_invite(tests.id('g'), 'Newbie@Example.com') $q$);
select tests.set_id('newbie', tests.new_user('newbie@example.com', 'Nia'));
select is((select count(*) from public.join_requests where user_id = tests.id('newbie') and group_id = tests.id('g') and status = 'PENDING'), 1::bigint,
  'the email invite becomes a join request when that email signs up');

select * from finish();
rollback;
