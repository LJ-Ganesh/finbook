-- Row Level Security, grants and the profile trigger.
--
-- Principle: clients may SELECT what they are allowed to see and write almost nothing
-- directly. Vote rows, skips and delivery rows are readable only by their owner;
-- aggregated, anonymous views are exposed through RPCs.

-- Helpers used by policies (SECURITY DEFINER so policies don't recurse through RLS).

create or replace function public.is_member(p_group_id uuid, p_user_id uuid default auth.uid())
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.group_members m where m.group_id = p_group_id and m.user_id = p_user_id)
$$;

create or replace function public.shares_group(p_other uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select p_other = auth.uid() or exists (
    select 1 from public.group_members a
    join public.group_members b on b.group_id = a.group_id
    where a.user_id = auth.uid() and b.user_id = p_other)
$$;

create or replace function public.can_see_proposal(p_proposal_id uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.proposals p
    join public.group_members m on m.group_id = p.group_id and m.user_id = auth.uid()
    where p.id = p_proposal_id and (p.image_ready or p.proposer_id = auth.uid()))
$$;

-- Lock down defaults: Supabase grants broad table privileges to anon/authenticated.
revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
alter default privileges in schema public revoke all on tables from anon, authenticated;
alter default privileges in schema public revoke execute on functions from public, anon;

-- profiles: you see yourself and people you share a group with. You may edit only
-- name, photo and the optional phone number of your own row.
create policy profiles_select on public.profiles for select to authenticated
  using (public.shares_group(id));
create policy profiles_update on public.profiles for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());
grant select on public.profiles to authenticated;
grant update (name, photo_url, phone_optional) on public.profiles to authenticated;

-- device_tokens: own rows only. Registration goes through register_device_token().
create policy device_tokens_own on public.device_tokens for select to authenticated using (user_id = auth.uid());
create policy device_tokens_delete on public.device_tokens for delete to authenticated using (user_id = auth.uid());
grant select, delete on public.device_tokens to authenticated;

-- groups / members: visible to members only.
create policy groups_select on public.groups for select to authenticated using (public.is_member(id));
grant select on public.groups to authenticated;

create policy group_members_select on public.group_members for select to authenticated using (public.is_member(group_id));
grant select on public.group_members to authenticated;

-- invites: the admin of the group sees the group's invites.
create policy invites_select on public.invites for select to authenticated using (
  exists (select 1 from public.groups g where g.id = group_id and g.admin_id = auth.uid()));
grant select on public.invites to authenticated;

-- join requests: members of the group and the joiner.
create policy join_requests_select on public.join_requests for select to authenticated
  using (user_id = auth.uid() or public.is_member(group_id));
grant select on public.join_requests to authenticated;

-- admin actions: members, except that a member being removed can't see the pending
-- action about them ("not told until the outcome").
create policy admin_actions_select on public.admin_actions for select to authenticated using (
  public.is_member(group_id)
  and not (type = 'REMOVE_MEMBER' and status = 'PENDING' and payload ->> 'user_id' = auth.uid()::text));
grant select on public.admin_actions to authenticated;

-- proposals: members; a not-yet-uploaded proposal only by its proposer.
create policy proposals_select on public.proposals for select to authenticated using (
  public.is_member(group_id) and (image_ready or proposer_id = auth.uid()));
grant select on public.proposals to authenticated;

-- Votes, skips and deliveries: only the owner may read their own row (anonymity).
create policy proposal_votes_own on public.proposal_votes for select to authenticated using (voter_id = auth.uid());
create policy admin_votes_own    on public.admin_votes    for select to authenticated using (voter_id = auth.uid());
create policy join_votes_own     on public.join_votes     for select to authenticated using (voter_id = auth.uid());
create policy skips_own          on public.skips          for select to authenticated using (user_id = auth.uid());
create policy deliveries_own     on public.deliveries     for select to authenticated using (user_id = auth.uid());
grant select on public.proposal_votes, public.admin_votes, public.join_votes, public.skips, public.deliveries to authenticated;

-- time suggestions: the person who suggested and the proposer.
create policy time_suggestions_select on public.time_suggestions for select to authenticated using (
  from_user = auth.uid()
  or exists (select 1 from public.proposals p where p.id = proposal_id and p.proposer_id = auth.uid()));
grant select on public.time_suggestions to authenticated;

-- outbox: owners read their own rows (the Activity inbox) and may mark them read.
create policy outbox_select on public.notification_outbox for select to authenticated using (user_id = auth.uid());
create policy outbox_update on public.notification_outbox for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
grant select on public.notification_outbox to authenticated;
grant update (read_at) on public.notification_outbox to authenticated;

-- storage_purge_queue: no client access at all (service role only).

-- Service role (edge functions) bypasses RLS; give it table access explicitly.
grant all on all tables in schema public to service_role;
grant usage, select on all sequences in schema public to service_role;

-- Profile row on sign-up, prefilled from the Google account; resolves pending email invites.
create or replace function public._on_auth_user_created()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, email, name, photo_url)
  values (
    new.id,
    lower(new.email),
    left(coalesce(new.raw_user_meta_data ->> 'full_name', new.raw_user_meta_data ->> 'name', split_part(coalesce(new.email, ''), '@', 1)), 60),
    new.raw_user_meta_data ->> 'avatar_url')
  on conflict (id) do nothing;
  perform public._resolve_email_invites(new.id);
  return new;
end $$;

-- Defined properly in the RPC migration; stub so the trigger compiles in order.
create or replace function public._resolve_email_invites(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$ begin end $$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function public._on_auth_user_created();
