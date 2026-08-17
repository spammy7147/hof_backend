alter table characters
    drop constraint uk_characters_account_hof_character;

alter table characters
    rename column hof_character_id to current_hof_character_id;

alter table characters
    add column lifecycle varchar(20) not null default 'ACTIVE';

alter table characters
    add column last_seen_at timestamp with time zone;

alter table characters
    add column missing_since timestamp with time zone;

alter table characters
    add column archived_at timestamp with time zone;

alter table characters
    add column roster_order integer;

update characters
set last_seen_at = updated_at;

alter table characters
    add constraint uk_characters_account_current_hof_character
        unique (account_id, current_hof_character_id);

alter table characters
    add constraint uk_characters_id_account unique (id, account_id);

alter table characters
    add constraint ck_characters_lifecycle
        check (case lifecycle
            when 'ACTIVE' then true
            when 'MISSING' then true
            when 'ARCHIVED' then true
            else false
        end);

alter table characters
    add constraint ck_characters_roster_order
        check (roster_order is null or roster_order >= 0);

create table character_hof_id_history (
    id bigserial,
    character_id bigint not null,
    account_id bigint not null,
    hof_character_id varchar(50) not null,
    valid_from timestamp with time zone not null,
    valid_to timestamp with time zone,
    link_reason varchar(30) not null,
    user_confirmed boolean not null,
    open_marker integer,
    constraint pk_character_hof_id_history primary key (id),
    constraint fk_character_hof_id_history_character_account
        foreign key (character_id, account_id)
        references characters (id, account_id) on delete cascade,
    constraint uk_character_hof_id_history_account_hof_character
        unique (account_id, hof_character_id),
    constraint uk_character_hof_id_history_character_open
        unique (character_id, open_marker),
    constraint ck_character_hof_id_history_reason
        check (case link_reason
            when 'INITIAL_SYNC' then true
            when 'KNOCKBACK' then true
            when 'MANUAL_LINK' then true
            when 'REAPPEARED' then true
            else false
        end),
    constraint ck_character_hof_id_history_open_marker
        check (
            (valid_to is null and open_marker = 1)
            or (valid_to is not null and open_marker is null)
        )
);

create index idx_character_hof_id_history_character_time
    on character_hof_id_history (character_id, valid_from, id);

insert into character_hof_id_history (
    character_id,
    account_id,
    hof_character_id,
    valid_from,
    valid_to,
    link_reason,
    user_confirmed,
    open_marker
)
select
    id,
    account_id,
    current_hof_character_id,
    updated_at,
    null,
    'INITIAL_SYNC',
    true,
    1
from characters;

create index idx_characters_account_lifecycle_roster
    on characters (account_id, lifecycle, roster_order, id);
