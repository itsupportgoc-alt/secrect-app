-- Secret Chat: image messages.
-- Adds an optional image to messages, a private storage bucket for chat photos,
-- and storage policies so only the members of a conversation can see its photos.

-- ---------------------------------------------------------------------------
-- Messages can carry an image; the text becomes optional for image messages.
-- ---------------------------------------------------------------------------

alter table public.messages add column if not exists image_path text;

alter table public.messages drop constraint if exists messages_body_check;
alter table public.messages drop constraint if exists messages_body_or_image_check;
alter table public.messages add constraint messages_body_or_image_check check (
    char_length(body) <= 8000
    and (char_length(body) >= 1 or image_path is not null)
);

-- ---------------------------------------------------------------------------
-- send_message gains an optional image path. The old 3-argument version is
-- removed so PostgREST never has two candidates to choose from.
-- ---------------------------------------------------------------------------

drop function if exists public.send_message(uuid, text, uuid);

create or replace function public.send_message(
    p_conversation_id uuid,
    p_body text,
    p_client_id uuid,
    p_image_path text default null
)
returns public.messages
language plpgsql
security definer
set search_path = public
as $$
declare
    v_message public.messages;
    v_body text := coalesce(trim(p_body), '');
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;
    if not public.is_conversation_member(p_conversation_id, auth.uid()) then
        raise exception 'not a conversation member' using errcode = '42501';
    end if;
    if p_image_path is not null
       and p_image_path not like p_conversation_id::text || '/%' then
        raise exception 'image does not belong to this conversation' using errcode = '42501';
    end if;
    if char_length(v_body) = 0 and p_image_path is null then
        raise exception 'message body is empty';
    end if;

    insert into public.messages (conversation_id, sender_id, body, client_id, image_path)
    values (p_conversation_id, auth.uid(), v_body, p_client_id, p_image_path)
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

revoke all on function public.send_message(uuid, text, uuid, text) from public;
grant execute on function public.send_message(uuid, text, uuid, text) to authenticated;

-- Inbox previews show "Photo" for image-only messages.
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
-- Private storage bucket. Object names are "<conversation_id>/<client_id>.jpg".
-- ---------------------------------------------------------------------------

-- 10 MB cap per photo (the app compresses photos to well under 1 MB).
insert into storage.buckets (id, name, public, file_size_limit)
values ('chat-images', 'chat-images', false, 10485760)
on conflict (id) do update
    set public = false,
        file_size_limit = excluded.file_size_limit;

-- Returns true when the first folder of an object name is a conversation the
-- current user belongs to. Malformed names simply return false.
create or replace function public.can_access_chat_image(p_name text)
returns boolean
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_conversation uuid;
begin
    begin
        v_conversation := split_part(p_name, '/', 1)::uuid;
    exception when others then
        return false;
    end;
    return public.is_conversation_member(v_conversation, auth.uid());
end;
$$;

revoke all on function public.can_access_chat_image(text) from public;
grant execute on function public.can_access_chat_image(text) to authenticated;

drop policy if exists "members read chat images" on storage.objects;
create policy "members read chat images"
    on storage.objects for select
    to authenticated
    using (bucket_id = 'chat-images' and public.can_access_chat_image(name));

drop policy if exists "members upload chat images" on storage.objects;
create policy "members upload chat images"
    on storage.objects for insert
    to authenticated
    with check (bucket_id = 'chat-images' and public.can_access_chat_image(name));

-- Needed for upsert, which makes a retried upload of the same photo harmless.
drop policy if exists "members replace their chat images" on storage.objects;
create policy "members replace their chat images"
    on storage.objects for update
    to authenticated
    using (bucket_id = 'chat-images' and owner_id = auth.uid()::text)
    with check (bucket_id = 'chat-images' and public.can_access_chat_image(name));

notify pgrst, 'reload schema';
