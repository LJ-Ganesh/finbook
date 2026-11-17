-- Proposals: validation, group lane, unanimity, personal lane, deadlines, skips.
begin;
\ir _helpers.psql
select plan(30);

select tests.set_id('alice', tests.new_user('alice@example.com', 'Alice'));
select tests.set_id('bob',   tests.new_user('bob@example.com', 'Bob'));
select tests.set_id('cara',  tests.new_user('cara@example.com', 'Cara'));
select tests.set_id('g1', tests.group_with(tests.id('alice'), array[tests.id('bob'), tests.id('cara')], 'Family'));
select tests.set_id('dave',  tests.new_user('dave@example.com', 'Dave'));
select tests.set_id('g2', tests.group_with(tests.id('dave'), array[tests.id('alice')], 'Friends'));

-- Validation -----------------------------------------------------------------------
select throws_ok(
  $$ select tests.as_user(tests.id('alice'), $q$ select public.create_proposal(tests.id('g1'), now() + interval '10 minutes', now() + interval '2 hours', 'HOME') $q$) $$,
  'P0001', 'LEAD_TIME_TOO_SHORT', 'start must be at least 30 minutes away');
select throws_ok(
  $$ select tests.as_user(tests.id('alice'), $q$ select public.create_proposal(tests.id('g1'), now() + interval '1 hour', now() + interval '70 minutes', 'HOME') $q$) $$,
  'P0001', 'WINDOW_TOO_SHORT', 'window must be at least 15 minutes');
select throws_ok(
  $$ select tests.as_user(tests.id('alice'), $q$ select public.create_proposal(tests.id('g1'), now() + interval '1 hour', now() + interval '32 days', 'HOME') $q$) $$,
  'P0001', 'WINDOW_TOO_LONG', 'window must be at most 30 days');
select throws_ok(
  $$ select tests.as_user(tests.new_user('eve@example.com'), $q$ select public.create_proposal(tests.id('g1'), now() + interval '1 hour', now() + interval '2 hours', 'HOME') $q$) $$,
  'P0001', 'NOT_MEMBER', 'non-members cannot propose');

-- Group lane -----------------------------------------------------------------------
select tests.set_id('p1', tests.propose(tests.id('alice'), tests.id('g1'), interval '1 day'));
select is(tests.status(tests.id('p1')), 'PENDING', 'a new proposal is pending');
select is(tests.outbox_count(tests.id('bob'), 'NEW_PROPOSAL'), 1, 'members are notified of a new proposal');
select is(tests.outbox_count(tests.id('alice'), 'NEW_PROPOSAL'), 0, 'the proposer is not notified of their own proposal');
select throws_ok(
  $$ select tests.as_user(tests.id('bob'), $q$ select public.create_proposal(tests.id('g1'), now() + interval '1 day 1 hour', now() + interval '1 day 3 hours', 'BOTH') $q$) $$,
  'P0001', 'SLOT_TAKEN', 'an overlapping proposal in the same group is refused');
select lives_ok(
  $$ select tests.as_user(tests.id('bob'), $q$ select public.create_proposal(tests.id('g1'), now() + interval '1 day 2 hours', now() + interval '1 day 3 hours', 'LOCK') $q$) $$,
  'a back-to-back slot (touching, not overlapping) is fine');
select throws_ok(
  $$ insert into public.proposals (group_id, proposer_id, start_at, end_at, target, image_path, vote_deadline)
     values (tests.id('g1'), tests.id('cara'), now() + interval '1 day 30 minutes', now() + interval '1 day 90 minutes', 'HOME', 'x', now() + interval '1 day 15 minutes') $$,
  '23P01', null, 'the exclusion constraint holds even for direct inserts (no race possible)');

-- Unanimity ------------------------------------------------------------------------
select tests.vote(tests.id('bob'), tests.id('p1'), 'APPROVE');
select is(tests.status(tests.id('p1')), 'PENDING', 'still pending until everyone approves');
select is((tests.as_user_json(tests.id('bob'), $q$ select public.proposal_vote_summary(tests.id('p1')) $q$) ->> 'approvals')::int, 2,
  'summary counts the proposer''s automatic approval');
select throws_ok($$ select tests.vote(tests.id('bob'), tests.id('p1'), 'APPROVE') $$, 'P0001', 'ALREADY_VOTED', 'one vote per member');
select tests.vote(tests.id('cara'), tests.id('p1'), 'APPROVE');
select is(tests.status(tests.id('p1')), 'APPROVED', 'unanimous approval makes a schedule');
select is(tests.outbox_count(tests.id('cara'), 'PROPOSAL_APPROVED'), 1, 'everyone gets the approved data message');

-- A single reject kills it, and the reason is anonymous ------------------------------
select tests.set_id('p2', tests.propose(tests.id('bob'), tests.id('g1'), interval '3 days'));
select tests.vote(tests.id('cara'), tests.id('p2'), 'REJECT', 'Not that photo please');
select is(tests.status(tests.id('p2')), 'REJECTED', 'one reject → REJECTED');
select is(tests.as_user_json(tests.id('bob'), $q$ select public.proposal_vote_summary(tests.id('p2')) $q$) -> 'reasons',
  '["Not that photo please"]'::jsonb, 'the proposer sees the reason');
select ok(not (tests.as_user_json(tests.id('bob'), $q$ select public.proposal_vote_summary(tests.id('p2')) $q$)::text ~ (tests.id('cara')::text)),
  'the summary never contains the voter id');
select is(tests.as_user_int(tests.id('bob'), $q$ select count(*) from public.proposal_votes where proposal_id = tests.id('p2') $q$), 1::bigint,
  'a member can only read their own vote row');
select ok(exists (select 1 from public.storage_purge_queue q join public.proposals p on p.image_path = q.image_path where p.id = tests.id('p2')),
  'a rejected proposal''s image is queued for immediate purge');

-- Personal lane --------------------------------------------------------------------
-- Alice is committed to p1 (approved, group 1). Dave proposes an overlapping B in group 2.
select tests.set_id('pB', tests.propose(tests.id('dave'), tests.id('g2'), interval '1 day 30 minutes'));
select is(jsonb_array_length(tests.as_user_json(tests.id('alice'), $q$ select public.proposal_conflicts(tests.id('pB')) $q$)), 1,
  'the overlap is reported (drives the disabled Approve + banner)');
select throws_ok($$ select tests.vote(tests.id('alice'), tests.id('pB'), 'APPROVE') $$, 'P0001', 'PERSONAL_CONFLICT',
  'the RPC refuses an approval that would overlap a commitment');
select lives_ok($$ select tests.as_user(tests.id('alice'), $q$ select public.suggest_time(tests.id('pB'), now() + interval '5 days', now() + interval '5 days 2 hours') $q$) $$,
  'suggesting another time is allowed');
select is(tests.outbox_count(tests.id('dave'), 'TIME_SUGGESTION'), 1, 'the proposer gets the suggestion');
select throws_ok(
  $$ select tests.as_user(tests.id('alice'), $q$ select public.create_proposal(tests.id('g2'), now() + interval '1 day 1 hour', now() + interval '1 day 4 hours', 'HOME') $q$) $$,
  'P0001', 'PERSONAL_CONFLICT', 'a member cannot propose over their own commitment either');

-- Re-propose with one tap: new proposal, votes reset, old one cancelled, image reused.
select tests.set_id('pB2', (tests.as_user_json(tests.id('dave'),
  $q$ select public.create_proposal(tests.id('g2'), now() + interval '5 days', now() + interval '5 days 2 hours', 'HOME', p_replaces => tests.id('pB')) $q$) ->> 'proposal_id')::uuid);
select is(tests.status(tests.id('pB')), 'CANCELLED', 're-proposing cancels the original');
select is((select image_path from public.proposals where id = tests.id('pB2')), (select image_path from public.proposals where id = tests.id('pB')),
  'the re-proposal reuses the image');
select lives_ok($$ select tests.vote(tests.id('alice'), tests.id('pB2'), 'APPROVE') $$, 'the new time can be approved');

-- Skip on my phone removes the commitment and is anonymous
select tests.as_user(tests.id('bob'), $q$ select public.skip_on_my_phone(tests.id('p1')) $q$);
select is((tests.as_user_json(tests.id('alice'), $q$ select public.delivery_summary(tests.id('p1')) $q$) ->> 'skipped')::int, 1,
  'the group sees that one member skipped');
select ok(not exists (select 1 from public.notification_outbox where type = 'SCHEDULE_SKIPPED' and payload::text ~ tests.id('bob')::text),
  'the skip notification does not name the member');

select * from finish();
rollback;
