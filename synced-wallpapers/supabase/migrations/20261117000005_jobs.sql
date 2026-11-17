-- Scheduled jobs (pg_cron) and calls to edge functions (pg_net). Both are available on
-- the Supabase Free plan. Edge function URL and a shared secret are read from Vault:
--
--   select vault.create_secret('https://<project-ref>.supabase.co', 'sw_project_url');
--   select vault.create_secret('<long random string>',            'sw_cron_secret');
--
-- (Locally: 'http://host.docker.internal:54321' or 'http://kong:8000' for the URL.)
-- The same secret must be set for the functions: `supabase secrets set CRON_SECRET=...`.

do $$
begin
  create extension if not exists pg_net with schema extensions;
exception when others then
  raise notice 'pg_net not available (%); edge functions will not be called from the database', sqlerrm;
end $$;

do $$
begin
  create extension if not exists pg_cron;
exception when others then
  raise notice 'pg_cron not available (%); scheduled jobs are not installed', sqlerrm;
end $$;

-- Fire-and-forget POST to an edge function. Never fails the calling transaction.
create or replace function public._invoke_edge(p_function text)
returns void language plpgsql security definer set search_path = '' as $$
declare v_url text; v_secret text;
begin
  begin
    select decrypted_secret into v_url from vault.decrypted_secrets where name = 'sw_project_url';
    select decrypted_secret into v_secret from vault.decrypted_secrets where name = 'sw_cron_secret';
  exception when others then
    return; -- no Vault (plain Postgres in tests)
  end;
  if v_url is null or v_secret is null then return; end if;
  begin
    perform net.http_post(
      url := rtrim(v_url, '/') || '/functions/v1/' || p_function,
      headers := jsonb_build_object('Content-Type', 'application/json', 'x-cron-secret', v_secret),
      body := '{}'::jsonb,
      timeout_milliseconds := 10000);
  exception when others then
    raise notice '_invoke_edge(%) failed: %', p_function, sqlerrm;
  end;
end $$;
revoke execute on function public._invoke_edge(text) from public, anon, authenticated;

-- Push quickly: kick send-push once per statement that queued notifications. The
-- request is queued by pg_net only if the transaction commits. The every-minute job
-- below retries anything that failed.
create or replace function public._outbox_kick()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  perform public._invoke_edge('send-push');
  return null;
end $$;

create trigger notification_outbox_kick after insert on public.notification_outbox
  for each statement execute function public._outbox_kick();

-- Jobs are idempotent and quick; re-running a migration replaces them by name.
do $$
begin
  if not exists (select 1 from pg_extension where extname = 'pg_cron') then return; end if;
  perform cron.schedule('sw-tick',         '* * * * *',   'select public._tick()');
  perform cron.schedule('sw-send-push',    '* * * * *',   'select public._invoke_edge(''send-push'')');
  perform cron.schedule('sw-purge-images', '*/5 * * * *', 'select public._invoke_edge(''purge-images'')');
  perform cron.schedule('sw-cleanup',      '17 * * * *',  'select public._cleanup_old_rows()');
end $$;
