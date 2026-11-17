-- Storage: one private bucket. Path = {group_id}/{proposal_id}.jpg.
-- 2 MB limit and JPEG only: the app always uploads a re-encoded, metadata-free JPEG.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('proposal-images', 'proposal-images', false, 2097152, array['image/jpeg'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit,
                               allowed_mime_types = excluded.allowed_mime_types;

-- Read: members of the group, and someone with a pending join request (they must see
-- the upcoming schedules they are asked to accept). Signed URLs are minted from this.
create or replace function public.can_read_image(p_name text)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.proposals p
    where p.image_path = p_name
      and p.status in ('PENDING', 'APPROVED', 'LIVE', 'ENDED')
      and (public.is_member(p.group_id)
           or exists (select 1 from public.join_requests r
                      where r.group_id = p.group_id and r.user_id = auth.uid() and r.status = 'PENDING')))
$$;

-- Upload: only the proposer, only to the path reserved by create_proposal, only while
-- that proposal is PENDING and its image has not been confirmed yet.
create or replace function public.can_upload_image(p_name text)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.proposals p
    where p.image_path = p_name and p.proposer_id = auth.uid()
      and p.status = 'PENDING' and not p.image_ready)
$$;

grant execute on function public.can_read_image(text), public.can_upload_image(text) to authenticated;

drop policy if exists "proposal images: read" on storage.objects;
create policy "proposal images: read" on storage.objects for select to authenticated
  using (bucket_id = 'proposal-images' and public.can_read_image(name));

drop policy if exists "proposal images: upload" on storage.objects;
create policy "proposal images: upload" on storage.objects for insert to authenticated
  with check (bucket_id = 'proposal-images' and public.can_upload_image(name));
-- No update/delete policies: clients can never overwrite or delete images.

-- Realtime: live updates while the app is open. Realtime applies the RLS above,
-- so each client only receives rows it may read.
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    alter publication supabase_realtime add table
      public.groups, public.group_members, public.proposals, public.admin_actions,
      public.join_requests, public.notification_outbox;
  else
    raise notice 'supabase_realtime publication not found; skipping (not a Supabase database?)';
  end if;
end $$;
