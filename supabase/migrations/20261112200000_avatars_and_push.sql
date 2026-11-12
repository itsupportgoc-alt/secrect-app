-- Secret Chat: profile pictures + push notifications.

-- ---------------------------------------------------------------------------
-- Profile pictures. Public bucket (anyone with the link can view an avatar),
-- but each user can only write inside their own folder: "<user_id>/...".
-- ---------------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit)
values ('avatars', 'avatars', true, 5242880)
on conflict (id) do update
    set public = true,
        file_size_limit = excluded.file_size_limit;

drop policy if exists "users upload their own avatar" on storage.objects;
create policy "users upload their own avatar"
    on storage.objects for insert
    to authenticated
    with check (bucket_id = 'avatars' and split_part(name, '/', 1) = auth.uid()::text);

drop policy if exists "users replace their own avatar" on storage.objects;
create policy "users replace their own avatar"
    on storage.objects for update
    to authenticated
    using (bucket_id = 'avatars' and split_part(name, '/', 1) = auth.uid()::text)
    with check (bucket_id = 'avatars' and split_part(name, '/', 1) = auth.uid()::text);

-- Upsert needs to be able to see the existing object.
drop policy if exists "users read their own avatar object" on storage.objects;
create policy "users read their own avatar object"
    on storage.objects for select
    to authenticated
    using (bucket_id = 'avatars' and split_part(name, '/', 1) = auth.uid()::text);

-- The inbox now also returns the other person's picture.
drop function if exists public.list_conversations();

create function public.list_conversations()
returns table (
    conversation_id uuid,
    title text,
    last_message text,
    last_message_at timestamptz,
    other_user_id uuid,
    other_avatar_url text
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
            select case
                       when msg.body <> '' then msg.body
                       when msg.image_path is not null then '📷 Photo'
                       else msg.body
                   end
            from public.messages msg
            where msg.conversation_id = c.id
              and msg.deleted_at is null
            order by msg.created_at desc
            limit 1
        ) as last_message,
        c.last_message_at,
        other.user_id as other_user_id,
        other.avatar_url as other_avatar_url
    from public.conversations c
    join public.conversation_members me
      on me.conversation_id = c.id
     and me.user_id = auth.uid()
    left join lateral (
        select m.user_id, p.avatar_url
        from public.conversation_members m
        join public.profiles p on p.id = m.user_id
        where m.conversation_id = c.id
          and m.user_id <> auth.uid()
        limit 1
    ) other on true
    order by c.last_message_at desc;
$$;

revoke all on function public.list_conversations() from public;
grant execute on function public.list_conversations() to authenticated;

-- ---------------------------------------------------------------------------
-- Push notifications: every new message calls the "push" Edge Function,
-- which looks the message up with the service role and sends it with
-- Firebase Cloud Messaging. Only the message id is sent from here.
-- ---------------------------------------------------------------------------

create extension if not exists pg_net with schema extensions;

create or replace function public.notify_new_message()
returns trigger
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
    begin
        perform net.http_post(
            url := 'https://wqjxcpknobwxktnuwprw.supabase.co/functions/v1/push',
            headers := jsonb_build_object(
                'Content-Type', 'application/json',
                -- Public anon key (the same one shipped in the app).
                'Authorization', 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndxanhjcGtub2J3eGt0bnV3cHJ3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ0NjcxMTcsImV4cCI6MjExMDA0MzExN30.ewZXjSIkyG9tbvSJStPsYPWVz1LrGcVQLPI4ZeDYNCk'
            ),
            body := jsonb_build_object('message_id', new.id)
        );
    exception when others then
        -- Never block a message because a notification could not be queued.
        raise warning 'push notification not queued: %', sqlerrm;
    end;
    return new;
end;
$$;

revoke all on function public.notify_new_message() from public;

drop trigger if exists on_message_push on public.messages;
create trigger on_message_push
    after insert on public.messages
    for each row execute function public.notify_new_message();

notify pgrst, 'reload schema';
