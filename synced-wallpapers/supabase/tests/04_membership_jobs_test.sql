-- Membership effects, scheduled transitions, purge queue, RLS on profiles/storage.
begin;
\ir _helpers.psql
select plan(19);

select tests.set_id('a', tests.new_user('a@example.com', 'Anu'));
select tests.set_id('b', tests.new_user('b@example.com', 'Ben'));
select tests.set_id('c', tests.new_user('c@example.com', 'Cyd'));
select tests.set_id('stranger', tests.new_user('s@example.com', 'Sam'));
select tests.set_id('g', tests.group_with(tests.id('a'), array[tests.id('b'), tests.id('c')]));
update public.profiles set phone_optional = '+919812345678' where id = tests.id('b');

-- Privacy -------------------------------------------------------------------------------
select is(tests.as_user_int(tests.id('stranger'), $q$ select count(*) from public.profiles where phone_optional is not null $q$), 0::bigint,
  'phone numbers are invisible to people outside your groups');
select is(tests.as_user_int(tests.id('a'), $q$ select count(*) from public.profiles where phone_optional is not null $q$), 1::bigint,
  'and visible to group members');
select is(tests.as_user_int(tests.id('stranger'), $q$ select count(*) from public.groups $q$), 0::bigint, 'non-members see no groups');
select throws_ok($$ select tests.as_user(tests.id('a'), $q$ update public.proposals set status = 'APPROVED' $q$) $$,
  '42501', null, 'clients cannot change proposal status directly');
select throws_ok($$ select tests.as_user(tests.id('a'), $q$ update public.profiles set email = 'x@y.z' where id = auth.uid() $q$) $$,
  '42501', null, 'clients cannot change their email column');
select lives_ok($$ select tests.as_user(tests.id('a'), $q$ update public.profiles set name = 'Anu R' where id = auth.uid() $q$) $$,
  'clients can edit their own display name');

-- Storage: only the proposer uploads, only for their own not-yet-confirmed proposal ----------
select set_config('tests.path', tests.as_user_json(tests.id('b'),
  $q$ select public.create_proposal(tests.id('g'), now() + interval '1 day', now() + interval '1 day 2 hours', 'HOME') $q$) ->> 'image_path', true);
select ok(not tests.as_user_json(tests.id('a'), $q$ select to_jsonb(public.can_upload_image(current_setting('tests.path'))) $q$)::boolean,
  'another member cannot upload to someone else''s proposal');
select ok(tests.as_user_json(tests.id('b'), $q$ select to_jsonb(public.can_upload_image(current_setting('tests.path'))) $q$)::boolean,
  'the proposer can upload while creating the proposal');
select ok(not tests.as_user_json(tests.id('stranger'), $q$ select to_jsonb(public.can_read_image(current_setting('tests.path'))) $q$)::boolean,
  'non-members cannot read images');
select throws_ok($$ select tests.as_user(tests.id('b'), $q$ select public.confirm_proposal_image((select id from public.proposals where image_path = current_setting('tests.path'))) $q$) $$,
  'P0001', 'IMAGE_NOT_UPLOADED', 'a proposal is only announced once its image exists');

-- Proposer leaves while their schedule is live → cancelled for everyone ---------------------
select tests.set_id('p', tests.propose(tests.id('c'), tests.id('g'), interval '3 days'));
select tests.vote(tests.id('a'), tests.id('p'), 'APPROVE');
select tests.vote(tests.id('b'), tests.id('p'), 'APPROVE');
select tests.shift(tests.id('p'), now() - interval '10 minutes', now() + interval '1 hour');
select (public._tick() ->> 'live')::int;
select is(tests.status(tests.id('p')), 'LIVE', 'tick moves APPROVED → LIVE at the start');
select tests.as_user(tests.id('c'), $q$ select public.leave_group(tests.id('g')) $q$);
select is(tests.status(tests.id('p')), 'CANCELLED', 'the leaver''s live schedule is cancelled');
select is(tests.outbox_count(tests.id('a'), 'PROPOSAL_CANCELLED'), 1, 'remaining members get the cancel (devices restore immediately)');
select is(tests.outbox_count(tests.id('c'), 'LEFT_GROUP'), 1, 'the leaver''s device is told to clean up too');

-- Pending proposal recomputes unanimity when a non-voter leaves ------------------------------
select tests.set_id('d', tests.new_user('d@example.com', 'Dia'));
insert into public.group_members (group_id, user_id) values (tests.id('g'), tests.id('d'));
select tests.set_id('p2', tests.propose(tests.id('a'), tests.id('g'), interval '5 days'));
select tests.vote(tests.id('b'), tests.id('p2'), 'APPROVE');
select tests.as_user(tests.id('d'), $q$ select public.leave_group(tests.id('g')) $q$);
select is(tests.status(tests.id('p2')), 'APPROVED', 'when the last missing voter leaves, the proposal is approved');

-- Expiry and purge --------------------------------------------------------------------------
select tests.set_id('p3', tests.propose(tests.id('b'), tests.id('g'), interval '7 days'));
select tests.shift(tests.id('p3'), now() + interval '10 minutes', now() + interval '2 hours');
select public._tick();
select is(tests.status(tests.id('p3')), 'EXPIRED', 'no unanimity by start − 15 min → EXPIRED');
select ok((select image_path from public.proposals where id = tests.id('p3')) in (select * from public._purge_candidates()),
  'the expired image is due for purge immediately');

select tests.shift(tests.id('p2'), now() - interval '3 hours', now() - interval '2 hours');
select public._tick();
select is(tests.status(tests.id('p2')), 'ENDED', 'tick moves to ENDED after the end');
select public._mark_purged(array(select public._purge_candidates()));
select is(tests.status(tests.id('p2')), 'PURGED', 'after the 1h grace period the image is purged → PURGED');

select * from finish();
rollback;
