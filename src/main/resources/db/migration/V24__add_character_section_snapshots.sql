create table character_section_sync_states (
    character_id bigint not null,
    section varchar(30) not null,
    status varchar(20) not null,
    parser_version varchar(40) not null,
    last_attempted_at timestamp with time zone not null,
    last_succeeded_at timestamp with time zone,
    error_code varchar(60),
    error_message varchar(500),
    observed_count integer,
    constraint pk_character_section_sync_states primary key (character_id, section),
    constraint fk_character_section_sync_states_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint ck_character_section_sync_states_section check (case section
        when 'PROFILE' then true when 'STATS' then true when 'EFFECTS_FAITH' then true
        when 'CURRENT_PATTERN' then true when 'POSITION_GUARD' then true when 'SAVED_PATTERNS' then true
        when 'EQUIPMENT' then true when 'EQUIPMENT_CANDIDATES' then true when 'SKILLS' then true
        when 'MANAGEMENT' then true else false end),
    constraint ck_character_section_sync_states_status check (case status
        when 'SUCCESS' then true when 'FAILED' then true else false end),
    constraint ck_character_section_sync_states_success check (
        (status = 'SUCCESS' and last_succeeded_at is not null and error_code is null and error_message is null)
        or (status = 'FAILED' and error_code is not null and error_message is not null)
    ),
    constraint ck_character_section_sync_states_count check (observed_count is null or observed_count >= 0)
);

alter table character_stats add column exp_current bigint;
alter table character_stats add column exp_max bigint;
alter table character_stats add column exp_maxed boolean;
alter table character_stats add column hp_base integer;
alter table character_stats add column hp_bonus integer;
alter table character_stats add column sp_base integer;
alter table character_stats add column sp_bonus integer;
alter table character_stats add column str_real integer;
alter table character_stats add column str_bonus integer;
alter table character_stats add column int_real integer;
alter table character_stats add column int_bonus integer;
alter table character_stats add column dex_real integer;
alter table character_stats add column dex_bonus integer;
alter table character_stats add column spd_real integer;
alter table character_stats add column spd_bonus integer;
alter table character_stats add column luk_real integer;
alter table character_stats add column luk_bonus integer;
alter table character_stats add column exp_description text;
alter table character_stats add column hp_description text;
alter table character_stats add column sp_description text;
alter table character_stats add column str_description text;
alter table character_stats add column int_description text;
alter table character_stats add column dex_description text;
alter table character_stats add column spd_description text;
alter table character_stats add column luk_description text;

create table character_status_effects (
    id bigserial,
    character_id bigint not null,
    effect_order integer not null,
    effect_type varchar(20) not null,
    name text not null,
    value_text text not null,
    description text not null,
    active boolean,
    constraint pk_character_status_effects primary key (id),
    constraint fk_character_status_effects_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_status_effects_character_order unique (character_id, effect_order),
    constraint ck_character_status_effects_order check (effect_order >= 0),
    constraint ck_character_status_effects_type check (case effect_type
        when 'SET' then true when 'EFFECT' then true else false end)
);

create table character_faith (
    character_id bigint,
    god_name text not null,
    current_value bigint not null,
    max_value bigint not null,
    constraint pk_character_faith primary key (character_id),
    constraint fk_character_faith_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint ck_character_faith_values check (current_value >= 0 and max_value >= 0)
);

create table character_pattern_options (
    id bigserial,
    character_id bigint not null,
    option_type varchar(20) not null,
    option_order integer not null,
    source_value text not null,
    label text not null,
    category text,
    constraint pk_character_pattern_options primary key (id),
    constraint fk_character_pattern_options_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_pattern_options_character_type_order unique (character_id, option_type, option_order),
    constraint ck_character_pattern_options_type check (case option_type
        when 'CONDITION' then true when 'SKILL' then true else false end),
    constraint ck_character_pattern_options_order check (option_order >= 0)
);

create table character_equipment_candidates (
    id bigserial,
    character_id bigint not null,
    candidate_order integer not null,
    source_value text not null,
    type_code varchar(40) not null,
    name text not null,
    icon_url text not null,
    description text not null,
    constraint pk_character_equipment_candidates primary key (id),
    constraint fk_character_equipment_candidates_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_equipment_candidates_character_order unique (character_id, candidate_order),
    constraint ck_character_equipment_candidates_order check (candidate_order >= 0)
);

alter table character_skills add column target_text text;
alter table character_skills add column scope_text text;
alter table character_skills add column sp_cost integer;
alter table character_skills add column multiplier_text text;
alter table character_skills add column description text;

alter table character_pattern_slots add column selected_position text;
alter table character_pattern_slots add column guard_value text;
alter table character_pattern_slots add column guard_text text;

create table character_saved_pattern_rows (
    id bigserial,
    pattern_slot_id bigint not null,
    row_index integer not null,
    judge text not null,
    judge_text text not null,
    quantity text not null,
    quantity_text text not null,
    skill text not null,
    skill_text text not null,
    constraint pk_character_saved_pattern_rows primary key (id),
    constraint fk_character_saved_pattern_rows_slot foreign key (pattern_slot_id)
        references character_pattern_slots (id) on delete cascade,
    constraint uk_character_saved_pattern_rows_slot_row unique (pattern_slot_id, row_index),
    constraint ck_character_saved_pattern_rows_index check (row_index >= 0)
);

create table character_equipment_saved_slots (
    id bigserial,
    character_id bigint not null,
    slot_number integer not null,
    observed_at timestamp with time zone not null,
    constraint pk_character_equipment_saved_slots primary key (id),
    constraint fk_character_equipment_saved_slots_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_equipment_saved_slots_character_number unique (character_id, slot_number),
    constraint ck_character_equipment_saved_slots_number check (slot_number between 1 and 2)
);

create table character_equipment_saved_items (
    id bigserial,
    equipment_saved_slot_id bigint not null,
    item_order integer not null,
    equipment_part text not null,
    name text not null,
    icon_url text not null,
    description text not null,
    constraint pk_character_equipment_saved_items primary key (id),
    constraint fk_character_equipment_saved_items_slot foreign key (equipment_saved_slot_id)
        references character_equipment_saved_slots (id) on delete cascade,
    constraint uk_character_equipment_saved_items_slot_order unique (equipment_saved_slot_id, item_order),
    constraint ck_character_equipment_saved_items_order check (item_order >= 0)
);

create index idx_character_section_sync_states_status
    on character_section_sync_states (character_id, status, last_attempted_at);
create index idx_character_status_effects_character
    on character_status_effects (character_id, effect_order, id);
create index idx_character_pattern_options_character
    on character_pattern_options (character_id, option_type, option_order, id);
create index idx_character_equipment_candidates_character
    on character_equipment_candidates (character_id, type_code, candidate_order, id);
create index idx_character_saved_pattern_rows_slot
    on character_saved_pattern_rows (pattern_slot_id, row_index, id);
create index idx_character_equipment_saved_items_slot
    on character_equipment_saved_items (equipment_saved_slot_id, item_order, id);
