-- Secret Chat: schema, row level security, RPCs and realtime setup.
-- Run this in the Supabase SQL editor, or with `supabase db push`.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

create table if not exists public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    username text not null unique check (username ~ '^[a-z0-9_]{3,24}$'),
    display_name text not null default '',
    avatar_url text,
    created_at timestamptz not null default now()
);

create table if not exists public.conversations (
    id uuid primary key default gen_random_uuid(),
    kind text not null default 'dm' check (kind in ('dm', 'group')),
    title text,
    created_by uuid not null references public.profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    last_message_at timestamptz not null default now()
);

create table if not exists public.conversation_members (
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    user_id uuid not null references public.profiles (id) on delete cascade,
    joined_at timestamptz not null default now(),
    last_read_at timestamptz not null default now(),
    primary key (conversation_id, user_id)
);

create table if not exists public.messages (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    sender_id uuid not null references public.profiles (id) on delete cascade,
    body text not null check (char_length(body) between 1 and 8000),
    -- Client generated idempotency key: retries never create a second message.
    client_id uuid not null,
    created_at timestamptz not null default now(),
    edited_at timestamptz,
    deleted_at timestamptz,
    unique (conversation_id, client_id)
);

create index if not exists messages_conversation_created_idx
    on public.messages (conversation_id, created_at desc);
create index if not exists conversation_members_user_idx
    on public.conversation_members (user_id);

create table if not exists public.device_tokens (
    token text primary key,
    user_id uuid not null references public.profiles (id) on delete cascade,
    platform text not null default 'android' check (platform in ('android', 'ios')),
    updated_at timestamptz not null default now()
);

create index if not exists device_tokens_user_idx on public.device_tokens (user_id);

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------

create or replace function public.is_conversation_member(
    p_conversation_id uuid,
    p_user_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
        from public.conversation_members m
        where m.conversation_id = p_conversation_id
          and m.user_id = p_user_id
    );
$$;

revoke all on function public.is_conversation_member(uuid, uuid) from public;
grant execute on function public.is_conversation_member(uuid, uuid) to authenticated;

-- ---------------------------------------------------------------------------
-- Row level security
-- ---------------------------------------------------------------------------

alter table public.profiles enable row level security;
alter table public.conversations enable row level security;
alter table public.conversation_members enable row level security;
alter table public.messages enable row level security;
alter table public.device_tokens enable row level security;

-- Explicit API grants; RLS still decides which rows are visible.
grant select on public.profiles to authenticated;
grant update (display_name, avatar_url) on public.profiles to authenticated;
grant select on public.conversations to authenticated;
grant select on public.conversation_members to authenticated;
grant select, insert, update on public.messages to authenticated;
grant select, insert, update, delete on public.device_tokens to authenticated;

drop policy if exists "profiles are readable by signed in users" on public.profiles;
create policy "profiles are readable by signed in users"
    on public.profiles for select
    to authenticated
    using (true);

drop policy if exists "users update their own profile" on public.profiles;
create policy "users update their own profile"
    on public.profiles for update
    to authenticated
    using (id = auth.uid())
    with check (id = auth.uid());

drop policy if exists "members can read their conversations" on public.conversations;
create policy "members can read their conversations"
    on public.conversations for select
    to authenticated
    using (public.is_conversation_member(id, auth.uid()));

drop policy if exists "members can read the member list" on public.conversation_members;
create policy "members can read the member list"
    on public.conversation_members for select
    to authenticated
    using (public.is_conversation_member(conversation_id, auth.uid()));

drop policy if exists "members can read messages" on public.messages;
create policy "members can read messages"
    on public.messages for select
    to authenticated
    using (public.is_conversation_member(conversation_id, auth.uid()));

drop policy if exists "members send their own messages" on public.messages;
create policy "members send their own messages"
    on public.messages for insert
    to authenticated
    with check (
        sender_id = auth.uid()
        and public.is_conversation_member(conversation_id, auth.uid())
    );

drop policy if exists "senders edit their own messages" on public.messages;
create policy "senders edit their own messages"
    on public.messages for update
    to authenticated
    using (sender_id = auth.uid())
    with check (sender_id = auth.uid());

drop policy if exists "users manage their device tokens" on public.device_tokens;
create policy "users manage their device tokens"
    on public.device_tokens for all
    to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- RPCs used by the Android client
-- ---------------------------------------------------------------------------

-- Creates a profile for every new auth user and resolves a unique username.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    base text;
    candidate text;
    suffix int := 0;
begin
    base := lower(
        regexp_replace(
            coalesce(new.raw_user_meta_data ->> 'username', split_part(coalesce(new.email, 'user'), '@', 1)),
            '[^a-z0-9_]', '', 'g'
        )
    );
    if length(base) < 3 then
        base := 'user';
    end if;
    base := left(base, 18);

    candidate := base;
    while exists (select 1 from public.profiles where username = candidate) loop
        suffix := suffix + 1;
        candidate := left(base, 15) || '_' || suffix::text;
    end loop;

    insert into public.profiles (id, username, display_name)
    values (
        new.id,
        candidate,
        coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), candidate)
    );
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

revoke all on function public.handle_new_user() from public;

-- Starts (or returns the existing) 1:1 conversation with the given username.
create or replace function public.start_dm(p_username text)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    v_me uuid := auth.uid();
    v_other uuid;
    v_conversation uuid;
begin
    if v_me is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    select id into v_other
    from public.profiles
    where username = lower(trim(p_username));

    if v_other is null then
        raise exception 'User not found' using errcode = 'P0002';
    end if;
    if v_other = v_me then
        raise exception 'Cannot start a chat with yourself';
    end if;

    select c.id into v_conversation
    from public.conversations c
    join public.conversation_members me
      on me.conversation_id = c.id and me.user_id = v_me
    join public.conversation_members them
      on them.conversation_id = c.id and them.user_id = v_other
    where c.kind = 'dm'
    limit 1;

    if v_conversation is not null then
        return v_conversation;
    end if;

    insert into public.conversations (kind, created_by)
    values ('dm', v_me)
    returning id into v_conversation;

    insert into public.conversation_members (conversation_id, user_id)
    values (v_conversation, v_me), (v_conversation, v_other);

    return v_conversation;
end;
$$;

revoke all on function public.start_dm(text) from public;
grant execute on function public.start_dm(text) to authenticated;

-- Idempotent send: replaying the same client_id returns the original message.
create or replace function public.send_message(
    p_conversation_id uuid,
    p_body text,
    p_client_id uuid
)
returns public.messages
language plpgsql
security definer
set search_path = public
as $$
declare
    v_message public.messages;
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;
    if not public.is_conversation_member(p_conversation_id, auth.uid()) then
        raise exception 'not a conversation member' using errcode = '42501';
    end if;
    if p_body is null or char_length(trim(p_body)) = 0 then
        raise exception 'message body is empty';
    end if;

    insert into public.messages (conversation_id, sender_id, body, client_id)
    values (p_conversation_id, auth.uid(), trim(p_body), p_client_id)
    on conflict (conversation_id, client_id) do nothing
    returning * into v_message;

    if v_message.id is null then
        select * into v_message
        from public.messages
        where conversation_id = p_conversation_id
          and client_id = p_client_id;
    end if;

    return v_message;
end;
$$;

revoke all on function public.send_message(uuid, text, uuid) from public;
grant execute on function public.send_message(uuid, text, uuid) to authenticated;

-- Inbox previews for the conversation list screen.
create or replace function public.list_conversations()
returns table (
    conversation_id uuid,
    title text,
    last_message text,
    last_message_at timestamptz,
    other_user_id uuid
)
language sql
stable
security definer
set search_path = public
as $$
    select
        c.id as conversation_id,
        coalesce(
            c.title,
            (
                select coalesce(nullif(p.display_name, ''), '@' || p.username)
                from public.conversation_members m
                join public.profiles p on p.id = m.user_id
                where m.conversation_id = c.id
                  and m.user_id <> auth.uid()
                limit 1
            ),
            'Chat'
        ) as title,
        (
            select msg.body
            from public.messages msg
            where msg.conversation_id = c.id
              and msg.deleted_at is null
            order by msg.created_at desc
            limit 1
        ) as last_message,
        c.last_message_at,
        (
            select m.user_id
            from public.conversation_members m
            where m.conversation_id = c.id
              and m.user_id <> auth.uid()
            limit 1
        ) as other_user_id
    from public.conversations c
    join public.conversation_members me
      on me.conversation_id = c.id
     and me.user_id = auth.uid()
    order by c.last_message_at desc;
$$;

revoke all on function public.list_conversations() from public;
grant execute on function public.list_conversations() to authenticated;

-- ---------------------------------------------------------------------------
-- Realtime + maintenance
-- ---------------------------------------------------------------------------

create or replace function public.touch_conversation()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.conversations
    set last_message_at = new.created_at
    where id = new.conversation_id;
    return new;
end;
$$;

drop trigger if exists on_message_created on public.messages;
create trigger on_message_created
    after insert on public.messages
    for each row execute function public.touch_conversation();

revoke all on function public.touch_conversation() from public;

alter table public.messages replica identity full;
alter table public.conversations replica identity full;

do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        begin
            alter publication supabase_realtime add table public.messages;
        exception when duplicate_object then null;
        end;
        begin
            alter publication supabase_realtime add table public.conversations;
        exception when duplicate_object then null;
        end;
    end if;
end;
$$;

notify pgrst, 'reload schema';
