-- All state changes. Every public RPC:
--   * is SECURITY DEFINER with search_path = '' (all names fully qualified),
--   * checks auth.uid() first,
--   * runs inside the caller's single transaction (PostgREST wraps each RPC call),
--   * raises errors whose MESSAGE is a stable code (e.g. 'SLOT_TAKEN') that the app
--     maps to a friendly sentence; DETAIL may carry JSON for the UI.
-- Functions starting with "_" are internal and not executable by clients.

-- ---------------------------------------------------------------------------------
-- Internal helpers
-- ---------------------------------------------------------------------------------

create or replace function public._err(p_code text, p_detail text default null)
returns void language plpgsql volatile set search_path = '' as $$
begin
  raise exception using errcode = 'P0001', message = p_code, detail = coalesce(p_detail, '');
end $$;

create or replace function public._uid()
returns uuid language plpgsql stable set search_path = '' as $$
declare v uuid := auth.uid();
begin
  if v is null then perform public._err('NOT_AUTHENTICATED'); end if;
  return v;
end $$;

create or replace function public._require_member(p_group_id uuid, p_user_id uuid)
returns void language plpgsql stable set search_path = '' as $$
begin
  if not exists (select 1 from public.group_members where group_id = p_group_id and user_id = p_user_id) then
    perform public._err('NOT_MEMBER');
  end if;
end $$;

create or replace function public._member_ids(p_group_id uuid)
returns uuid[] language sql stable set search_path = '' as $$
  select coalesce(array_agg(user_id order by joined_at), '{}') from public.group_members where group_id = p_group_id
$$;

-- Queue a notification (and, for data-only events, a sync trigger) for each user.
create or replace function public._notify(p_user_ids uuid[], p_type text, p_payload jsonb)
returns void language sql set search_path = '' as $$
  insert into public.notification_outbox (user_id, type, payload)
  select u, p_type, p_payload from unnest(p_user_ids) as u where u is not null
$$;

create or replace function public._group_payload(p_group_id uuid, p_extra jsonb default '{}')
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('group_id', g.id, 'group_name', g.name, 'time_zone', g.time_zone) || p_extra
  from public.groups g where g.id = p_group_id
$$;

create or replace function public._proposal_payload(p_proposal_id uuid, p_extra jsonb default '{}')
returns jsonb language sql stable set search_path = '' as $$
  select public._group_payload(p.group_id, jsonb_build_object(
      'proposal_id', p.id, 'start_at', p.start_at, 'end_at', p.end_at, 'target', p.target,
      'proposer_name', coalesce(pr.name, ''), 'reminder_text', p.reminder_text) || p_extra)
  from public.proposals p left join public.profiles pr on pr.id = p.proposer_id
  where p.id = p_proposal_id
$$;

-- Personal lane. A commitment is an APPROVED/LIVE schedule in one of the user's groups
-- that they have not skipped, or a PENDING proposal they proposed or approved.
create or replace function public._user_commitments(p_user_id uuid)
returns table (proposal_id uuid, group_id uuid, group_name text, start_at timestamptz, end_at timestamptz, status text)
language sql stable set search_path = '' as $$
  select p.id, p.group_id, g.name, p.start_at, p.end_at, p.status
  from public.proposals p
  join public.groups g on g.id = p.group_id
  join public.group_members m on m.group_id = p.group_id and m.user_id = p_user_id
  where p.end_at > now()
    and (
      (p.status in ('APPROVED', 'LIVE')
        and not exists (select 1 from public.skips s where s.proposal_id = p.id and s.user_id = p_user_id))
      or (p.status = 'PENDING' and (
        p.proposer_id = p_user_id
        or exists (select 1 from public.proposal_votes v
                   where v.proposal_id = p.id and v.voter_id = p_user_id and v.decision = 'APPROVE'))))
$$;

create or replace function public._conflicts(p_user_id uuid, p_start timestamptz, p_end timestamptz, p_exclude uuid default null)
returns jsonb language sql stable set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object(
           'proposal_id', c.proposal_id, 'group_id', c.group_id, 'group_name', c.group_name,
           'start_at', c.start_at, 'end_at', c.end_at, 'status', c.status) order by c.start_at), '[]')
  from public._user_commitments(p_user_id) c
  where c.proposal_id is distinct from p_exclude
    and tstzrange(c.start_at, c.end_at, '[)') && tstzrange(p_start, p_end, '[)')
$$;

-- Serialise everything that changes one user's commitments (votes in two groups at
-- the same moment can't both pass the conflict check).
create or replace function public._lock_user(p_user_id uuid)
returns void language plpgsql set search_path = '' as $$
begin
  perform 1 from public.profiles where id = p_user_id for update;
end $$;

create or replace function public._validate_window(p_start timestamptz, p_end timestamptz)
returns void language plpgsql stable set search_path = '' as $$
begin
  if p_start is null or p_end is null then perform public._err('INVALID_TIME'); end if;
  if p_start < now() + interval '30 minutes' then perform public._err('LEAD_TIME_TOO_SHORT'); end if;
  if p_end - p_start < interval '15 minutes' then perform public._err('WINDOW_TOO_SHORT'); end if;
  if p_end - p_start > interval '30 days' then perform public._err('WINDOW_TOO_LONG'); end if;
end $$;

-- ---------------------------------------------------------------------------------
-- Clock and lookups
-- ---------------------------------------------------------------------------------

-- Real wall-clock time of the database (not the transaction start), for skew correction.
create or replace function public.server_now()
returns timestamptz language sql volatile security definer set search_path = '' as $$ select clock_timestamp() $$;

create or replace function public.my_upcoming_commitments()
returns setof jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('proposal_id', c.proposal_id, 'group_id', c.group_id, 'group_name', c.group_name,
                            'start_at', c.start_at, 'end_at', c.end_at, 'status', c.status)
  from public._user_commitments(public._uid()) c order by c.start_at
$$;

-- Slots held in a group (including proposals still uploading), for the greyed-out picker.
create or replace function public.group_taken_slots(p_group_id uuid, p_from timestamptz, p_to timestamptz)
returns setof jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  perform public._require_member(p_group_id, public._uid());
  return query
    select jsonb_build_object('proposal_id', p.id, 'start_at', p.start_at, 'end_at', p.end_at, 'status', p.status)
    from public.proposals p
    where p.group_id = p_group_id and p.status in ('PENDING', 'APPROVED', 'LIVE')
      and tstzrange(p.start_at, p.end_at, '[)') && tstzrange(p_from, p_to, '[)')
    order by p.start_at;
end $$;

-- Counts for the home screen badges: things waiting for MY vote, per group.
create or replace function public.my_pending_counts()
returns setof jsonb language sql stable security definer set search_path = '' as $$
  with me as (select public._uid() as id)
  select jsonb_build_object(
    'group_id', m.group_id,
    'proposals', (select count(*) from public.proposals p
                  where p.group_id = m.group_id and p.status = 'PENDING' and p.image_ready and p.vote_deadline > now()
                    and not exists (select 1 from public.proposal_votes v where v.proposal_id = p.id and v.voter_id = me.id)),
    'admin_actions', (select count(*) from public.admin_actions a
                      join public.groups g on g.id = a.group_id
                      where a.group_id = m.group_id and a.status = 'PENDING' and g.admin_id <> me.id
                        and coalesce(a.payload ->> 'user_id', '') <> me.id::text
                        and not exists (select 1 from public.admin_votes v where v.action_id = a.id and v.voter_id = me.id)),
    'join_requests', (select count(*) from public.join_requests j
                      where j.group_id = m.group_id and j.status = 'PENDING'
                        and not exists (select 1 from public.join_votes v where v.request_id = j.id and v.voter_id = me.id)))
  from public.group_members m, me where m.user_id = me.id
$$;

-- ---------------------------------------------------------------------------------
-- Profile and device
-- ---------------------------------------------------------------------------------

create or replace function public.register_device_token(p_token text)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid();
begin
  -- A token belongs to one device, which belongs to whoever is signed in on it now.
  insert into public.device_tokens (fcm_token, user_id, updated_at) values (p_token, v_uid, now())
  on conflict (fcm_token) do update set user_id = excluded.user_id, updated_at = now();
end $$;

-- ---------------------------------------------------------------------------------
-- Groups, invites, joining
-- ---------------------------------------------------------------------------------

create or replace function public.create_group(p_name text, p_time_zone text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_id uuid;
begin
  if char_length(btrim(coalesce(p_name, ''))) not between 1 and 40 then perform public._err('INVALID_NAME'); end if;
  if not exists (select 1 from pg_catalog.pg_timezone_names where name = p_time_zone) then
    perform public._err('INVALID_TIME_ZONE');
  end if;
  insert into public.groups (name, time_zone, admin_id) values (btrim(p_name), p_time_zone, v_uid) returning id into v_id;
  insert into public.group_members (group_id, user_id) values (v_id, v_uid);
  return v_id;
end $$;

create or replace function public.set_delivery_mode(p_group_id uuid, p_mode text)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid();
begin
  if p_mode not in ('AUTO', 'TAP') then perform public._err('INVALID_MODE'); end if;
  update public.group_members set delivery_mode = p_mode where group_id = p_group_id and user_id = v_uid;
  if not found then perform public._err('NOT_MEMBER'); end if;
end $$;

create or replace function public._new_invite_code()
returns text language plpgsql volatile set search_path = '' as $$
declare
  v_alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; -- no 0/O, 1/I/L
  v_bytes bytea := extensions.gen_random_bytes(8);
  v_code text := '';
begin
  for i in 0..7 loop
    v_code := v_code || substr(v_alphabet, (get_byte(v_bytes, i) % char_length(v_alphabet)) + 1, 1);
  end loop;
  return v_code;
end $$;

-- Creates the join request and records the inviter's approval (inviting = approving).
create or replace function public._create_join_request(p_group_id uuid, p_user_id uuid, p_invite_id uuid)
returns uuid language plpgsql set search_path = '' as $$
declare v_id uuid; v_inviter uuid;
begin
  select id into v_id from public.join_requests where group_id = p_group_id and user_id = p_user_id and status = 'PENDING';
  if v_id is not null then return v_id; end if;

  insert into public.join_requests (group_id, user_id, invite_id) values (p_group_id, p_user_id, p_invite_id) returning id into v_id;

  select created_by into v_inviter from public.invites where id = p_invite_id;
  if v_inviter is not null and public.is_member(p_group_id, v_inviter) then
    insert into public.join_votes (request_id, voter_id, decision) values (v_id, v_inviter, 'APPROVE');
  end if;

  perform public._notify(array_remove(public._member_ids(p_group_id), v_inviter), 'JOIN_REQUEST',
    public._group_payload(p_group_id, jsonb_build_object('request_id', v_id,
      'joiner_name', (select name from public.profiles where id = p_user_id))));
  perform public._notify(array[p_user_id], 'JOIN_STARTED', public._group_payload(p_group_id, jsonb_build_object('request_id', v_id)));
  return v_id;
end $$;

create or replace function public.create_invite(p_group_id uuid, p_email text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := public._uid();
  v_group public.groups;
  v_email text := nullif(lower(btrim(coalesce(p_email, ''))), '');
  v_invite public.invites;
  v_existing uuid;
  v_request uuid;
begin
  select * into v_group from public.groups where id = p_group_id;
  if v_group.id is null or not public.is_member(p_group_id, v_uid) then perform public._err('NOT_MEMBER'); end if;
  if v_group.admin_id <> v_uid then perform public._err('NOT_ADMIN'); end if;
  if (select count(*) from public.group_members where group_id = p_group_id) >= v_group.max_members then
    perform public._err('GROUP_FULL');
  end if;
  if v_email is not null and v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then perform public._err('INVALID_EMAIL'); end if;

  if v_email is not null then
    select p.id into v_existing from public.profiles p where lower(p.email) = v_email limit 1;
    if v_existing is not null and public.is_member(p_group_id, v_existing) then perform public._err('ALREADY_MEMBER'); end if;
  end if;

  insert into public.invites (group_id, code, email_optional, created_by, expires_at)
  values (p_group_id, public._new_invite_code(), v_email, v_uid,
          -- Link/code invites expire in 24h. An email invite waits for that person to sign up.
          case when v_email is null then now() + interval '24 hours' else now() + interval '30 days' end)
  returning * into v_invite;

  if v_existing is not null then
    update public.invites set used_at = now(), used_by = v_existing where id = v_invite.id;
    v_request := public._create_join_request(p_group_id, v_existing, v_invite.id);
  end if;

  return jsonb_build_object('invite_id', v_invite.id, 'code', v_invite.code, 'expires_at', v_invite.expires_at,
                            'email', v_email, 'join_request_id', v_request);
end $$;

-- Called by the auth trigger when someone signs up: turn matching email invites into join requests.
create or replace function public._resolve_email_invites(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_email text; r record;
begin
  select lower(email) into v_email from public.profiles where id = p_user_id;
  if v_email is null then return; end if;
  for r in select * from public.invites
           where lower(email_optional) = v_email and used_at is null and expires_at > now()
           for update loop
    if not public.is_member(r.group_id, p_user_id) then
      update public.invites set used_at = now(), used_by = p_user_id where id = r.id;
      perform public._create_join_request(r.group_id, p_user_id, r.id);
    end if;
  end loop;
end $$;

create or replace function public.redeem_invite(p_code text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_inv public.invites; v_email text;
begin
  select * into v_inv from public.invites where code = upper(btrim(coalesce(p_code, ''))) for update;
  if v_inv.id is null then perform public._err('INVITE_NOT_FOUND'); end if;
  if v_inv.used_at is not null then perform public._err('INVITE_USED'); end if;
  if v_inv.expires_at <= now() then perform public._err('INVITE_EXPIRED'); end if;
  select lower(email) into v_email from public.profiles where id = v_uid;
  if v_inv.email_optional is not null and v_inv.email_optional is distinct from v_email then
    perform public._err('INVITE_FOR_SOMEONE_ELSE');
  end if;
  if public.is_member(v_inv.group_id, v_uid) then perform public._err('ALREADY_MEMBER'); end if;
  if (select count(*) from public.group_members where group_id = v_inv.group_id)
     >= (select max_members from public.groups where id = v_inv.group_id) then
    perform public._err('GROUP_FULL');
  end if;
  update public.invites set used_at = now(), used_by = v_uid where id = v_inv.id;  -- single use
  return public._create_join_request(v_inv.group_id, v_uid, v_inv.id);
end $$;

-- Upcoming schedules the joiner must accept, and which of them overlap their commitments.
create or replace function public.join_request_preview(p_request_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_req public.join_requests;
begin
  select * into v_req from public.join_requests where id = p_request_id;
  if v_req.id is null or v_req.user_id <> v_uid then perform public._err('NOT_FOUND'); end if;
  return (
    select jsonb_build_object(
      'request_id', v_req.id, 'status', v_req.status, 'terms_accepted_at', v_req.terms_accepted_at,
      'group_id', g.id, 'group_name', g.name, 'time_zone', g.time_zone,
      'member_count', (select count(*) from public.group_members where group_id = g.id),
      'required', (select count(*) from public.group_members where group_id = g.id),
      'approvals', (select count(*) from public.join_votes v join public.group_members m
                    on m.group_id = g.id and m.user_id = v.voter_id
                    where v.request_id = v_req.id and v.decision = 'APPROVE'),
      'schedules', coalesce((
        select jsonb_agg(jsonb_build_object(
          'proposal_id', p.id, 'start_at', p.start_at, 'end_at', p.end_at, 'target', p.target,
          'image_path', p.image_path, 'focal_x', p.focal_x, 'focal_y', p.focal_y,
          'reminder_text', p.reminder_text, 'reminder_style', p.reminder_style,
          'conflicts', public._conflicts(v_uid, p.start_at, p.end_at)) order by p.start_at)
        from public.proposals p
        where p.group_id = g.id and p.status in ('APPROVED', 'LIVE') and p.end_at > now()), '[]'))
    from public.groups g where g.id = v_req.group_id);
end $$;

create or replace function public._join_conflicts(p_request_id uuid)
returns jsonb language sql stable set search_path = '' as $$
  select coalesce(jsonb_agg(c), '[]') from (
    select jsonb_build_object('proposal_id', p.id, 'start_at', p.start_at, 'end_at', p.end_at,
                              'conflicts', public._conflicts(r.user_id, p.start_at, p.end_at)) as c
    from public.join_requests r
    join public.proposals p on p.group_id = r.group_id and p.status in ('APPROVED', 'LIVE') and p.end_at > now()
    where r.id = p_request_id) x
  where jsonb_array_length(c -> 'conflicts') > 0
$$;

create or replace function public._try_finalize_join(p_request_id uuid)
returns void language plpgsql set search_path = '' as $$
declare
  v_req public.join_requests;
  v_group public.groups;
  v_members int;
  v_approvals int;
begin
  select * into v_req from public.join_requests where id = p_request_id for update;
  if v_req.status <> 'PENDING' or v_req.terms_accepted_at is null then return; end if;
  select * into v_group from public.groups where id = v_req.group_id for update;

  select count(*) into v_members from public.group_members where group_id = v_req.group_id;
  select count(*) into v_approvals from public.join_votes v
    join public.group_members m on m.group_id = v_req.group_id and m.user_id = v.voter_id
    where v.request_id = v_req.id and v.decision = 'APPROVE';
  if v_approvals < v_members then return; end if;

  if v_members >= v_group.max_members then
    update public.join_requests set status = 'REJECTED', result_note = 'GROUP_FULL', decided_at = now() where id = v_req.id;
    perform public._notify(array[v_req.user_id], 'JOIN_RESULT', public._group_payload(v_group.id,
      jsonb_build_object('request_id', v_req.id, 'result', 'REJECTED', 'note', 'GROUP_FULL')));
    return;
  end if;

  -- A schedule approved after the joiner accepted the terms needs their consent too.
  perform public._lock_user(v_req.user_id);
  if exists (select 1 from public.proposals p where p.group_id = v_req.group_id and p.status in ('APPROVED', 'LIVE')
             and p.end_at > now() and p.approved_at > v_req.terms_accepted_at)
     or public._join_conflicts(v_req.id) <> '[]'::jsonb then
    update public.join_requests set terms_accepted_at = null where id = v_req.id;
    perform public._notify(array[v_req.user_id], 'JOIN_TERMS_CHANGED',
      public._group_payload(v_group.id, jsonb_build_object('request_id', v_req.id)));
    return;
  end if;

  insert into public.group_members (group_id, user_id) values (v_req.group_id, v_req.user_id);
  update public.join_requests set status = 'APPROVED', decided_at = now() where id = v_req.id;
  perform public._notify(array[v_req.user_id], 'JOIN_RESULT', public._group_payload(v_group.id,
    jsonb_build_object('request_id', v_req.id, 'result', 'APPROVED')));
  perform public._notify(array_remove(public._member_ids(v_group.id), v_req.user_id), 'MEMBER_JOINED',
    public._group_payload(v_group.id, jsonb_build_object('user_id', v_req.user_id,
      'member_name', (select name from public.profiles where id = v_req.user_id))));
end $$;

-- The joiner accepts (or declines) every upcoming schedule of the group.
create or replace function public.accept_join_terms(p_request_id uuid, p_accept boolean)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_req public.join_requests; v_conf jsonb;
begin
  select * into v_req from public.join_requests where id = p_request_id for update;
  if v_req.id is null or v_req.user_id <> v_uid then perform public._err('NOT_FOUND'); end if;
  if v_req.status <> 'PENDING' then perform public._err('REQUEST_CLOSED'); end if;

  if not p_accept then
    update public.join_requests set status = 'CANCELLED', result_note = 'TERMS_DECLINED', decided_at = now() where id = v_req.id;
    return jsonb_build_object('status', 'CANCELLED');
  end if;

  perform public._lock_user(v_uid);
  v_conf := public._join_conflicts(v_req.id);
  if v_conf <> '[]'::jsonb then perform public._err('PERSONAL_CONFLICT', v_conf::text); end if;

  update public.join_requests set terms_accepted_at = now() where id = v_req.id;
  perform public._try_finalize_join(v_req.id);
  return jsonb_build_object('status', (select status from public.join_requests where id = v_req.id));
end $$;

create or replace function public.cancel_join_request(p_request_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid();
begin
  update public.join_requests set status = 'CANCELLED', result_note = 'CANCELLED_BY_JOINER', decided_at = now()
  where id = p_request_id and user_id = v_uid and status = 'PENDING';
  if not found then perform public._err('REQUEST_CLOSED'); end if;
end $$;

create or replace function public.vote_join(p_request_id uuid, p_decision text, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_req public.join_requests;
begin
  if p_decision not in ('APPROVE', 'REJECT') then perform public._err('INVALID_DECISION'); end if;
  select * into v_req from public.join_requests where id = p_request_id for update;
  if v_req.id is null then perform public._err('NOT_FOUND'); end if;
  perform public._require_member(v_req.group_id, v_uid);
  if v_req.status <> 'PENDING' then perform public._err('REQUEST_CLOSED'); end if;
  if exists (select 1 from public.join_votes where request_id = v_req.id and voter_id = v_uid) then
    perform public._err('ALREADY_VOTED');
  end if;

  insert into public.join_votes (request_id, voter_id, decision, reason)
  values (v_req.id, v_uid, p_decision, nullif(btrim(coalesce(p_reason, '')), ''));

  if p_decision = 'REJECT' then
    update public.join_requests set status = 'REJECTED', decided_at = now() where id = v_req.id;
    -- Anonymous: the joiner learns the outcome and the optional reason, never who.
    perform public._notify(array[v_req.user_id], 'JOIN_RESULT', public._group_payload(v_req.group_id,
      jsonb_build_object('request_id', v_req.id, 'result', 'REJECTED', 'reason', nullif(btrim(coalesce(p_reason, '')), ''))));
  else
    perform public._try_finalize_join(v_req.id);
  end if;
  return jsonb_build_object('status', (select status from public.join_requests where id = v_req.id));
end $$;

-- Join requests of a group as members see them (joiner profile is shown so members can decide).
create or replace function public.group_join_requests(p_group_id uuid)
returns setof jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_uid uuid := public._uid();
begin
  perform public._require_member(p_group_id, v_uid);
  return query
    select jsonb_build_object(
      'request_id', r.id, 'user_id', r.user_id, 'name', p.name, 'email', p.email, 'photo_url', p.photo_url,
      'created_at', r.created_at, 'terms_accepted', r.terms_accepted_at is not null,
      'required', (select count(*) from public.group_members where group_id = p_group_id),
      'approvals', (select count(*) from public.join_votes v join public.group_members m
                    on m.group_id = p_group_id and m.user_id = v.voter_id
                    where v.request_id = r.id and v.decision = 'APPROVE'),
      'my_vote', (select decision from public.join_votes where request_id = r.id and voter_id = v_uid))
    from public.join_requests r join public.profiles p on p.id = r.user_id
    where r.group_id = p_group_id and r.status = 'PENDING'
    order by r.created_at;
end $$;

-- The joiner's own open requests (to resume the join flow).
create or replace function public.my_join_requests()
returns setof jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('request_id', r.id, 'group_id', r.group_id, 'group_name', g.name,
                            'status', r.status, 'terms_accepted', r.terms_accepted_at is not null, 'created_at', r.created_at)
  from public.join_requests r join public.groups g on g.id = r.group_id
  where r.user_id = public._uid() and r.status = 'PENDING'
  order by r.created_at desc
$$;

-- ---------------------------------------------------------------------------------
-- Proposals and votes
-- ---------------------------------------------------------------------------------

create or replace function public._try_finalize_proposal(p_proposal_id uuid)
returns void language plpgsql set search_path = '' as $$
declare v_p public.proposals; v_members int; v_approvals int;
begin
  select * into v_p from public.proposals where id = p_proposal_id for update;
  if v_p.status <> 'PENDING' or not v_p.image_ready or now() >= v_p.vote_deadline then return; end if;
  select count(*) into v_members from public.group_members where group_id = v_p.group_id;
  select count(*) into v_approvals from public.proposal_votes v
    join public.group_members m on m.group_id = v_p.group_id and m.user_id = v.voter_id
    where v.proposal_id = v_p.id and v.decision = 'APPROVE';
  if v_members > 0 and v_approvals >= v_members then
    update public.proposals set status = 'APPROVED', approved_at = now(), decided_at = now() where id = v_p.id;
    -- Data message: every device downloads and schedules the wallpaper.
    perform public._notify(public._member_ids(v_p.group_id), 'PROPOSAL_APPROVED', public._proposal_payload(v_p.id));
  end if;
end $$;

create or replace function public._announce_proposal(p_proposal_id uuid)
returns void language plpgsql set search_path = '' as $$
declare v_p public.proposals;
begin
  select * into v_p from public.proposals where id = p_proposal_id;
  perform public._notify(array_remove(public._member_ids(v_p.group_id), v_p.proposer_id), 'NEW_PROPOSAL',
    public._proposal_payload(v_p.id, jsonb_build_object('vote_deadline', v_p.vote_deadline)));
  perform public._try_finalize_proposal(v_p.id); -- a group of 1 is approved straight away
end $$;

-- Step 1 of proposing: validates and reserves the slot. The client then uploads the
-- image to the returned path and calls confirm_proposal_image. A proposal that
-- re-proposes an earlier one (p_replaces) reuses its image and skips the upload.
create or replace function public.create_proposal(
  p_group_id uuid, p_start timestamptz, p_end timestamptz, p_target text,
  p_focal_x real default 0.5, p_focal_y real default 0.5,
  p_reminder_text text default null, p_reminder_style smallint default 0,
  p_replaces uuid default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := public._uid();
  v_id uuid := gen_random_uuid();
  v_path text;
  v_ready boolean := false;
  v_old public.proposals;
  v_text text := nullif(btrim(coalesce(p_reminder_text, '')), '');
  v_conf jsonb;
begin
  perform public._require_member(p_group_id, v_uid);
  perform public._validate_window(p_start, p_end);
  if p_target not in ('HOME', 'LOCK', 'BOTH') then perform public._err('INVALID_TARGET'); end if;
  if p_focal_x not between 0 and 1 or p_focal_y not between 0 and 1 then perform public._err('INVALID_FOCAL_POINT'); end if;
  if char_length(v_text) > 60 then perform public._err('REMINDER_TOO_LONG'); end if;
  if coalesce(p_reminder_style, 0) not between 0 and 2 then perform public._err('INVALID_STYLE'); end if;

  perform public._lock_user(v_uid);

  v_path := p_group_id::text || '/' || v_id::text || '.jpg';
  if p_replaces is not null then
    select * into v_old from public.proposals where id = p_replaces for update;
    if v_old.id is null or v_old.proposer_id is distinct from v_uid or v_old.group_id <> p_group_id then
      perform public._err('NOT_FOUND');
    end if;
    -- Only a still-pending proposal can be re-proposed with its image: once a proposal is
    -- closed its image is queued for deletion and may already be gone.
    if v_old.status <> 'PENDING' or not v_old.image_ready then perform public._err('IMAGE_GONE'); end if;
    update public.proposals set status = 'CANCELLED', cancel_reason = 'REPLACED', decided_at = now() where id = v_old.id;
    v_path := v_old.image_path;
    v_ready := true;
  end if;

  v_conf := public._conflicts(v_uid, p_start, p_end);
  if v_conf <> '[]'::jsonb then perform public._err('PERSONAL_CONFLICT', v_conf::text); end if;

  begin
    insert into public.proposals (id, group_id, proposer_id, start_at, end_at, target, image_path, image_ready,
                                  focal_x, focal_y, reminder_text, reminder_style, vote_deadline, replaces_id)
    values (v_id, p_group_id, v_uid, p_start, p_end, p_target, v_path, v_ready,
            p_focal_x, p_focal_y, v_text, coalesce(p_reminder_style, 0), p_start - interval '15 minutes', p_replaces);
  exception when exclusion_violation then
    perform public._err('SLOT_TAKEN');
  end;

  insert into public.proposal_votes (proposal_id, voter_id, decision) values (v_id, v_uid, 'APPROVE');
  if v_ready then perform public._announce_proposal(v_id); end if;

  return jsonb_build_object('proposal_id', v_id, 'image_path', v_path, 'image_ready', v_ready);
end $$;

-- Step 2 of proposing: the image is in Storage; announce the proposal to the group.
create or replace function public.confirm_proposal_image(p_proposal_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_p public.proposals;
begin
  select * into v_p from public.proposals where id = p_proposal_id for update;
  if v_p.id is null or v_p.proposer_id is distinct from v_uid then perform public._err('NOT_FOUND'); end if;
  if v_p.status <> 'PENDING' then perform public._err('PROPOSAL_CLOSED'); end if;
  if v_p.image_ready then return; end if;
  if not exists (select 1 from storage.objects o where o.bucket_id = 'proposal-images' and o.name = v_p.image_path) then
    perform public._err('IMAGE_NOT_UPLOADED');
  end if;
  update public.proposals set image_ready = true where id = v_p.id;
  perform public._announce_proposal(v_p.id);
end $$;

create or replace function public.cast_vote(p_proposal_id uuid, p_decision text, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_p public.proposals; v_conf jsonb; v_reason text := nullif(btrim(coalesce(p_reason, '')), '');
begin
  if p_decision not in ('APPROVE', 'REJECT') then perform public._err('INVALID_DECISION'); end if;
  -- Row lock on the proposal: concurrent votes on it are applied one after another.
  select * into v_p from public.proposals where id = p_proposal_id for update;
  if v_p.id is null then perform public._err('NOT_FOUND'); end if;
  perform public._require_member(v_p.group_id, v_uid);
  if not v_p.image_ready then perform public._err('NOT_FOUND'); end if;
  if v_p.status <> 'PENDING' then perform public._err('PROPOSAL_CLOSED'); end if;
  if now() >= v_p.vote_deadline then perform public._err('VOTING_CLOSED'); end if;
  if exists (select 1 from public.proposal_votes where proposal_id = v_p.id and voter_id = v_uid) then
    perform public._err('ALREADY_VOTED');
  end if;

  if p_decision = 'APPROVE' then
    -- Row lock on the voter: two approvals by the same person in two groups can't race.
    perform public._lock_user(v_uid);
    v_conf := public._conflicts(v_uid, v_p.start_at, v_p.end_at, v_p.id);
    if v_conf <> '[]'::jsonb then perform public._err('PERSONAL_CONFLICT', v_conf::text); end if;
  end if;

  insert into public.proposal_votes (proposal_id, voter_id, decision, reason) values (v_p.id, v_uid, p_decision, v_reason);

  if p_decision = 'REJECT' then
    update public.proposals set status = 'REJECTED', decided_at = now() where id = v_p.id;
    perform public._notify(array_remove(public._member_ids(v_p.group_id), v_p.proposer_id), 'PROPOSAL_REJECTED',
      public._proposal_payload(v_p.id));
    -- Only the proposer gets the reason, and never the voter's name.
    perform public._notify(array[v_p.proposer_id], 'PROPOSAL_REJECTED',
      public._proposal_payload(v_p.id, jsonb_build_object('reason', v_reason)));
  else
    perform public._try_finalize_proposal(v_p.id);
  end if;
  return public.proposal_vote_summary(v_p.id);
end $$;

-- Anonymous vote progress. Reasons only for the proposer, without names, sorted by text.
create or replace function public.proposal_vote_summary(p_proposal_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_p public.proposals;
begin
  select * into v_p from public.proposals where id = p_proposal_id;
  if v_p.id is null or not public.can_see_proposal(v_p.id) then perform public._err('NOT_FOUND'); end if;
  return jsonb_build_object(
    'proposal_id', v_p.id, 'status', v_p.status,
    'required', (select count(*) from public.group_members where group_id = v_p.group_id),
    'approvals', (select count(*) from public.proposal_votes v join public.group_members m
                  on m.group_id = v_p.group_id and m.user_id = v.voter_id
                  where v.proposal_id = v_p.id and v.decision = 'APPROVE'),
    'rejections', (select count(*) from public.proposal_votes v where v.proposal_id = v_p.id and v.decision = 'REJECT'),
    'my_vote', (select decision from public.proposal_votes where proposal_id = v_p.id and voter_id = v_uid),
    'reasons', case when v_p.proposer_id = v_uid then
      (select coalesce(jsonb_agg(reason order by reason), '[]') from public.proposal_votes
       where proposal_id = v_p.id and decision = 'REJECT' and reason is not null)
      else '[]'::jsonb end,
    'skipped_count', (select count(*) from public.skips where proposal_id = v_p.id));
end $$;

-- Conflicts the caller would have if they approved this proposal (drives the banner).
create or replace function public.proposal_conflicts(p_proposal_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_p public.proposals;
begin
  select * into v_p from public.proposals where id = p_proposal_id;
  if v_p.id is null or not public.can_see_proposal(v_p.id) then perform public._err('NOT_FOUND'); end if;
  return public._conflicts(v_uid, v_p.start_at, v_p.end_at, v_p.id);
end $$;

create or replace function public.cancel_proposal(p_proposal_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_p public.proposals;
begin
  select * into v_p from public.proposals where id = p_proposal_id for update;
  if v_p.id is null or v_p.proposer_id is distinct from v_uid then perform public._err('NOT_FOUND'); end if;
  if v_p.status not in ('PENDING', 'APPROVED', 'LIVE') then perform public._err('PROPOSAL_CLOSED'); end if;
  update public.proposals set status = 'CANCELLED', cancel_reason = 'BY_PROPOSER', decided_at = now() where id = v_p.id;
  if v_p.image_ready then
    perform public._notify(public._member_ids(v_p.group_id), 'PROPOSAL_CANCELLED', public._proposal_payload(v_p.id));
  end if;
end $$;

create or replace function public.suggest_time(p_proposal_id uuid, p_start timestamptz, p_end timestamptz)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_p public.proposals; v_id uuid;
begin
  select * into v_p from public.proposals where id = p_proposal_id;
  if v_p.id is null or not public.can_see_proposal(v_p.id) then perform public._err('NOT_FOUND'); end if;
  perform public._require_member(v_p.group_id, v_uid);
  if v_p.proposer_id = v_uid then perform public._err('OWN_PROPOSAL'); end if;
  if v_p.status <> 'PENDING' then perform public._err('PROPOSAL_CLOSED'); end if;
  perform public._validate_window(p_start, p_end);
  insert into public.time_suggestions (proposal_id, from_user, suggested_start, suggested_end)
  values (v_p.id, v_uid, p_start, p_end) returning id into v_id;
  perform public._notify(array[v_p.proposer_id], 'TIME_SUGGESTION', public._proposal_payload(v_p.id,
    jsonb_build_object('suggestion_id', v_id, 'suggested_start', p_start, 'suggested_end', p_end)));
  return v_id;
end $$;

-- Revoke consent on this device only. The group learns "a member skipped", never who.
create or replace function public.skip_on_my_phone(p_proposal_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_p public.proposals;
begin
  select * into v_p from public.proposals where id = p_proposal_id;
  if v_p.id is null then perform public._err('NOT_FOUND'); end if;
  perform public._require_member(v_p.group_id, v_uid);
  if v_p.status not in ('APPROVED', 'LIVE') then perform public._err('PROPOSAL_CLOSED'); end if;
  insert into public.skips (proposal_id, user_id) values (v_p.id, v_uid) on conflict do nothing;
  if not found then return; end if;
  insert into public.deliveries (proposal_id, user_id, state, at) values (v_p.id, v_uid, 'SKIPPED', now())
  on conflict (proposal_id, user_id) do update set state = 'SKIPPED', at = now();
  perform public._notify(array_remove(public._member_ids(v_p.group_id), v_uid), 'SCHEDULE_SKIPPED',
    public._proposal_payload(v_p.id));
end $$;

create or replace function public.report_delivery(p_proposal_id uuid, p_state text)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_p public.proposals;
begin
  if p_state not in ('DOWNLOADING', 'READY', 'NOT_READY', 'LIVE', 'APPLIED_LATE', 'NOT_TAPPED', 'MISSED', 'ENDED') then
    perform public._err('INVALID_STATE');
  end if;
  select * into v_p from public.proposals where id = p_proposal_id;
  if v_p.id is null then perform public._err('NOT_FOUND'); end if;
  perform public._require_member(v_p.group_id, v_uid);
  if v_p.status not in ('APPROVED', 'LIVE', 'ENDED', 'PURGED') then perform public._err('PROPOSAL_CLOSED'); end if;
  if exists (select 1 from public.skips where proposal_id = v_p.id and user_id = v_uid) then return; end if;
  insert into public.deliveries (proposal_id, user_id, state, at) values (v_p.id, v_uid, p_state, now())
  on conflict (proposal_id, user_id) do update set state = excluded.state, at = now();
end $$;

-- "8 of 9 ready": counts, plus who is ready or live. Never who skipped.
create or replace function public.delivery_summary(p_proposal_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_p public.proposals;
begin
  select * into v_p from public.proposals where id = p_proposal_id;
  if v_p.id is null or not public.can_see_proposal(v_p.id) then perform public._err('NOT_FOUND'); end if;
  return jsonb_build_object(
    'proposal_id', v_p.id,
    'members', (select count(*) from public.group_members where group_id = v_p.group_id),
    'skipped', (select count(*) from public.skips where proposal_id = v_p.id),
    'ready', (select count(*) from public.deliveries d join public.group_members m
              on m.group_id = v_p.group_id and m.user_id = d.user_id
              where d.proposal_id = v_p.id and d.state in ('READY', 'LIVE', 'APPLIED_LATE')),
    'live', (select count(*) from public.deliveries d join public.group_members m
             on m.group_id = v_p.group_id and m.user_id = d.user_id
             where d.proposal_id = v_p.id and d.state in ('LIVE', 'APPLIED_LATE')),
    'people', (select coalesce(jsonb_agg(jsonb_build_object('user_id', d.user_id, 'name', pr.name,
                 'state', case when d.state = 'APPLIED_LATE' then 'LIVE' else d.state end) order by pr.name), '[]')
               from public.deliveries d
               join public.group_members m on m.group_id = v_p.group_id and m.user_id = d.user_id
               join public.profiles pr on pr.id = d.user_id
               where d.proposal_id = v_p.id and d.state in ('READY', 'LIVE', 'APPLIED_LATE')));
end $$;

-- Everything a device needs to reconcile its local schedule. Absence means "cancel".
create or replace function public.my_schedules()
returns setof jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'proposal_id', p.id, 'group_id', p.group_id, 'group_name', g.name, 'time_zone', g.time_zone,
    'start_at', p.start_at, 'end_at', p.end_at, 'target', p.target, 'image_path', p.image_path,
    'focal_x', p.focal_x, 'focal_y', p.focal_y, 'reminder_text', p.reminder_text, 'reminder_style', p.reminder_style,
    'status', p.status, 'delivery_mode', m.delivery_mode,
    'skipped', exists (select 1 from public.skips s where s.proposal_id = p.id and s.user_id = m.user_id),
    'proposer_id', p.proposer_id)
  from public.group_members m
  join public.groups g on g.id = m.group_id
  join public.proposals p on p.group_id = m.group_id
  where m.user_id = public._uid() and p.status in ('APPROVED', 'LIVE') and p.end_at > now()
  order by p.start_at
$$;

-- ---------------------------------------------------------------------------------
-- Membership changes
-- ---------------------------------------------------------------------------------

create or replace function public._cancel_admin_actions(p_group_id uuid, p_note text)
returns void language plpgsql set search_path = '' as $$
declare r record;
begin
  for r in update public.admin_actions set status = 'CANCELLED', result_note = p_note, decided_at = now()
           where group_id = p_group_id and status = 'PENDING' returning id, type loop
    perform public._notify(public._member_ids(p_group_id), 'ADMIN_ACTION_RESULT',
      public._group_payload(p_group_id, jsonb_build_object('action_id', r.id, 'action_type', r.type, 'result', 'CANCELLED', 'note', p_note)));
  end loop;
end $$;

-- Admin passes to the longest-standing remaining member who hasn't declined this round.
create or replace function public._handover_admin(p_group_id uuid)
returns void language plpgsql set search_path = '' as $$
declare v_group public.groups; v_next uuid;
begin
  select * into v_group from public.groups where id = p_group_id for update;
  select user_id into v_next from public.group_members
   where group_id = p_group_id and not (user_id = any (v_group.admin_declined_by))
   order by joined_at, user_id limit 1;
  if v_next is null then -- everyone declined: it stays with the longest-standing member
    select user_id into v_next from public.group_members where group_id = p_group_id order by joined_at, user_id limit 1;
  end if;
  if v_next is null then return; end if;
  perform public._cancel_admin_actions(p_group_id, 'ADMIN_CHANGED');
  update public.groups set admin_id = v_next, admin_handover_pending = true where id = p_group_id;
  perform public._notify(array_remove(public._member_ids(p_group_id), v_next), 'ADMIN_CHANGED',
    public._group_payload(p_group_id, jsonb_build_object('admin_id', v_next,
      'admin_name', (select name from public.profiles where id = v_next))));
  perform public._notify(array[v_next], 'YOU_ARE_ADMIN', public._group_payload(p_group_id, jsonb_build_object('can_decline', true)));
end $$;

create or replace function public._delete_group(p_group_id uuid)
returns void language plpgsql set search_path = '' as $$
declare v_members uuid[] := public._member_ids(p_group_id); v_payload jsonb := public._group_payload(p_group_id);
begin
  update public.proposals set status = 'CANCELLED', cancel_reason = 'GROUP_DELETED', decided_at = now()
   where group_id = p_group_id and status in ('PENDING', 'APPROVED', 'LIVE');
  -- Queue images before the rows disappear (the delete trigger queues them too).
  perform public._notify(v_members, 'GROUP_DELETED', v_payload);
  delete from public.groups where id = p_group_id;
end $$;

-- Shared by leave, removal and account deletion.
create or replace function public._remove_member(p_group_id uuid, p_user_id uuid, p_reason text)
returns void language plpgsql set search_path = '' as $$
declare v_group public.groups; r record; v_payload jsonb;
begin
  select * into v_group from public.groups where id = p_group_id for update;
  if v_group.id is null then return; end if;
  if not exists (select 1 from public.group_members where group_id = p_group_id and user_id = p_user_id) then return; end if;

  v_payload := public._group_payload(p_group_id, jsonb_build_object('user_id', p_user_id,
    'member_name', (select name from public.profiles where id = p_user_id)));

  delete from public.group_members where group_id = p_group_id and user_id = p_user_id;
  delete from public.skips s using public.proposals p where p.id = s.proposal_id and p.group_id = p_group_id and s.user_id = p_user_id;
  delete from public.deliveries d using public.proposals p where p.id = d.proposal_id and p.group_id = p_group_id and d.user_id = p_user_id;

  -- Their proposals and schedules are cancelled for everyone.
  for r in update public.proposals set status = 'CANCELLED', cancel_reason = 'PROPOSER_LEFT', decided_at = now()
           where group_id = p_group_id and proposer_id = p_user_id and status in ('PENDING', 'APPROVED', 'LIVE')
           returning id, image_ready loop
    if r.image_ready then
      perform public._notify(public._member_ids(p_group_id), 'PROPOSAL_CANCELLED', public._proposal_payload(r.id));
    end if;
  end loop;

  -- Tell the person who left (a data message: their device cleans up immediately).
  perform public._notify(array[p_user_id], case when p_reason = 'REMOVED' then 'REMOVED_FROM_GROUP' else 'LEFT_GROUP' end, v_payload);

  if not exists (select 1 from public.group_members where group_id = p_group_id) then
    delete from public.groups where id = p_group_id;
    return;
  end if;

  perform public._notify(public._member_ids(p_group_id), case when p_reason = 'REMOVED' then 'MEMBER_REMOVED' else 'MEMBER_LEFT' end, v_payload);

  -- Actions about this person are no longer needed.
  update public.admin_actions set status = 'CANCELLED', result_note = 'NO_LONGER_NEEDED', decided_at = now()
   where group_id = p_group_id and status = 'PENDING' and type in ('REMOVE_MEMBER', 'TRANSFER_ADMIN')
     and payload ->> 'user_id' = p_user_id::text;

  if v_group.admin_id = p_user_id then
    update public.groups set admin_declined_by = '{}' where id = p_group_id;
    perform public._handover_admin(p_group_id);
  end if;

  -- Unanimity is recomputed over the remaining members.
  for r in select id from public.proposals where group_id = p_group_id and status = 'PENDING' loop
    perform public._try_finalize_proposal(r.id);
  end loop;
  for r in select id from public.join_requests where group_id = p_group_id and status = 'PENDING' loop
    perform public._try_finalize_join(r.id);
  end loop;
  for r in select id from public.admin_actions where group_id = p_group_id and status = 'PENDING' loop
    perform public._try_finalize_admin_action(r.id);
  end loop;
end $$;

-- Leaving is instant and needs no vote.
create or replace function public.leave_group(p_group_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid();
begin
  perform public._require_member(p_group_id, v_uid);
  perform public._remove_member(p_group_id, v_uid, 'LEFT');
end $$;

create or replace function public.decline_admin(p_group_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); v_group public.groups; v_next uuid;
begin
  select * into v_group from public.groups where id = p_group_id for update;
  if v_group.id is null or v_group.admin_id <> v_uid then perform public._err('NOT_ADMIN'); end if;
  if not v_group.admin_handover_pending then perform public._err('NOTHING_TO_DECLINE'); end if;
  select user_id into v_next from public.group_members
   where group_id = p_group_id and user_id <> v_uid and not (user_id = any (v_group.admin_declined_by))
   order by joined_at, user_id limit 1;
  if v_next is null then perform public._err('NO_OTHER_CANDIDATE'); end if;
  update public.groups set admin_declined_by = array_append(admin_declined_by, v_uid) where id = p_group_id;
  perform public._handover_admin(p_group_id);
end $$;

create or replace function public.accept_admin(p_group_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid();
begin
  update public.groups set admin_handover_pending = false, admin_declined_by = '{}'
   where id = p_group_id and admin_id = v_uid;
  if not found then perform public._err('NOT_ADMIN'); end if;
end $$;

-- Service-role only: used by the delete-account edge function before deleting the auth user.
create or replace function public._delete_account(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  perform public._lock_user(p_user_id);
  for r in select group_id from public.group_members where user_id = p_user_id loop
    perform public._remove_member(r.group_id, p_user_id, 'LEFT');
  end loop;
  update public.join_requests set status = 'CANCELLED', result_note = 'ACCOUNT_DELETED', decided_at = now()
   where user_id = p_user_id and status = 'PENDING';
  delete from public.device_tokens where user_id = p_user_id;
  delete from public.profiles where id = p_user_id;
end $$;

-- ---------------------------------------------------------------------------------
-- Admin actions
-- ---------------------------------------------------------------------------------

create or replace function public._admin_required_voters(p_action_id uuid)
returns uuid[] language sql stable set search_path = '' as $$
  select coalesce(array_agg(m.user_id), '{}')
  from public.admin_actions a
  join public.groups g on g.id = a.group_id
  join public.group_members m on m.group_id = a.group_id
  where a.id = p_action_id
    and m.user_id <> g.admin_id
    and not (a.type = 'REMOVE_MEMBER' and m.user_id::text = a.payload ->> 'user_id')
$$;

create or replace function public._apply_admin_action(p_action_id uuid)
returns void language plpgsql set search_path = '' as $$
declare a public.admin_actions; v_target uuid; v_count int;
begin
  select * into a from public.admin_actions where id = p_action_id;
  v_target := nullif(a.payload ->> 'user_id', '')::uuid;

  if a.type = 'MEMBER_LIMIT' then
    select count(*) into v_count from public.group_members where group_id = a.group_id;
    if (a.payload ->> 'max_members')::int < v_count then
      update public.admin_actions set status = 'REJECTED', result_note = 'NO_LONGER_VALID', decided_at = now() where id = a.id;
      return;
    end if;
  end if;
  if a.type in ('TRANSFER_ADMIN', 'REMOVE_MEMBER') and not public.is_member(a.group_id, v_target) then
    update public.admin_actions set status = 'CANCELLED', result_note = 'NO_LONGER_NEEDED', decided_at = now() where id = a.id;
    return;
  end if;

  update public.admin_actions set status = 'APPROVED', decided_at = now() where id = a.id;
  if a.type <> 'DELETE_GROUP' then
    perform public._notify(array_remove(public._member_ids(a.group_id), case when a.type = 'REMOVE_MEMBER' then v_target end),
      'ADMIN_ACTION_RESULT', public._group_payload(a.group_id,
        jsonb_build_object('action_id', a.id, 'action_type', a.type, 'result', 'APPROVED') || a.payload));
  end if;

  case a.type
    when 'RENAME' then update public.groups set name = a.payload ->> 'name' where id = a.group_id;
    -- Schedules are absolute instants; only how they are displayed changes.
    when 'TIMEZONE' then update public.groups set time_zone = a.payload ->> 'time_zone' where id = a.group_id;
    when 'MEMBER_LIMIT' then update public.groups set max_members = (a.payload ->> 'max_members')::int where id = a.group_id;
    when 'REMOVE_MEMBER' then perform public._remove_member(a.group_id, v_target, 'REMOVED');
    when 'TRANSFER_ADMIN' then
      update public.groups set admin_id = v_target, admin_handover_pending = false, admin_declined_by = '{}' where id = a.group_id;
      perform public._notify(array[v_target], 'YOU_ARE_ADMIN', public._group_payload(a.group_id, jsonb_build_object('can_decline', false)));
    when 'DELETE_GROUP' then perform public._delete_group(a.group_id);
  end case;
end $$;

create or replace function public._try_finalize_admin_action(p_action_id uuid)
returns void language plpgsql set search_path = '' as $$
declare a public.admin_actions; v_required uuid[]; v_approvals int;
begin
  select * into a from public.admin_actions where id = p_action_id for update;
  if a.status <> 'PENDING' or now() >= a.deadline then return; end if;
  v_required := public._admin_required_voters(a.id);
  select count(*) into v_approvals from public.admin_votes
   where action_id = a.id and decision = 'APPROVE' and voter_id = any (v_required);
  if v_approvals >= coalesce(array_length(v_required, 1), 0) then
    perform public._apply_admin_action(a.id);
  end if;
end $$;

create or replace function public.create_admin_action(p_group_id uuid, p_type text, p_payload jsonb default '{}')
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := public._uid();
  v_group public.groups;
  v_payload jsonb := coalesce(p_payload, '{}');
  v_target uuid;
  v_id uuid;
  v_count int;
begin
  select * into v_group from public.groups where id = p_group_id for update;
  if v_group.id is null or not public.is_member(p_group_id, v_uid) then perform public._err('NOT_MEMBER'); end if;
  if v_group.admin_id <> v_uid then perform public._err('NOT_ADMIN'); end if;
  if p_type not in ('RENAME', 'TIMEZONE', 'MEMBER_LIMIT', 'REMOVE_MEMBER', 'TRANSFER_ADMIN', 'DELETE_GROUP') then
    perform public._err('INVALID_ACTION');
  end if;
  select count(*) into v_count from public.group_members where group_id = p_group_id;

  -- Starting an action means the admin role was accepted.
  update public.groups set admin_handover_pending = false, admin_declined_by = '{}' where id = p_group_id;

  case p_type
    when 'RENAME' then
      if char_length(btrim(coalesce(v_payload ->> 'name', ''))) not between 1 and 40 then perform public._err('INVALID_NAME'); end if;
      v_payload := jsonb_build_object('name', btrim(v_payload ->> 'name'), 'previous', v_group.name);
    when 'TIMEZONE' then
      if not exists (select 1 from pg_catalog.pg_timezone_names where name = v_payload ->> 'time_zone') then
        perform public._err('INVALID_TIME_ZONE');
      end if;
      v_payload := jsonb_build_object('time_zone', v_payload ->> 'time_zone', 'previous', v_group.time_zone);
    when 'MEMBER_LIMIT' then
      if jsonb_typeof(v_payload -> 'max_members') <> 'number'
         or (v_payload ->> 'max_members')::int not between greatest(v_count, 1) and public.app_max_members() then
        perform public._err('INVALID_MEMBER_LIMIT');
      end if;
      v_payload := jsonb_build_object('max_members', (v_payload ->> 'max_members')::int, 'previous', v_group.max_members);
    when 'REMOVE_MEMBER', 'TRANSFER_ADMIN' then
      begin v_target := (v_payload ->> 'user_id')::uuid; exception when others then v_target := null; end;
      if v_target is null or v_target = v_uid or not public.is_member(p_group_id, v_target) then
        perform public._err('INVALID_MEMBER');
      end if;
      v_payload := jsonb_build_object('user_id', v_target, 'member_name', (select name from public.profiles where id = v_target));
    else
      v_payload := '{}';
  end case;

  begin
    insert into public.admin_actions (group_id, type, payload, created_by, deadline)
    values (p_group_id, p_type, v_payload, v_uid, now() + interval '48 hours') returning id into v_id;
  exception when unique_violation then
    perform public._err('ACTION_ALREADY_PENDING');
  end;

  perform public._notify(public._admin_required_voters(v_id), 'ADMIN_ACTION_REQUEST',
    public._group_payload(p_group_id, jsonb_build_object('action_id', v_id, 'action_type', p_type) || v_payload));
  perform public._try_finalize_admin_action(v_id); -- a group of 1 applies instantly
  return v_id;
end $$;

create or replace function public.cast_admin_vote(p_action_id uuid, p_decision text, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); a public.admin_actions; v_reason text := nullif(btrim(coalesce(p_reason, '')), '');
begin
  if p_decision not in ('APPROVE', 'REJECT') then perform public._err('INVALID_DECISION'); end if;
  select * into a from public.admin_actions where id = p_action_id for update;
  if a.id is null then perform public._err('NOT_FOUND'); end if;
  perform public._require_member(a.group_id, v_uid);
  if a.status <> 'PENDING' then perform public._err('ACTION_CLOSED'); end if;
  if now() >= a.deadline then perform public._err('VOTING_CLOSED'); end if;
  if not (v_uid = any (public._admin_required_voters(a.id))) then perform public._err('NOT_A_VOTER'); end if;
  if exists (select 1 from public.admin_votes where action_id = a.id and voter_id = v_uid) then
    perform public._err('ALREADY_VOTED');
  end if;

  insert into public.admin_votes (action_id, voter_id, decision, reason) values (a.id, v_uid, p_decision, v_reason);

  if p_decision = 'REJECT' then
    update public.admin_actions set status = 'REJECTED', decided_at = now() where id = a.id;
    -- A member who was going to be removed is not told about a failed attempt.
    perform public._notify(
      array_remove(public._member_ids(a.group_id), case when a.type = 'REMOVE_MEMBER' then (a.payload ->> 'user_id')::uuid end),
      'ADMIN_ACTION_RESULT', public._group_payload(a.group_id,
        jsonb_build_object('action_id', a.id, 'action_type', a.type, 'result', 'REJECTED', 'reason', v_reason) || a.payload));
  else
    perform public._try_finalize_admin_action(a.id);
  end if;
  if not exists (select 1 from public.admin_actions where id = a.id) then
    return jsonb_build_object('action_id', a.id, 'status', 'APPROVED', 'group_deleted', true);
  end if;
  return public.admin_action_summary(a.id);
end $$;

create or replace function public.cancel_admin_action(p_action_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); a public.admin_actions;
begin
  select * into a from public.admin_actions where id = p_action_id for update;
  if a.id is null or a.created_by <> v_uid then perform public._err('NOT_FOUND'); end if;
  if a.status <> 'PENDING' then perform public._err('ACTION_CLOSED'); end if;
  update public.admin_actions set status = 'CANCELLED', result_note = 'BY_ADMIN', decided_at = now() where id = a.id;
  perform public._notify(
    array_remove(public._member_ids(a.group_id), case when a.type = 'REMOVE_MEMBER' then (a.payload ->> 'user_id')::uuid end),
    'ADMIN_ACTION_RESULT', public._group_payload(a.group_id,
      jsonb_build_object('action_id', a.id, 'action_type', a.type, 'result', 'CANCELLED') || a.payload));
end $$;

create or replace function public.admin_action_summary(p_action_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_uid uuid := public._uid(); a public.admin_actions; v_required uuid[];
begin
  select * into a from public.admin_actions where id = p_action_id;
  if a.id is null or not public.is_member(a.group_id, v_uid)
     or (a.type = 'REMOVE_MEMBER' and a.status = 'PENDING' and a.payload ->> 'user_id' = v_uid::text) then
    perform public._err('NOT_FOUND');
  end if;
  v_required := public._admin_required_voters(a.id);
  return jsonb_build_object(
    'action_id', a.id, 'status', a.status, 'deadline', a.deadline,
    'required', coalesce(array_length(v_required, 1), 0),
    'approvals', (select count(*) from public.admin_votes where action_id = a.id and decision = 'APPROVE' and voter_id = any (v_required)),
    'rejections', (select count(*) from public.admin_votes where action_id = a.id and decision = 'REJECT'),
    'can_vote', a.status = 'PENDING' and v_uid = any (v_required)
                and not exists (select 1 from public.admin_votes where action_id = a.id and voter_id = v_uid),
    'my_vote', (select decision from public.admin_votes where action_id = a.id and voter_id = v_uid),
    'reasons', (select coalesce(jsonb_agg(reason order by reason), '[]') from public.admin_votes
                where action_id = a.id and decision = 'REJECT' and reason is not null));
end $$;

-- ---------------------------------------------------------------------------------
-- Scheduled work (called by pg_cron; see 20261117000005_jobs.sql)
-- ---------------------------------------------------------------------------------

create or replace function public._tick()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare r record; v_expired int := 0; v_live int := 0; v_ended int := 0; v_actions int := 0;
begin
  -- Abandoned uploads: the slot is released quietly.
  update public.proposals set status = 'CANCELLED', cancel_reason = 'UPLOAD_ABANDONED', decided_at = now()
   where status = 'PENDING' and not image_ready and created_at < now() - interval '15 minutes';

  -- No unanimity by the deadline → EXPIRED (image purged by the trigger + purge job).
  for r in update public.proposals set status = 'EXPIRED', decided_at = now()
           where status = 'PENDING' and now() >= vote_deadline returning id, group_id, image_ready loop
    v_expired := v_expired + 1;
    if r.image_ready then
      perform public._notify(public._member_ids(r.group_id), 'PROPOSAL_EXPIRED', public._proposal_payload(r.id));
    end if;
  end loop;

  -- Server-side status only; devices act on their own alarms.
  update public.proposals set status = 'LIVE' where status = 'APPROVED' and start_at <= now() and end_at > now();
  get diagnostics v_live = row_count;
  update public.proposals set status = 'ENDED' where status in ('APPROVED', 'LIVE') and end_at <= now();
  get diagnostics v_ended = row_count;

  -- Vote reminders, once, 1h before the deadline, to members who haven't voted.
  for r in update public.proposals set reminder_sent_at = now()
           where status = 'PENDING' and image_ready and reminder_sent_at is null
             and vote_deadline - now() <= interval '1 hour' and vote_deadline > now()
           returning id, group_id loop
    perform public._notify(array(
      select m.user_id from public.group_members m where m.group_id = r.group_id
        and not exists (select 1 from public.proposal_votes v where v.proposal_id = r.id and v.voter_id = m.user_id)),
      'VOTE_REMINDER', public._proposal_payload(r.id));
  end loop;

  for r in update public.admin_actions set status = 'EXPIRED', decided_at = now()
           where status = 'PENDING' and now() >= deadline returning id, group_id, type, payload loop
    v_actions := v_actions + 1;
    perform public._notify(
      array_remove(public._member_ids(r.group_id), case when r.type = 'REMOVE_MEMBER' then (r.payload ->> 'user_id')::uuid end),
      'ADMIN_ACTION_RESULT', public._group_payload(r.group_id,
        jsonb_build_object('action_id', r.id, 'action_type', r.type, 'result', 'EXPIRED') || r.payload));
  end loop;

  -- Join requests that nobody finished within 7 days.
  update public.join_requests set status = 'EXPIRED', decided_at = now()
   where status = 'PENDING' and created_at < now() - interval '7 days';

  return jsonb_build_object('expired', v_expired, 'live', v_live, 'ended', v_ended, 'actions_expired', v_actions);
end $$;

create or replace function public._cleanup_old_rows()
returns void language plpgsql security definer set search_path = '' as $$
begin
  -- Votes and delivery rows go 7 days after the end.
  delete from public.proposal_votes v using public.proposals p
   where p.id = v.proposal_id and greatest(p.end_at, coalesce(p.decided_at, p.end_at)) < now() - interval '7 days'
     and p.status not in ('PENDING', 'APPROVED', 'LIVE');
  delete from public.deliveries d using public.proposals p
   where p.id = d.proposal_id and p.end_at < now() - interval '7 days';
  delete from public.skips s using public.proposals p
   where p.id = s.proposal_id and p.end_at < now() - interval '7 days';
  delete from public.time_suggestions t using public.proposals p
   where p.id = t.proposal_id and p.status not in ('PENDING') and p.end_at < now() - interval '7 days';
  delete from public.invites where expires_at < now() - interval '7 days';
  delete from public.notification_outbox where created_at < now() - interval '30 days';
end $$;

-- Image purge queue: on terminal states immediately, on ENDED after a 1h grace period,
-- and whenever a proposal row is deleted (group deleted, account deleted).
create or replace function public._queue_image_purge()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'DELETE' then
    insert into public.storage_purge_queue (image_path, due_at) values (old.image_path, now())
    on conflict (image_path) do update set due_at = least(public.storage_purge_queue.due_at, excluded.due_at);
    return old;
  end if;
  if new.status is distinct from old.status then
    if new.status in ('REJECTED', 'EXPIRED', 'CANCELLED') then
      insert into public.storage_purge_queue (image_path, due_at) values (new.image_path, now())
      on conflict (image_path) do update set due_at = least(public.storage_purge_queue.due_at, excluded.due_at);
    elsif new.status = 'ENDED' then
      insert into public.storage_purge_queue (image_path, due_at) values (new.image_path, new.end_at + interval '1 hour')
      on conflict (image_path) do nothing;
    end if;
  end if;
  return new;
end $$;

create trigger proposals_queue_purge after update of status or delete on public.proposals
  for each row execute function public._queue_image_purge();

-- For purge-images: paths that are due and not used by any active proposal (a re-proposal
-- may reuse an image).
create or replace function public._purge_candidates(p_limit int default 100)
returns setof text language sql stable security definer set search_path = '' as $$
  select q.image_path from public.storage_purge_queue q
  where q.due_at <= now()
    and not exists (select 1 from public.proposals p where p.image_path = q.image_path
                    and (p.status in ('PENDING', 'APPROVED', 'LIVE')
                         or (p.status = 'ENDED' and p.end_at > now() - interval '1 hour')))
  order by q.due_at limit p_limit
$$;

create or replace function public._mark_purged(p_paths text[])
returns void language plpgsql security definer set search_path = '' as $$
begin
  delete from public.storage_purge_queue where image_path = any (p_paths);
  update public.proposals set purged_at = now(),
         status = case when status = 'ENDED' then 'PURGED' else status end
   where image_path = any (p_paths) and purged_at is null
     and status not in ('PENDING', 'APPROVED', 'LIVE');
end $$;

-- ---------------------------------------------------------------------------------
-- Privileges: clients may call only the public RPCs.
-- ---------------------------------------------------------------------------------

revoke execute on all functions in schema public from public, anon, authenticated;

grant execute on function
  public.app_max_members(),
  public.is_member(uuid, uuid), public.shares_group(uuid), public.can_see_proposal(uuid),
  public.server_now(), public.my_upcoming_commitments(), public.group_taken_slots(uuid, timestamptz, timestamptz),
  public.my_pending_counts(), public.register_device_token(text),
  public.create_group(text, text), public.set_delivery_mode(uuid, text),
  public.create_invite(uuid, text), public.redeem_invite(text), public.join_request_preview(uuid),
  public.accept_join_terms(uuid, boolean), public.cancel_join_request(uuid), public.vote_join(uuid, text, text),
  public.group_join_requests(uuid), public.my_join_requests(),
  public.create_proposal(uuid, timestamptz, timestamptz, text, real, real, text, smallint, uuid),
  public.confirm_proposal_image(uuid), public.cast_vote(uuid, text, text), public.proposal_vote_summary(uuid),
  public.proposal_conflicts(uuid), public.cancel_proposal(uuid), public.suggest_time(uuid, timestamptz, timestamptz),
  public.skip_on_my_phone(uuid), public.report_delivery(uuid, text), public.delivery_summary(uuid),
  public.my_schedules(),
  public.leave_group(uuid), public.decline_admin(uuid), public.accept_admin(uuid),
  public.create_admin_action(uuid, text, jsonb), public.cast_admin_vote(uuid, text, text),
  public.cancel_admin_action(uuid), public.admin_action_summary(uuid)
to authenticated;

grant execute on function public._delete_account(uuid), public._purge_candidates(int), public._mark_purged(text[]),
  public._tick(), public._cleanup_old_rows()
to service_role;
