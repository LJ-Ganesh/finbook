-- Synced Wallpapers: core schema.
--
-- Every table has RLS enabled (policies live in the next migration). Clients never
-- write tables directly except where a column-level grant says so; all state changes
-- go through SECURITY DEFINER RPCs (see 20261117000003_rpc.sql).

create schema if not exists extensions;
create extension if not exists btree_gist with schema extensions;
create extension if not exists pgcrypto with schema extensions;

-- Constants -----------------------------------------------------------------------

create or replace function public.app_max_members() returns int
language sql immutable parallel safe set search_path = '' as $$ select 20 $$;

-- Profiles ------------------------------------------------------------------------

create table public.profiles (
  id             uuid primary key references auth.users (id) on delete cascade,
  email          text,
  name           text not null default '' check (char_length(name) <= 60),
  photo_url      text check (photo_url is null or char_length(photo_url) <= 1000),
  -- Optional, visible only to people who share a group with this user (RLS).
  phone_optional text check (phone_optional is null or phone_optional ~ '^\+[1-9][0-9]{6,14}$'),
  created_at     timestamptz not null default now()
);
create index profiles_email_idx on public.profiles (lower(email));

create table public.device_tokens (
  fcm_token  text primary key check (char_length(fcm_token) between 10 and 4096),
  user_id    uuid not null references public.profiles (id) on delete cascade,
  updated_at timestamptz not null default now()
);
create index device_tokens_user_idx on public.device_tokens (user_id);

-- Groups --------------------------------------------------------------------------

create table public.groups (
  id                     uuid primary key default gen_random_uuid(),
  name                   text not null check (char_length(btrim(name)) between 1 and 40),
  time_zone              text not null,
  max_members            int  not null default 20 check (max_members between 1 and 20),
  admin_id               uuid not null references public.profiles (id),
  -- Set when admin passed automatically (the previous admin left); the new admin may decline.
  admin_handover_pending boolean not null default false,
  admin_declined_by      uuid[] not null default '{}',
  created_at             timestamptz not null default now()
);

create table public.group_members (
  group_id      uuid not null references public.groups (id) on delete cascade,
  user_id       uuid not null references public.profiles (id) on delete cascade,
  joined_at     timestamptz not null default clock_timestamp(),
  delivery_mode text not null default 'AUTO' check (delivery_mode in ('AUTO', 'TAP')),
  primary key (group_id, user_id)
);
create index group_members_user_idx on public.group_members (user_id);

-- Invites and join requests -------------------------------------------------------

create table public.invites (
  id            uuid primary key default gen_random_uuid(),
  group_id      uuid not null references public.groups (id) on delete cascade,
  code          text not null unique,
  email_optional text,
  created_by    uuid not null references public.profiles (id) on delete cascade,
  expires_at    timestamptz not null,
  used_at       timestamptz,
  used_by       uuid references public.profiles (id) on delete set null,
  created_at    timestamptz not null default now()
);
create index invites_email_idx on public.invites (lower(email_optional)) where used_at is null;

create table public.join_requests (
  id                uuid primary key default gen_random_uuid(),
  group_id          uuid not null references public.groups (id) on delete cascade,
  user_id           uuid not null references public.profiles (id) on delete cascade,
  invite_id         uuid references public.invites (id) on delete set null,
  status            text not null default 'PENDING'
                    check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
  -- The joiner accepted every upcoming schedule at this instant.
  terms_accepted_at timestamptz,
  result_note       text,
  created_at        timestamptz not null default now(),
  decided_at        timestamptz
);
create unique index join_requests_one_pending on public.join_requests (group_id, user_id) where status = 'PENDING';

create table public.join_votes (
  request_id uuid not null references public.join_requests (id) on delete cascade,
  voter_id   uuid not null references public.profiles (id) on delete cascade,
  decision   text not null check (decision in ('APPROVE', 'REJECT')),
  reason     text check (reason is null or char_length(reason) <= 200),
  at         timestamptz not null default now(),
  primary key (request_id, voter_id)
);

-- Admin actions -------------------------------------------------------------------

create table public.admin_actions (
  id          uuid primary key default gen_random_uuid(),
  group_id    uuid not null references public.groups (id) on delete cascade,
  type        text not null check (type in ('RENAME', 'TIMEZONE', 'MEMBER_LIMIT', 'REMOVE_MEMBER', 'TRANSFER_ADMIN', 'DELETE_GROUP')),
  payload     jsonb not null default '{}',
  created_by  uuid not null references public.profiles (id) on delete cascade,
  status      text not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
  result_note text,
  deadline    timestamptz not null,
  created_at  timestamptz not null default now(),
  decided_at  timestamptz
);
create unique index admin_actions_one_pending_per_type on public.admin_actions (group_id, type) where status = 'PENDING';

create table public.admin_votes (
  action_id uuid not null references public.admin_actions (id) on delete cascade,
  voter_id  uuid not null references public.profiles (id) on delete cascade,
  decision  text not null check (decision in ('APPROVE', 'REJECT')),
  reason    text check (reason is null or char_length(reason) <= 200),
  at        timestamptz not null default now(),
  primary key (action_id, voter_id)
);

-- Proposals -----------------------------------------------------------------------

create table public.proposals (
  id               uuid primary key default gen_random_uuid(),
  group_id         uuid not null references public.groups (id) on delete cascade,
  -- Nullable so that deleting an account keeps the row long enough to purge the image.
  proposer_id      uuid references public.profiles (id) on delete set null,
  start_at         timestamptz not null,
  end_at           timestamptz not null,
  target           text not null check (target in ('HOME', 'LOCK', 'BOTH')),
  image_path       text not null,
  -- False until the proposer has uploaded the image and called confirm_proposal_image.
  image_ready      boolean not null default false,
  focal_x          real not null default 0.5 check (focal_x between 0 and 1),
  focal_y          real not null default 0.5 check (focal_y between 0 and 1),
  reminder_text    text check (reminder_text is null or char_length(reminder_text) between 1 and 60),
  reminder_style   smallint not null default 0 check (reminder_style between 0 and 2),
  status           text not null default 'PENDING'
                   check (status in ('PENDING', 'APPROVED', 'LIVE', 'ENDED', 'PURGED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
  vote_deadline    timestamptz not null,
  replaces_id      uuid references public.proposals (id) on delete set null,
  cancel_reason    text,
  created_at       timestamptz not null default now(),
  approved_at      timestamptz,
  decided_at       timestamptz,
  reminder_sent_at timestamptz,
  purged_at        timestamptz,
  check (end_at > start_at),
  check (end_at - start_at between interval '15 minutes' and interval '30 days'),
  check (vote_deadline = start_at - interval '15 minutes'),
  -- Group lane: one schedule at a time per group. Races are impossible because the
  -- database itself refuses the second overlapping row.
  constraint proposals_group_lane exclude using gist (
    group_id with =,
    tstzrange(start_at, end_at, '[)') with &&
  ) where (status in ('PENDING', 'APPROVED', 'LIVE'))
);
create index proposals_group_status_idx on public.proposals (group_id, status);
create index proposals_proposer_idx on public.proposals (proposer_id);
create index proposals_active_time_idx on public.proposals (start_at, end_at) where status in ('PENDING', 'APPROVED', 'LIVE');
create index proposals_image_path_idx on public.proposals (image_path);

create table public.proposal_votes (
  proposal_id uuid not null references public.proposals (id) on delete cascade,
  voter_id    uuid not null references public.profiles (id) on delete cascade,
  decision    text not null check (decision in ('APPROVE', 'REJECT')),
  reason      text check (reason is null or char_length(reason) <= 200),
  at          timestamptz not null default now(),
  primary key (proposal_id, voter_id)
);
create index proposal_votes_voter_idx on public.proposal_votes (voter_id);

create table public.time_suggestions (
  id              uuid primary key default gen_random_uuid(),
  proposal_id     uuid not null references public.proposals (id) on delete cascade,
  from_user       uuid not null references public.profiles (id) on delete cascade,
  suggested_start timestamptz not null,
  suggested_end   timestamptz not null,
  at              timestamptz not null default now(),
  check (suggested_end > suggested_start)
);

create table public.skips (
  proposal_id uuid not null references public.proposals (id) on delete cascade,
  user_id     uuid not null references public.profiles (id) on delete cascade,
  at          timestamptz not null default now(),
  primary key (proposal_id, user_id)
);

create table public.deliveries (
  proposal_id uuid not null references public.proposals (id) on delete cascade,
  user_id     uuid not null references public.profiles (id) on delete cascade,
  state       text not null check (state in ('DOWNLOADING', 'READY', 'NOT_READY', 'LIVE', 'APPLIED_LATE',
                                             'NOT_TAPPED', 'SKIPPED', 'MISSED', 'ENDED')),
  at          timestamptz not null default now(),
  primary key (proposal_id, user_id)
);

-- Notifications -------------------------------------------------------------------

-- Written by RPCs and jobs; read by send-push (service role) and by the owner (Activity inbox).
create table public.notification_outbox (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references public.profiles (id) on delete cascade,
  type       text not null,
  payload    jsonb not null default '{}',
  created_at timestamptz not null default now(),
  sent_at    timestamptz,
  attempts   int not null default 0,
  last_error text,
  read_at    timestamptz
);
create index notification_outbox_unsent_idx on public.notification_outbox (created_at) where sent_at is null;
create index notification_outbox_user_idx on public.notification_outbox (user_id, created_at desc);

-- Images waiting to be deleted from Storage by the purge-images edge function.
create table public.storage_purge_queue (
  image_path text primary key,
  due_at     timestamptz not null default now(),
  attempts   int not null default 0
);

-- RLS on everything (policies are in the next migration; no policy = no access).
alter table public.profiles            enable row level security;
alter table public.device_tokens       enable row level security;
alter table public.groups              enable row level security;
alter table public.group_members       enable row level security;
alter table public.invites             enable row level security;
alter table public.join_requests       enable row level security;
alter table public.join_votes          enable row level security;
alter table public.admin_actions       enable row level security;
alter table public.admin_votes         enable row level security;
alter table public.proposals           enable row level security;
alter table public.proposal_votes      enable row level security;
alter table public.time_suggestions    enable row level security;
alter table public.skips               enable row level security;
alter table public.deliveries          enable row level security;
alter table public.notification_outbox enable row level security;
alter table public.storage_purge_queue enable row level security;
