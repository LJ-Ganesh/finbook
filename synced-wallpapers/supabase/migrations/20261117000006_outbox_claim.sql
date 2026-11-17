-- send-push claims outbox rows with a short lease so that two overlapping invocations
-- (trigger kick + cron) never send the same notification twice.
alter table public.notification_outbox add column claimed_at timestamptz;

create or replace function public._claim_outbox(p_limit int default 200)
returns table (id bigint, user_id uuid, type text, payload jsonb, created_at timestamptz, attempts int, tokens text[])
language sql security definer set search_path = '' as $$
  with picked as (
    select o.id from public.notification_outbox o
    where o.sent_at is null and o.attempts < 8
      and (o.claimed_at is null or o.claimed_at < now() - interval '2 minutes')
      and o.created_at > now() - interval '1 day'      -- stale pushes are pointless; Activity still has them
    order by o.id
    limit p_limit
    for update skip locked)
  update public.notification_outbox o
     set claimed_at = now(), attempts = o.attempts + 1
    from picked
   where o.id = picked.id
  returning o.id, o.user_id, o.type, o.payload, o.created_at, o.attempts,
            array(select t.fcm_token from public.device_tokens t where t.user_id = o.user_id)
$$;

create or replace function public._finish_outbox(p_sent bigint[], p_failed bigint[], p_error text default null)
returns void language sql security definer set search_path = '' as $$
  update public.notification_outbox set sent_at = now(), claimed_at = null, last_error = null where id = any (p_sent);
  update public.notification_outbox set claimed_at = null, last_error = left(p_error, 500) where id = any (p_failed);
$$;

revoke execute on function public._claim_outbox(int), public._finish_outbox(bigint[], bigint[], text) from public, anon, authenticated;
grant execute on function public._claim_outbox(int), public._finish_outbox(bigint[], bigint[], text) to service_role;
