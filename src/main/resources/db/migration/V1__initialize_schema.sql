-- Consolidated baseline through the former V22 migration. Apply only to an empty database.
create table hof_accounts (
    id bigserial,
    login_id varchar(255) not null,
    encrypted_password varchar(255) not null,
    created_at timestamp with time zone not null,
    last_login_at timestamp with time zone,
    constraint pk_hof_accounts primary key (id),
    constraint uk_hof_accounts_login_id unique (login_id)
);

create table hof_cookies (
    id bigserial,
    account_id bigint not null,
    name varchar(255) not null,
    cookie_value varchar(255) not null,
    domain varchar(255),
    path varchar(255),
    expires_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    constraint pk_hof_cookies primary key (id),
    constraint fk_hof_cookies_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_hof_cookies_account_name unique (account_id, name)
);

create index idx_hof_cookies_account_updated
    on hof_cookies (account_id, updated_at, id);

create table characters (
    id bigserial,
    account_id bigint not null,
    hof_character_id varchar(50) not null,
    name varchar(100) not null,
    job varchar(100) not null,
    level integer,
    pattern_slot_count integer not null,
    image_url text,
    updated_at timestamp with time zone not null,
    constraint pk_characters primary key (id),
    constraint fk_characters_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_characters_account_hof_character unique (account_id, hof_character_id),
    constraint ck_characters_pattern_slot_count check (pattern_slot_count >= 0)
);

create index idx_characters_account_name
    on characters (account_id, name, id);

create table character_stats (
    character_id bigint,
    atk integer,
    matk integer,
    def_base integer,
    def_bonus integer,
    mdef_base integer,
    mdef_bonus integer,
    handle_used integer,
    handle_max integer,
    cost_used integer,
    cost_max integer,
    constraint pk_character_stats primary key (character_id),
    constraint fk_character_stats_character foreign key (character_id)
        references characters (id) on delete cascade
);

create table character_status_lines (
    id bigserial,
    character_id bigint not null,
    line_order integer not null,
    content text not null,
    constraint pk_character_status_lines primary key (id),
    constraint fk_character_status_lines_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_status_lines_character_order unique (character_id, line_order),
    constraint ck_character_status_lines_order check (line_order >= 0)
);

create table character_pattern_slots (
    id bigserial,
    character_id bigint not null,
    slot_code varchar(255) not null,
    label text not null,
    can_load boolean not null,
    constraint pk_character_pattern_slots primary key (id),
    constraint fk_character_pattern_slots_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_pattern_slots_character_code unique (character_id, slot_code)
);

create table character_action_patterns (
    id bigserial,
    character_id bigint not null,
    row_index integer not null,
    judge text not null,
    judge_text text not null,
    quantity text not null,
    quantity_text text not null,
    skill text not null,
    skill_text text not null,
    constraint pk_character_action_patterns primary key (id),
    constraint fk_character_action_patterns_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_action_patterns_character_row unique (character_id, row_index),
    constraint ck_character_action_patterns_row check (row_index >= 0)
);

create table character_guard_settings (
    character_id bigint,
    selected_position text not null,
    guard_value text not null,
    guard_text text not null,
    constraint pk_character_guard_settings primary key (character_id),
    constraint fk_character_guard_settings_character foreign key (character_id)
        references characters (id) on delete cascade
);

create table character_position_choices (
    id bigserial,
    character_id bigint not null,
    choice_order integer not null,
    "value" text not null,
    checked boolean not null,
    constraint pk_character_position_choices primary key (id),
    constraint fk_character_position_choices_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_position_choices_character_order unique (character_id, choice_order),
    constraint ck_character_position_choices_order check (choice_order >= 0)
);

create table character_equipment (
    id bigserial,
    character_id bigint not null,
    equipment_order integer not null,
    slot text not null,
    part text not null,
    name text not null,
    icon_url text not null,
    description text not null,
    checked boolean not null,
    constraint pk_character_equipment primary key (id),
    constraint fk_character_equipment_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_equipment_character_order unique (character_id, equipment_order),
    constraint ck_character_equipment_order check (equipment_order >= 0)
);

create table character_skills (
    id bigserial,
    character_id bigint not null,
    skill_type varchar(255) not null,
    skill_order integer not null,
    source_value text not null,
    name text not null,
    icon_url text not null,
    category text not null,
    constraint pk_character_skills primary key (id),
    constraint fk_character_skills_character foreign key (character_id)
        references characters (id) on delete cascade,
    constraint uk_character_skills_character_type_order unique (character_id, skill_type, skill_order),
    constraint ck_character_skills_order check (skill_order >= 0)
);

create table character_sync_jobs (
    id bigserial,
    account_id bigint not null,
    status varchar(255) not null,
    roster_count integer not null,
    synced_count integer not null,
    message text,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    constraint pk_character_sync_jobs primary key (id),
    constraint fk_character_sync_jobs_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint ck_character_sync_jobs_roster_count check (roster_count >= 0),
    constraint ck_character_sync_jobs_synced_count check (synced_count >= 0)
);

create index idx_character_sync_jobs_account_started
    on character_sync_jobs (account_id, started_at, id);

create table character_sync_failures (
    id bigserial,
    sync_job_id bigint not null,
    failure_order integer not null,
    hof_character_id varchar(255) not null,
    constraint pk_character_sync_failures primary key (id),
    constraint fk_character_sync_failures_job foreign key (sync_job_id)
        references character_sync_jobs (id) on delete cascade,
    constraint uk_character_sync_failures_job_character unique (sync_job_id, hof_character_id),
    constraint uk_character_sync_failures_job_order unique (sync_job_id, failure_order),
    constraint ck_character_sync_failures_order check (failure_order >= 0)
);

create table battle_map_groups (
    id bigserial,
    category_id varchar(50) not null,
    name varchar(200) not null,
    display_order integer not null,
    recommended_level varchar(50),
    constraint pk_battle_map_groups primary key (id),
    constraint uk_battle_map_groups_category_name unique (category_id, name),
    constraint ck_battle_map_groups_display_order check (display_order >= 0)
);

create index idx_battle_map_groups_category_display
    on battle_map_groups (category_id, display_order, name, id);

create table battle_maps (
    id bigserial,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    group_id bigint,
    name varchar(300) not null,
    normalized_name varchar(300) not null,
    display_order integer not null,
    required_time integer,
    icon_url text,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_battle_maps primary key (id),
    constraint fk_battle_maps_group foreign key (group_id)
        references battle_map_groups (id) on delete set null,
    constraint uk_battle_maps_category_map_code unique (category_id, map_code),
    constraint ck_battle_maps_display_order check (display_order >= 0),
    constraint ck_battle_maps_required_time check (required_time is null or required_time >= 0)
);

create index idx_battle_maps_category_display
    on battle_maps (category_id, group_id, display_order, name, id);

create index idx_battle_maps_category_normalized_name
    on battle_maps (category_id, normalized_name, id);

create table battle_map_aliases (
    id bigserial,
    battle_map_id bigint not null,
    alias varchar(300) not null,
    normalized_alias varchar(300) not null,
    constraint pk_battle_map_aliases primary key (id),
    constraint fk_battle_map_aliases_map foreign key (battle_map_id)
        references battle_maps (id) on delete cascade,
    constraint uk_battle_map_aliases_map_normalized unique (battle_map_id, normalized_alias)
);

create index idx_battle_map_aliases_normalized
    on battle_map_aliases (normalized_alias, battle_map_id);

create table account_battle_map_states (
    id bigserial,
    account_id bigint not null,
    battle_map_id bigint not null,
    key_count integer,
    available_count integer,
    attempt_remaining integer,
    win_remaining integer,
    cooldown_until timestamp with time zone,
    raw_href text not null,
    visible boolean not null,
    last_seen_at timestamp with time zone not null,
    constraint pk_account_battle_map_states primary key (id),
    constraint fk_account_battle_map_states_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint fk_account_battle_map_states_map foreign key (battle_map_id)
        references battle_maps (id) on delete cascade,
    constraint uk_account_battle_map_states_account_map unique (account_id, battle_map_id),
    constraint ck_account_battle_map_states_key_count check (key_count is null or key_count >= 0),
    constraint ck_account_battle_map_states_available_count check (available_count is null or available_count >= 0),
    constraint ck_account_battle_map_states_attempt_remaining check (attempt_remaining is null or attempt_remaining >= 0),
    constraint ck_account_battle_map_states_win_remaining check (win_remaining is null or win_remaining >= 0)
);

create index idx_account_battle_map_states_tree
    on account_battle_map_states (account_id, visible, battle_map_id);

create table unresolved_battle_maps (
    id bigserial,
    account_id bigint not null,
    category_id varchar(50) not null,
    group_name varchar(200),
    group_normalized_name varchar(200) not null,
    group_display_order integer not null,
    map_display_order integer not null,
    observed_name varchar(300) not null,
    normalized_name varchar(300) not null,
    recommended_level varchar(50),
    key_count integer,
    available_count integer,
    attempt_remaining integer,
    win_remaining integer,
    cooldown_until timestamp with time zone,
    required_time integer,
    icon_url text,
    raw_href text not null,
    visible boolean not null,
    last_seen_at timestamp with time zone not null,
    constraint pk_unresolved_battle_maps primary key (id),
    constraint fk_unresolved_battle_maps_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_unresolved_battle_maps_identity unique (
        account_id, category_id, group_normalized_name, normalized_name
    ),
    constraint ck_unresolved_battle_maps_group_order check (group_display_order >= 0),
    constraint ck_unresolved_battle_maps_map_order check (map_display_order >= 0),
    constraint ck_unresolved_battle_maps_key_count check (key_count is null or key_count >= 0),
    constraint ck_unresolved_battle_maps_available_count check (available_count is null or available_count >= 0),
    constraint ck_unresolved_battle_maps_attempt_remaining check (attempt_remaining is null or attempt_remaining >= 0),
    constraint ck_unresolved_battle_maps_win_remaining check (win_remaining is null or win_remaining >= 0),
    constraint ck_unresolved_battle_maps_required_time check (required_time is null or required_time >= 0)
);

create index idx_unresolved_battle_maps_tree
    on unresolved_battle_maps (
        account_id, category_id, visible, group_display_order, map_display_order, observed_name, id
    );

create table party_presets (
    id bigserial,
    account_id bigint not null,
    name varchar(255) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_party_presets primary key (id),
    constraint fk_party_presets_account foreign key (account_id)
        references hof_accounts (id) on delete cascade
);

create index idx_party_presets_account_updated
    on party_presets (account_id, updated_at, id);

create table party_preset_members (
    preset_id bigint not null,
    slot_index integer not null,
    character_id bigint,
    pattern_slot_id bigint,
    constraint pk_party_preset_members primary key (preset_id, slot_index),
    constraint uk_party_preset_members_preset_slot unique (preset_id, slot_index),
    constraint fk_party_preset_members_preset foreign key (preset_id)
        references party_presets (id) on delete cascade,
    constraint fk_party_preset_members_character foreign key (character_id)
        references characters (id) on delete set null,
    constraint fk_party_preset_members_pattern_slot foreign key (pattern_slot_id)
        references character_pattern_slots (id) on delete set null,
    constraint ck_party_preset_members_slot check (slot_index between 0 and 4)
);

create index idx_party_preset_members_character
    on party_preset_members (character_id);

create index idx_party_preset_members_pattern_slot
    on party_preset_members (pattern_slot_id);

create table automation_profiles (
    id bigserial,
    account_id bigint not null,
    name varchar(255) not null,
    mode varchar(255) not null,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_automation_profiles primary key (id),
    constraint fk_automation_profiles_account foreign key (account_id)
        references hof_accounts (id) on delete cascade
);

create index idx_automation_profiles_account_updated
    on automation_profiles (account_id, updated_at, id);

create table automation_profile_maps (
    id bigserial,
    profile_id bigint not null,
    battle_map_id bigint not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_automation_profile_maps primary key (id),
    constraint fk_automation_profile_maps_profile foreign key (profile_id)
        references automation_profiles (id) on delete cascade,
    constraint fk_automation_profile_maps_battle_map foreign key (battle_map_id)
        references battle_maps (id) on delete cascade,
    constraint fk_automation_profile_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_automation_profile_maps_profile_map unique (profile_id, battle_map_id),
    constraint ck_automation_profile_maps_execution_order check (execution_order >= 0)
);

create index idx_automation_profile_maps_profile_order
    on automation_profile_maps (profile_id, execution_order, battle_map_id, id);

create index idx_automation_profile_maps_party_preset
    on automation_profile_maps (party_preset_id);

create table automation_jobs (
    id bigserial,
    account_id bigint not null,
    profile_id bigint not null,
    status varchar(255) not null,
    current_step_index integer not null,
    message text,
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    constraint pk_automation_jobs primary key (id),
    constraint fk_automation_jobs_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint fk_automation_jobs_profile foreign key (profile_id)
        references automation_profiles (id),
    constraint ck_automation_jobs_current_step check (current_step_index >= 0)
);

create index idx_automation_jobs_account_active_updated
    on automation_jobs (account_id, status, updated_at, id);

create index idx_automation_jobs_profile
    on automation_jobs (profile_id, id);

create table battle_logs (
    id bigserial,
    account_id bigint not null,
    battle_map_id bigint,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    map_name_snapshot varchar(300) not null,
    outcome varchar(255) not null,
    title text not null,
    turns integer,
    funds integer,
    experience integer,
    quest text,
    enemy_hp_current integer,
    enemy_hp_max integer,
    enemy_survivors_alive integer,
    enemy_survivors_max integer,
    enemy_total_damage integer,
    enemy_turn_current integer,
    enemy_turn_max integer,
    ally_hp_current integer,
    ally_hp_max integer,
    ally_survivors_alive integer,
    ally_survivors_max integer,
    ally_total_damage integer,
    ally_turn_current integer,
    ally_turn_max integer,
    raw_log_url text,
    created_at timestamp with time zone not null,
    constraint pk_battle_logs primary key (id),
    constraint fk_battle_logs_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint fk_battle_logs_battle_map foreign key (battle_map_id)
        references battle_maps (id) on delete set null
);

create index idx_battle_logs_account_created
    on battle_logs (account_id, created_at, id);

create index idx_battle_logs_account_outcome
    on battle_logs (account_id, outcome, id);

create index idx_battle_logs_battle_map
    on battle_logs (battle_map_id, id);

create table battle_log_participants (
    id bigserial,
    battle_log_id bigint not null,
    slot_index integer not null,
    character_id bigint,
    hof_character_id_snapshot varchar(50) not null,
    character_name_snapshot varchar(100) not null,
    constraint pk_battle_log_participants primary key (id),
    constraint fk_battle_log_participants_log foreign key (battle_log_id)
        references battle_logs (id) on delete cascade,
    constraint fk_battle_log_participants_character foreign key (character_id)
        references characters (id) on delete set null,
    constraint uk_battle_log_participants_log_slot unique (battle_log_id, slot_index),
    constraint ck_battle_log_participants_slot check (slot_index >= 0)
);

create index idx_battle_log_participants_log_order
    on battle_log_participants (battle_log_id, slot_index, id);

create index idx_battle_log_participants_character
    on battle_log_participants (character_id, id);

create table battle_log_loots (
    id bigserial,
    battle_log_id bigint not null,
    display_order integer not null,
    name varchar(300) not null,
    quantity integer not null,
    raw_text text not null,
    constraint pk_battle_log_loots primary key (id),
    constraint fk_battle_log_loots_log foreign key (battle_log_id)
        references battle_logs (id) on delete cascade,
    constraint uk_battle_log_loots_log_display_order unique (battle_log_id, display_order),
    constraint ck_battle_log_loots_order check (display_order >= 0),
    constraint ck_battle_log_loots_quantity check (quantity > 0)
);

create index idx_battle_log_loots_log_order
    on battle_log_loots (battle_log_id, display_order, id);

create table captcha_challenges (
    id bigserial,
    account_id bigint not null,
    status varchar(255) not null,
    prompt text not null,
    image_url text,
    source_url text not null,
    answer text,
    created_at timestamp with time zone not null,
    answered_at timestamp with time zone,
    submit_url text,
    submit_method varchar(10) not null,
    answer_field_name varchar(100) not null,
    constraint pk_captcha_challenges primary key (id),
    constraint fk_captcha_challenges_account foreign key (account_id)
        references hof_accounts (id) on delete cascade
);

create index idx_captcha_challenges_account_status_created
    on captcha_challenges (account_id, status, created_at, id);

create table captcha_form_fields (
    id bigserial,
    challenge_id bigint not null,
    field_order integer not null,
    field_name varchar(255) not null,
    field_value text not null,
    constraint pk_captcha_form_fields primary key (id),
    constraint fk_captcha_form_fields_challenge foreign key (challenge_id)
        references captcha_challenges (id) on delete cascade,
    constraint uk_captcha_form_fields_challenge_name unique (challenge_id, field_name),
    constraint ck_captcha_form_fields_order check (field_order >= 0)
);

create index idx_captcha_form_fields_challenge_order
    on captcha_form_fields (challenge_id, field_order, id);

-- BEGIN GENERATED BATTLE MAP SEED
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '기초 허수아비', 0, null);
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '특수 허수아비', 1, null);
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '특수 채집 구역', 2, '??');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '고블린 부락', 3, '1-20');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '순환 퀘스트 - 칠요의 시련', 4, '60+');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '순환 퀘스트', 5, '30-50');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '고대의 지하유적', 6, '45-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', 'HOF 마을', 7, '??-??');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', 'HOF 마을 묘지', 8, '45-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', 'HOF 마을 지하 묘지', 9, '45-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', 'HOF 마을 지하', 10, '30-50');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '채집 구역', 11, '1');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '마법신의 탑-이차원', 12, '45-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '마법신의 탑-상위 구역', 13, '55-63');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '정글 심부', 14, '45-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '아눕 왕묘', 15, '30-55');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '완다이 산맥', 16, '20-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '지각 내부', 17, '55-63');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '귀족의 장원', 18, '30-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '귀족의 저택-동관', 19, '40-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '귀족의 저택-서관', 20, '40-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', 'G.S 교단 본부', 21, '50-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '천공성 이지 모드', 22, '50-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '천공성', 23, '55-63');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '대형', 24, null);
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '요일던전', 25, null);
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '지각 내부 (B', 26, null);
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('adventure_map', '특수채집', 27, null);
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '고블린 부락', 0, '1-20');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '고대의 동굴', 1, '15-45');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '고대의 지하미궁', 2, '20-45');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '서부 대사막', 3, '15-35');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '아눕 왕묘', 4, '30-55');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', 'HOF 마을 지하', 5, '30-50');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '대충산 등산로', 6, '1-25');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '대충산 위험지역', 7, '40-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '정글', 8, '20-45');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '정글 심부', 9, '45-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '마법신의 탑-현실계', 10, '20-50');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '마법신의 탑-이차원', 11, '45-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '완다이 산맥', 12, '20-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '죽음의 폐광', 13, '20-50');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '지각 내부', 14, '50-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '귀족의 장원', 15, '30-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '귀족의 저택-동관', 16, '40-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '귀족의 저택-서관', 17, '40-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', '귀족의 저택-지하 감옥', 18, '40-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', 'G.S 교단 본부', 19, '50-60');
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', 'G.S order Headquarters', 20, null);
insert into battle_map_groups (category_id, name, display_order, recommended_level) values ('battle_map', 'Noble''s Mansion- 저택 지하', 21, null);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul001', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 허수아비 Lv.1 (100 Turn)', 'simulation- 허수아비 lv.1 (100 turn)', 0, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul002', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 허수아비 Lv.1 (300 Turn)', 'simulation- 허수아비 lv.1 (300 turn)', 1, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul003', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 허수아비 파티 Lv.1 (100 Turn)', 'simulation- 허수아비 파티 lv.1 (100 turn)', 2, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul004', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 허수아비 파티 Lv.1 (300 Turn)', 'simulation- 허수아비 파티 lv.1 (300 turn)', 3, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul006', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 허수아비 Lv.60 (100 Turn)', 'simulation- 허수아비 lv.60 (100 turn)', 4, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul007', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 허수아비 Lv.60 (300 Turn)', 'simulation- 허수아비 lv.60 (300 turn)', 5, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul008', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 허수아비 파티 Lv.60 (100 Turn)', 'simulation- 허수아비 파티 lv.60 (100 turn)', 6, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul009', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 허수아비 파티 Lv.60 (300 Turn)', 'simulation- 허수아비 파티 lv.60 (300 turn)', 7, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul011', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 보스 허수아비 Lv.1 (100 Turn)', 'simulation- 보스 허수아비 lv.1 (100 turn)', 8, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul012', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 보스 허수아비 Lv.1 (300 Turn)', 'simulation- 보스 허수아비 lv.1 (300 turn)', 9, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul013', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 보스 허수아비 파티 Lv.1 (100 Turn)', 'simulation- 보스 허수아비 파티 lv.1 (100 turn)', 10, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul014', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 보스 허수아비 파티 Lv.1 (300 Turn)', 'simulation- 보스 허수아비 파티 lv.1 (300 turn)', 11, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul016', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 보스 허수아비 Lv.60 (100 Turn)', 'simulation- 보스 허수아비 lv.60 (100 turn)', 12, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul017', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 보스 허수아비 Lv.60 (300 Turn)', 'simulation- 보스 허수아비 lv.60 (300 turn)', 13, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul018', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 보스 허수아비 파티 Lv.60 (100 Turn)', 'simulation- 보스 허수아비 파티 lv.60 (100 turn)', 14, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul019', (select id from battle_map_groups where category_id = 'adventure_map' and name = '기초 허수아비'), 'Simulation- 보스 허수아비 파티 Lv.60 (300 Turn)', 'simulation- 보스 허수아비 파티 lv.60 (300 turn)', 15, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul101', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 공격 허수아비 Lv.10 (100 Turn)', 'simulation- 공격 허수아비 lv.10 (100 turn)', 0, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul102', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 공격 허수아비 Lv.60 (100 Turn)', 'simulation- 공격 허수아비 lv.60 (100 turn)', 1, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul103', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 공격 허수아비 Lv.80 (100 Turn)', 'simulation- 공격 허수아비 lv.80 (100 turn)', 2, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul104', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 전체 공격 허수아비 Lv.60 (100 Turn)', 'simulation- 전체 공격 허수아비 lv.60 (100 turn)', 3, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul105', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 전체 공격 허수아비 Lv.80 (100 Turn)', 'simulation- 전체 공격 허수아비 lv.80 (100 turn)', 4, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul106', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 공격 허수아비 파티 Lv.60 (200 Turn)', 'simulation- 공격 허수아비 파티 lv.60 (200 turn)', 5, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul107', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 공격 허수아비 파티 Lv.80 (200 Turn)', 'simulation- 공격 허수아비 파티 lv.80 (200 turn)', 6, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul111', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 마법 허수아비 Lv.10 (100 Turn)', 'simulation- 마법 허수아비 lv.10 (100 turn)', 7, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul112', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 마법 허수아비 Lv.60 (100 Turn)', 'simulation- 마법 허수아비 lv.60 (100 turn)', 8, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul113', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 마법 허수아비 Lv.80 (100 Turn)', 'simulation- 마법 허수아비 lv.80 (100 turn)', 9, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul114', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 전체 마법 허수아비 Lv.60 (100 Turn)', 'simulation- 전체 마법 허수아비 lv.60 (100 turn)', 10, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul115', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 전체 마법 허수아비 Lv.80 (100 Turn)', 'simulation- 전체 마법 허수아비 lv.80 (100 turn)', 11, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul116', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 마법 허수아비 파티 Lv.60 (200 Turn)', 'simulation- 마법 허수아비 파티 lv.60 (200 turn)', 12, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul117', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 마법 허수아비 파티 Lv.80 (200 Turn)', 'simulation- 마법 허수아비 파티 lv.80 (200 turn)', 13, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul118', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 시전 허수아비 -물리 Lv.60 (100 Turn)', 'simulation- 시전 허수아비 -물리 lv.60 (100 turn)', 14, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul119', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 시전 허수아비 -마법 Lv.60 (100 Turn)', 'simulation- 시전 허수아비 -마법 lv.60 (100 turn)', 15, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul120', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 시전 허수아비 -하이브리드 Lv.60 (100 Turn)', 'simulation- 시전 허수아비 -하이브리드 lv.60 (100 turn)', 16, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul201', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 조합 허수아비 파티 Lv.60 (200 Turn)', 'simulation- 조합 허수아비 파티 lv.60 (200 turn)', 17, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Simul203', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 허수아비'), 'Simulation- 조합 허수아비 파티 Lv.80 (200 Turn)', 'simulation- 조합 허수아비 파티 lv.80 (200 turn)', 18, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'HerbS01', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 채집 구역'), 'Wandai- 완다이 산맥(빅풋의 영역)', 'wandai- 완다이 산맥(빅풋의 영역)', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Fish01ex', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 채집 구역'), 'Collecting- 대해(재난 해역)', 'collecting- 대해(재난 해역)', 1, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sd2ex', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수 채집 구역'), 'Collecting- 숨겨진 사막 협곡', 'collecting- 숨겨진 사막 협곡', 2, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'gb7', (select id from battle_map_groups where category_id = 'adventure_map' and name = '고블린 부락'), 'Goblin- 고블린 콜로세움', 'goblin- 고블린 콜로세움', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAY1003R', (select id from battle_map_groups where category_id = 'adventure_map' and name = '순환 퀘스트 - 칠요의 시련'), 'Day Quest- 칠요의 시련(水) - 별 바다(Easy)', 'day quest- 칠요의 시련(水) - 별 바다(easy)', 0, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAY1003', (select id from battle_map_groups where category_id = 'adventure_map' and name = '순환 퀘스트 - 칠요의 시련'), 'Day Quest- 칠요의 시련(水) - 별 바다(Normal)', 'day quest- 칠요의 시련(水) - 별 바다(normal)', 1, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAYM0004', (select id from battle_map_groups where category_id = 'adventure_map' and name = '순환 퀘스트'), 'Day Quest- 성장의 신전 ~치유사의 시련~', 'day quest- 성장의 신전 ~치유사의 시련~', 0, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAYM0014', (select id from battle_map_groups where category_id = 'adventure_map' and name = '순환 퀘스트'), 'Day Quest- 용사의 신전 ~치유사의 시련~', 'day quest- 용사의 신전 ~치유사의 시련~', 1, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'acruin01', (select id from battle_map_groups where category_id = 'adventure_map' and name = '고대의 지하유적'), 'Ancient Ruins-고대의 지하유적 (B1)', 'ancient ruins-고대의 지하유적 (b1)', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'acruin03', (select id from battle_map_groups where category_id = 'adventure_map' and name = '고대의 지하유적'), 'Ancient Ruins-고대의 지하유적 (B3)', 'ancient ruins-고대의 지하유적 (b3)', 1, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'acruin02', (select id from battle_map_groups where category_id = 'adventure_map' and name = '고대의 지하유적'), '고대의 지하유적 (B2)', '고대의 지하유적 (b2)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'acruin04', (select id from battle_map_groups where category_id = 'adventure_map' and name = '고대의 지하유적'), '고대의 지하유적 (B4)', '고대의 지하유적 (b4)', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'acruin05', (select id from battle_map_groups where category_id = 'adventure_map' and name = '고대의 지하유적'), '고대의 지하유적 (B5) - 내부 성소', '고대의 지하유적 (b5) - 내부 성소', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival01', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회', 'arena- 천년제 무투회', 0, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival02', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 검과 방패의 자매', 'arena- 천년제 무투회 - 검과 방패의 자매', 1, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival03', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 혈족의 후예', 'arena- 천년제 무투회 - 혈족의 후예', 2, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival04', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 낫과 망치', 'arena- 천년제 무투회 - 낫과 망치', 3, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival05', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 모래와 바람의 비술', 'arena- 천년제 무투회 - 모래와 바람의 비술', 4, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival06', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 산중노인의 제자', 'arena- 천년제 무투회 - 산중노인의 제자', 5, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival07', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 신비의 세계', 'arena- 천년제 무투회 - 신비의 세계', 6, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival011', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회(HARD)', 'arena- 천년제 무투회(hard)', 7, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival022', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 검과 방패의 자매(HARD)', 'arena- 천년제 무투회 - 검과 방패의 자매(hard)', 8, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival033', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 혈족의 후예(HARD)', 'arena- 천년제 무투회 - 혈족의 후예(hard)', 9, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival044', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 낫과 망치(HARD)', 'arena- 천년제 무투회 - 낫과 망치(hard)', 10, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival055', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 모래와 바람의 비술(HARD)', 'arena- 천년제 무투회 - 모래와 바람의 비술(hard)', 11, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival066', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 산중노인의 제자(HARD)', 'arena- 천년제 무투회 - 산중노인의 제자(hard)', 12, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival077', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 신비의 세계(HARD)', 'arena- 천년제 무투회 - 신비의 세계(hard)', 13, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival08', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 무투회 결승', 'arena- 천년제 무투회 - 무투회 결승', 14, 30, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'festival09', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Arena- 천년제 무투회 - 무투회 결승 난입전', 'arena- 천년제 무투회 - 무투회 결승 난입전', 15, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'colo01', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을'), 'Balzac''s Invitation - 제 1투기장', 'balzac''s invitation - 제 1투기장', 16, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Grave001', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을 묘지'), 'Graveyard- 마을 묘지', 'graveyard- 마을 묘지', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Conc001', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을 지하 묘지'), 'Catacomb- 지하 묘소 - 열기가 느껴지는 묘소', 'catacomb- 지하 묘소 - 열기가 느껴지는 묘소', 0, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Conc002', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을 지하 묘지'), 'Catacomb- 지하 묘소 - 차갑게 얼어붙은 묘소', 'catacomb- 지하 묘소 - 차갑게 얼어붙은 묘소', 1, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Conc003', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을 지하 묘지'), 'Catacomb- 지하 묘소 - 헤메는 사령의 묘소', 'catacomb- 지하 묘소 - 헤메는 사령의 묘소', 2, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Conc004', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을 지하 묘지'), 'Catacomb- 지하 묘소 - 명암이 교차하는 묘소', 'catacomb- 지하 묘소 - 명암이 교차하는 묘소', 3, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Conc005', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을 지하 묘지'), 'Catacomb- 지하 묘소 - 그림자가 짙게 깔린 묘소', 'catacomb- 지하 묘소 - 그림자가 짙게 깔린 묘소', 4, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Conc006', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을 지하 묘지'), 'Catacomb- 지하 묘소 - 묘소 대회랑', 'catacomb- 지하 묘소 - 묘소 대회랑', 5, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'tnfh4', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'HOF 마을 지하'), 'Culvert- 마을 지하 수로(물 저장고)', 'culvert- 마을 지하 수로(물 저장고)', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Mine01', (select id from battle_map_groups where category_id = 'adventure_map' and name = '채집 구역'), 'Collecting- 뒷산의 광산', 'collecting- 뒷산의 광산', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Herb01', (select id from battle_map_groups where category_id = 'adventure_map' and name = '채집 구역'), 'Collecting- 뒷산의 숲', 'collecting- 뒷산의 숲', 1, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'mg06', (select id from battle_map_groups where category_id = 'adventure_map' and name = '마법신의 탑-이차원'), 'Tower of Magic- 마법신의 탑(상층(裏))', 'tower of magic- 마법신의 탑(상층(裏))', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'mg04', (select id from battle_map_groups where category_id = 'adventure_map' and name = '마법신의 탑-상위 구역'), 'Tower of Magic- 천체관', 'tower of magic- 천체관', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'mg044', (select id from battle_map_groups where category_id = 'adventure_map' and name = '마법신의 탑-상위 구역'), 'Tower of Magic- 천체관 심부', 'tower of magic- 천체관 심부', 1, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'for099', (select id from battle_map_groups where category_id = 'adventure_map' and name = '정글 심부'), 'Jungle- 오염된 숲', 'jungle- 오염된 숲', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Pyra55', (select id from battle_map_groups where category_id = 'adventure_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(F5) - 태양의 제단', 'pyramid- anub의 왕묘(f5) - 태양의 제단', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Pyra66', (select id from battle_map_groups where category_id = 'adventure_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(F5) - 호루스의 의식', 'pyramid- anub의 왕묘(f5) - 호루스의 의식', 1, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'mt05', (select id from battle_map_groups where category_id = 'adventure_map' and name = '완다이 산맥'), 'Wandai- 완다이 산맥(거대한 둥지)', 'wandai- 완다이 산맥(거대한 둥지)', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min08', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B4) Tuls의 문( x )', 'dead pit- 지각 내부 (b4) tuls의 문( x )', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min091', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B5) 흑요석 성채- 성채 정문', 'dead pit- 지각 내부 (b5) 흑요석 성채- 성채 정문', 1, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min10', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B6) 타오르는 세계', 'dead pit- 지각 내부 (b6) 타오르는 세계', 2, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min11', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B??) 별의 심장부', 'dead pit- 지각 내부 (b??) 별의 심장부', 3, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min12', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B??) 불의 바다', 'dead pit- 지각 내부 (b??) 불의 바다', 4, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Noble033', (select id from battle_map_groups where category_id = 'adventure_map' and name = '귀족의 장원'), 'Noble''s Manor- 귀족의 장원(문지기 호출)', 'noble''s manor- 귀족의 장원(문지기 호출)', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Noble103', (select id from battle_map_groups where category_id = 'adventure_map' and name = '귀족의 저택-동관'), 'Noble''s Mansion- 저택 동관(동관 안뜰)', 'noble''s mansion- 저택 동관(동관 안뜰)', 0, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Noble205', (select id from battle_map_groups where category_id = 'adventure_map' and name = '귀족의 저택-서관'), 'Noble''s Mansion- 저택 서관(놀이방)', 'noble''s mansion- 저택 서관(놀이방)', 0, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'GS06', (select id from battle_map_groups where category_id = 'adventure_map' and name = 'G.S 교단 본부'), 'G.S order Headquarters - 기원의 제단', 'g.s order headquarters - 기원의 제단', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion00e', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성 이지 모드'), 'Castle In The Sky- 천공성(순찰로)', 'castle in the sky- 천공성(순찰로)', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Rsion03', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성 이지 모드'), 'Castle In The Sky- 천공성(제 1탑) (EASY)', 'castle in the sky- 천공성(제 1탑) (easy)', 1, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Rsion04', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성 이지 모드'), 'Castle In The Sky- 천공성(제 2탑) (EASY)', 'castle in the sky- 천공성(제 2탑) (easy)', 2, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Rsion05', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성 이지 모드'), 'Castle In The Sky- 천공성(제 3탑) (EASY)', 'castle in the sky- 천공성(제 3탑) (easy)', 3, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Rsion06', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성 이지 모드'), 'Castle In The Sky- 천공성(제 4탑) (EASY)', 'castle in the sky- 천공성(제 4탑) (easy)', 4, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Rsion07', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성 이지 모드'), 'Castle In The Sky- 천공성(중앙 마력로) (EASY)', 'castle in the sky- 천공성(중앙 마력로) (easy)', 5, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion00', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성'), 'Castle In The Sky- 천공성(외곽)', 'castle in the sky- 천공성(외곽)', 0, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion01', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성'), 'Castle In The Sky- 천공성(내곽)', 'castle in the sky- 천공성(내곽)', 1, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion02', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성'), 'Castle In The Sky- 천공성(중앙 구역)', 'castle in the sky- 천공성(중앙 구역)', 2, 100, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion03', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성'), 'Castle In The Sky- 천공성(제 1탑)', 'castle in the sky- 천공성(제 1탑)', 3, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion04', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성'), 'Castle In The Sky- 천공성(제 2탑)', 'castle in the sky- 천공성(제 2탑)', 4, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion05', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성'), 'Castle In The Sky- 천공성(제 3탑)', 'castle in the sky- 천공성(제 3탑)', 5, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion06', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성'), 'Castle In The Sky- 천공성(제 4탑)', 'castle in the sky- 천공성(제 4탑)', 6, 50, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion07', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성'), 'Castle In The Sky- 천공성(중앙 마력로)', 'castle in the sky- 천공성(중앙 마력로)', 7, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'sion08', (select id from battle_map_groups where category_id = 'adventure_map' and name = '천공성'), 'Castle In The Sky- 천공성(중앙 탑)', 'castle in the sky- 천공성(중앙 탑)', 8, 0, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Noble022', (select id from battle_map_groups where category_id = 'adventure_map' and name = '대형'), 'Noble''s Manor- 북 에스타드 숲(마차 추적)', 'noble''s manor- 북 에스타드 숲(마차 추적)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'snow34', (select id from battle_map_groups where category_id = 'adventure_map' and name = '대형'), '대충산(백계)', '대충산(백계)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAY1001', (select id from battle_map_groups where category_id = 'adventure_map' and name = '요일던전'), '칠요의 시련(月) - 고요의 바다(Normal)', '칠요의 시련(月) - 고요의 바다(normal)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAY1001R', (select id from battle_map_groups where category_id = 'adventure_map' and name = '요일던전'), '칠요의 시련(月) - 고요의 바다(Easy)', '칠요의 시련(月) - 고요의 바다(easy)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAY1004R', (select id from battle_map_groups where category_id = 'adventure_map' and name = '요일던전'), '칠요의 시련(木) - 잊혀진 숲(Easy)', '칠요의 시련(木) - 잊혀진 숲(easy)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAY1005R', (select id from battle_map_groups where category_id = 'adventure_map' and name = '요일던전'), '금요', '금요', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAY1006R', (select id from battle_map_groups where category_id = 'adventure_map' and name = '요일던전'), '칠요의 시련(土) - 사령의 안개(Easy)', '칠요의 시련(土) - 사령의 안개(easy)', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min092', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부 (B'), '지각 내부 (B5) 흑요석 성채- 제1 저지선', '지각 내부 (b5) 흑요석 성채- 제1 저지선', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min093', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부 (B'), '지각 내부 (B5) 흑요석 성채- 소환의 방', '지각 내부 (b5) 흑요석 성채- 소환의 방', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min094', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부 (B'), '지각 내부 (B5) 흑요석 성채- 제2 저지선', '지각 내부 (b5) 흑요석 성채- 제2 저지선', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min095', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부 (B'), '지각 내부 (B5) 흑요석 성채- 최종 저지선', '지각 내부 (b5) 흑요석 성채- 최종 저지선', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'min096', (select id from battle_map_groups where category_id = 'adventure_map' and name = '지각 내부 (B'), '지각 내부 (B5) 흑요석 성채- 장군 호위대', '지각 내부 (b5) 흑요석 성채- 장군 호위대', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'MineS01', (select id from battle_map_groups where category_id = 'adventure_map' and name = '특수채집'), '흑요석 동굴 통제 구역', '흑요석 동굴 통제 구역', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAYM0001', null, '성장의 신전', '성장의 신전', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAYM0003', null, '성장의 신전 ~마법사의 시련~', '성장의 신전 ~마법사의 시련~', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAYM0005', null, '성장의 신전 ~레인저의 시련~', '성장의 신전 ~레인저의 시련~', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAYM0006', null, '성장의 신전 ~도화사의 시련~', '성장의 신전 ~도화사의 시련~', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAYM0012', null, '용사의 신전 ~전사의 시련~', '용사의 신전 ~전사의 시련~', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'DAYM0050', null, '조용한 도서관', '조용한 도서관', 5, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'Fish01', null, '용 낚시', '용 낚시', 6, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('adventure_map', 'colo02', null, 'Balzac''s Invitation - 제 2투기장', 'balzac''s invitation - 제 2투기장', 7, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'gb0', (select id from battle_map_groups where category_id = 'battle_map' and name = '고블린 부락'), 'Goblin- 고블린과 놀기(가장 약함)', 'goblin- 고블린과 놀기(가장 약함)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'gb1', (select id from battle_map_groups where category_id = 'battle_map' and name = '고블린 부락'), 'Goblin- 조금 강한 고블린', 'goblin- 조금 강한 고블린', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'gb2', (select id from battle_map_groups where category_id = 'battle_map' and name = '고블린 부락'), 'Goblin- 고블린의 전사들', 'goblin- 고블린의 전사들', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'gb4', (select id from battle_map_groups where category_id = 'battle_map' and name = '고블린 부락'), 'Goblin- 고귀한 고블린들', 'goblin- 고귀한 고블린들', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'gb5', (select id from battle_map_groups where category_id = 'battle_map' and name = '고블린 부락'), 'Goblin- 고블린 성채', 'goblin- 고블린 성채', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'gb6', (select id from battle_map_groups where category_id = 'battle_map' and name = '고블린 부락'), 'Goblin- 고블린의 왕', 'goblin- 고블린의 왕', 5, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ac0', (select id from battle_map_groups where category_id = 'battle_map' and name = '고대의 동굴'), 'Ancient Cave- 고대의 동굴', 'ancient cave- 고대의 동굴', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ac1', (select id from battle_map_groups where category_id = 'battle_map' and name = '고대의 동굴'), 'Ancient Cave- 고대의 동굴 (B2)', 'ancient cave- 고대의 동굴 (b2)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ac2', (select id from battle_map_groups where category_id = 'battle_map' and name = '고대의 동굴'), 'Ancient Cave- 고대의 동굴 (B3)', 'ancient cave- 고대의 동굴 (b3)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ac3', (select id from battle_map_groups where category_id = 'battle_map' and name = '고대의 동굴'), 'Ancient Cave- 고대의 동굴 (B4)', 'ancient cave- 고대의 동굴 (b4)', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ac4', (select id from battle_map_groups where category_id = 'battle_map' and name = '고대의 동굴'), 'Ancient Cave- 고대의 동굴 (B5)', 'ancient cave- 고대의 동굴 (b5)', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ac33', (select id from battle_map_groups where category_id = 'battle_map' and name = '고대의 지하미궁'), 'Ancient Maze- 고대의 지하미궁 (B1)', 'ancient maze- 고대의 지하미궁 (b1)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ac44', (select id from battle_map_groups where category_id = 'battle_map' and name = '고대의 지하미궁'), 'Ancient Maze-고대의 지하미궁 (B2)', 'ancient maze-고대의 지하미궁 (b2)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ac55', (select id from battle_map_groups where category_id = 'battle_map' and name = '고대의 지하미궁'), 'Ancient Maze-고대의 지하미궁 (B3)', 'ancient maze-고대의 지하미궁 (b3)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'sd1', (select id from battle_map_groups where category_id = 'battle_map' and name = '서부 대사막'), 'Desert- 고요의 사막', 'desert- 고요의 사막', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'sd2', (select id from battle_map_groups where category_id = 'battle_map' and name = '서부 대사막'), 'Desert- 신기루의 사막', 'desert- 신기루의 사막', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ant1', (select id from battle_map_groups where category_id = 'battle_map' and name = '서부 대사막'), 'Desert- 개미굴', 'desert- 개미굴', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'ant2', (select id from battle_map_groups where category_id = 'battle_map' and name = '서부 대사막'), 'Desert- 깊은 개미굴', 'desert- 깊은 개미굴', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Pyra1', (select id from battle_map_groups where category_id = 'battle_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘 입구', 'pyramid- anub의 왕묘 입구', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Pyra2', (select id from battle_map_groups where category_id = 'battle_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(B1)', 'pyramid- anub의 왕묘(b1)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Pyra3', (select id from battle_map_groups where category_id = 'battle_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(B2)', 'pyramid- anub의 왕묘(b2)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Pyra4', (select id from battle_map_groups where category_id = 'battle_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(B3)', 'pyramid- anub의 왕묘(b3)', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Pyra5', (select id from battle_map_groups where category_id = 'battle_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(B4) - 왕묘 내실', 'pyramid- anub의 왕묘(b4) - 왕묘 내실', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Pyra6', (select id from battle_map_groups where category_id = 'battle_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(B5) - 왕의 안식처', 'pyramid- anub의 왕묘(b5) - 왕의 안식처', 5, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Pyra22', (select id from battle_map_groups where category_id = 'battle_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(F2)', 'pyramid- anub의 왕묘(f2)', 6, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Pyra33', (select id from battle_map_groups where category_id = 'battle_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(F3)', 'pyramid- anub의 왕묘(f3)', 7, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Pyra44', (select id from battle_map_groups where category_id = 'battle_map' and name = '아눕 왕묘'), 'Pyramid- Anub의 왕묘(F4)', 'pyramid- anub의 왕묘(f4)', 8, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'tnfh1', (select id from battle_map_groups where category_id = 'battle_map' and name = 'HOF 마을 지하'), 'Culvert- 마을 지하 수로(입구)', 'culvert- 마을 지하 수로(입구)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'tnfh2', (select id from battle_map_groups where category_id = 'battle_map' and name = 'HOF 마을 지하'), 'Culvert- 마을 지하 수로(안쪽)', 'culvert- 마을 지하 수로(안쪽)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'tnfh22', (select id from battle_map_groups where category_id = 'battle_map' and name = 'HOF 마을 지하'), 'Culvert- 마을 지하 수로(쓰레기장)', 'culvert- 마을 지하 수로(쓰레기장)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'tnfh3', (select id from battle_map_groups where category_id = 'battle_map' and name = 'HOF 마을 지하'), 'Culvert- 마을 지하 수로(폐수 처리장)', 'culvert- 마을 지하 수로(폐수 처리장)', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow0', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 등산로'), 'Frosty Mountain- 대충산(기슭)', 'frosty mountain- 대충산(기슭)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow1', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 등산로'), 'Frosty Mountain- 대충산(산 중턱)', 'frosty mountain- 대충산(산 중턱)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow2', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 위험지역'), 'Frosty Mountain- 대충산(고원)', 'frosty mountain- 대충산(고원)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow3', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 위험지역'), 'Frosty Mountain- 대충산(만년설봉)', 'frosty mountain- 대충산(만년설봉)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow4', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 위험지역'), 'Frosty Mountain- 대충산(제2봉우리)', 'frosty mountain- 대충산(제2봉우리)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow21', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 위험지역'), 'Frosty Mountain- 대충산(얼음 동굴)', 'frosty mountain- 대충산(얼음 동굴)', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow22', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 위험지역'), 'Frosty Mountain- 대충산(마도사의 은신처)', 'frosty mountain- 대충산(마도사의 은신처)', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow23', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 위험지역'), 'Frosty Mountain- 대충산(발자두스의 연구실)', 'frosty mountain- 대충산(발자두스의 연구실)', 5, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow24', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 위험지역'), 'Frosty Mountain- 대충산(리치의 창고)', 'frosty mountain- 대충산(리치의 창고)', 6, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'snow33', (select id from battle_map_groups where category_id = 'battle_map' and name = '대충산 위험지역'), 'Frosty Mountain- 대충산(환상봉)', 'frosty mountain- 대충산(환상봉)', 7, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'for01', (select id from battle_map_groups where category_id = 'battle_map' and name = '정글'), 'Jungle- 밀림 입구', 'jungle- 밀림 입구', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'for02', (select id from battle_map_groups where category_id = 'battle_map' and name = '정글'), 'Jungle- 원시림', 'jungle- 원시림', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'for03', (select id from battle_map_groups where category_id = 'battle_map' and name = '정글'), 'Jungle- 야만인의 숲', 'jungle- 야만인의 숲', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'for04', (select id from battle_map_groups where category_id = 'battle_map' and name = '정글 심부'), 'Jungle- 야만인 부락 입구', 'jungle- 야만인 부락 입구', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'for05', (select id from battle_map_groups where category_id = 'battle_map' and name = '정글 심부'), 'Jungle- 야만인 부락', 'jungle- 야만인 부락', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'for06', (select id from battle_map_groups where category_id = 'battle_map' and name = '정글 심부'), 'Jungle- 검은 늪', 'jungle- 검은 늪', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mg01', (select id from battle_map_groups where category_id = 'battle_map' and name = '마법신의 탑-현실계'), 'Tower of Magic- 마법신의 탑(하층)', 'tower of magic- 마법신의 탑(하층)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mg02', (select id from battle_map_groups where category_id = 'battle_map' and name = '마법신의 탑-현실계'), 'Tower of Magic- 마법신의 탑(상층)', 'tower of magic- 마법신의 탑(상층)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mg03', (select id from battle_map_groups where category_id = 'battle_map' and name = '마법신의 탑-현실계'), 'Tower of Magic- 마법신의 탑 - 봉인 서고', 'tower of magic- 마법신의 탑 - 봉인 서고', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mg05', (select id from battle_map_groups where category_id = 'battle_map' and name = '마법신의 탑-이차원'), 'Tower of Magic- 마법신의 탑(하층(裏))', 'tower of magic- 마법신의 탑(하층(裏))', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mt01', (select id from battle_map_groups where category_id = 'battle_map' and name = '완다이 산맥'), 'Wandai- 완다이 산맥(숲길)', 'wandai- 완다이 산맥(숲길)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mt02', (select id from battle_map_groups where category_id = 'battle_map' and name = '완다이 산맥'), 'Wandai- 완다이 산맥(기슭)', 'wandai- 완다이 산맥(기슭)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mt03', (select id from battle_map_groups where category_id = 'battle_map' and name = '완다이 산맥'), 'Wandai- 완다이 산맥(산길)', 'wandai- 완다이 산맥(산길)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mt032', (select id from battle_map_groups where category_id = 'battle_map' and name = '완다이 산맥'), 'Wandai- 완다이 산맥(깊은 숲)', 'wandai- 완다이 산맥(깊은 숲)', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mt04', (select id from battle_map_groups where category_id = 'battle_map' and name = '완다이 산맥'), 'Wandai- 완다이 산맥(절벽길)', 'wandai- 완다이 산맥(절벽길)', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'mt042', (select id from battle_map_groups where category_id = 'battle_map' and name = '완다이 산맥'), 'Wandai- 완다이 산맥(오크의 숲)', 'wandai- 완다이 산맥(오크의 숲)', 5, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'min01', (select id from battle_map_groups where category_id = 'battle_map' and name = '죽음의 폐광'), 'Dead Pit- 죽음의 폐광', 'dead pit- 죽음의 폐광', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'min02', (select id from battle_map_groups where category_id = 'battle_map' and name = '죽음의 폐광'), 'Dead Pit- 죽음의 폐광 B1', 'dead pit- 죽음의 폐광 b1', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'min03', (select id from battle_map_groups where category_id = 'battle_map' and name = '죽음의 폐광'), 'Dead Pit- 죽음의 폐광 B2 - 불타는 광산', 'dead pit- 죽음의 폐광 b2 - 불타는 광산', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'min04', (select id from battle_map_groups where category_id = 'battle_map' and name = '죽음의 폐광'), 'Dead Pit- 흑요석 동굴', 'dead pit- 흑요석 동굴', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'min05', (select id from battle_map_groups where category_id = 'battle_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B1)', 'dead pit- 지각 내부 (b1)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'min06', (select id from battle_map_groups where category_id = 'battle_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B2)', 'dead pit- 지각 내부 (b2)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'min07', (select id from battle_map_groups where category_id = 'battle_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B3) 화염의 대장간', 'dead pit- 지각 내부 (b3) 화염의 대장간', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'min072', (select id from battle_map_groups where category_id = 'battle_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B3) 화룡굴', 'dead pit- 지각 내부 (b3) 화룡굴', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'min082', (select id from battle_map_groups where category_id = 'battle_map' and name = '지각 내부'), 'Dead Pit- 지각 내부 (B5) 증열의 평원', 'dead pit- 지각 내부 (b5) 증열의 평원', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble01', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 장원'), 'Noble''s Manor- 북 에스타드 숲', 'noble''s manor- 북 에스타드 숲', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble02', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 장원'), 'Noble''s Manor- 북 에스타드 숲(숲길)', 'noble''s manor- 북 에스타드 숲(숲길)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble0222', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 장원'), 'Noble''s Manor- 귀족의 장원(사냥 숲)', 'noble''s manor- 귀족의 장원(사냥 숲)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble03', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 장원'), 'Noble''s Manor- 귀족의 장원(저택 정문)', 'noble''s manor- 귀족의 장원(저택 정문)', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble04', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 장원'), 'Noble''s Manor- 귀족의 장원(저택 정원)', 'noble''s manor- 귀족의 장원(저택 정원)', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble102', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-동관'), 'Noble''s Mansion- 저택 동관(복도)', 'noble''s mansion- 저택 동관(복도)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble1021', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-동관'), 'Noble''s Mansion- 저택 동관(보쉬의 방)', 'noble''s mansion- 저택 동관(보쉬의 방)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble1022', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-동관'), 'Noble''s Mansion- 저택 동관(하인켈의 방)', 'noble''s mansion- 저택 동관(하인켈의 방)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble1023', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-동관'), 'Noble''s Mansion- 저택 동관(커티스의 방)', 'noble''s mansion- 저택 동관(커티스의 방)', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble1024', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-동관'), 'Noble''s Mansion- 저택 동관(장서고)', 'noble''s mansion- 저택 동관(장서고)', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble201', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-서관'), 'Noble''s Mansion- 저택 서관(복도)', 'noble''s mansion- 저택 서관(복도)', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble202', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-서관'), 'Noble''s Mansion- 저택 서관(인형사의 창고)', 'noble''s mansion- 저택 서관(인형사의 창고)', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble203', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-서관'), 'Noble''s Mansion- 저택 서관(해와 달의 방)', 'noble''s mansion- 저택 서관(해와 달의 방)', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble204', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-서관'), 'Noble''s Mansion- 저택 서관(인형사의 공방)', 'noble''s mansion- 저택 서관(인형사의 공방)', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble303', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-지하 감옥'), 'Noble''s Mansion- 지하 감옥(III) -제196 수감실', 'noble''s mansion- 지하 감옥(iii) -제196 수감실', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble304', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-지하 감옥'), 'Noble''s Mansion- 지하 감옥(IV) -제54 수감실', 'noble''s mansion- 지하 감옥(iv) -제54 수감실', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble305', (select id from battle_map_groups where category_id = 'battle_map' and name = '귀족의 저택-지하 감옥'), 'Noble''s Mansion- 지하 감옥(V) -고문실', 'noble''s mansion- 지하 감옥(v) -고문실', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'GS01', (select id from battle_map_groups where category_id = 'battle_map' and name = 'G.S 교단 본부'), 'G.S order Headquarters - 본부 입구', 'g.s order headquarters - 본부 입구', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'GS02', (select id from battle_map_groups where category_id = 'battle_map' and name = 'G.S 교단 본부'), 'G.S order Headquarters - 자애의 관문', 'g.s order headquarters - 자애의 관문', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'GS041', (select id from battle_map_groups where category_id = 'battle_map' and name = 'G.S 교단 본부'), 'G.S order Headquarters - 숭배하는 자의 방', 'g.s order headquarters - 숭배하는 자의 방', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'GS042', (select id from battle_map_groups where category_id = 'battle_map' and name = 'G.S 교단 본부'), 'G.S order Headquarters - 고행하는 자의 방', 'g.s order headquarters - 고행하는 자의 방', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'GS043', (select id from battle_map_groups where category_id = 'battle_map' and name = 'G.S 교단 본부'), 'G.S order Headquarters - 전도하는 자의 방', 'g.s order headquarters - 전도하는 자의 방', 4, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'GS03', (select id from battle_map_groups where category_id = 'battle_map' and name = 'G.S order Headquarters'), 'G.S order Headquarters - 슬픔의 관문', 'g.s order headquarters - 슬픔의 관문', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'GS04', (select id from battle_map_groups where category_id = 'battle_map' and name = 'G.S order Headquarters'), 'G.S order Headquarters - 사도 천사의 관문', 'g.s order headquarters - 사도 천사의 관문', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'GS05', (select id from battle_map_groups where category_id = 'battle_map' and name = 'G.S order Headquarters'), 'G.S order Headquarters - 삼위일체의 회랑', 'g.s order headquarters - 삼위일체의 회랑', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Noble301', (select id from battle_map_groups where category_id = 'battle_map' and name = 'Noble''s Mansion- 저택 지하'), 'Noble''s Mansion- 지하 감옥(I) -제14 수감실', 'noble''s mansion- 지하 감옥(i) -제14 수감실', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Fish02', null, '피라냐', '피라냐', 0, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Fish03', null, '악어', '악어', 1, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Fish04', null, '전기 뱀장어', '전기 뱀장어', 2, null, null, true, current_timestamp, current_timestamp);
insert into battle_maps (category_id, map_code, group_id, name, normalized_name, display_order, required_time, icon_url, enabled, created_at, updated_at) values ('battle_map', 'Sink04', null, '선실 내부', '선실 내부', 3, null, null, true, current_timestamp, current_timestamp);
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul001'), 'Simulation- 허수아비 Lv.1 (100 Turn)', 'simulation- 허수아비 lv.1 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul001'), '허수아비 Lv.1 (100 Turn)', '허수아비 lv.1 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul002'), 'Simulation- 허수아비 Lv.1 (300 Turn)', 'simulation- 허수아비 lv.1 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul002'), '허수아비 Lv.1 (300 Turn)', '허수아비 lv.1 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul003'), 'Simulation- 허수아비 파티 Lv.1 (100 Turn)', 'simulation- 허수아비 파티 lv.1 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul003'), '허수아비 파티 Lv.1 (100 Turn)', '허수아비 파티 lv.1 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul004'), 'Simulation- 허수아비 파티 Lv.1 (300 Turn)', 'simulation- 허수아비 파티 lv.1 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul004'), '허수아비 파티 Lv.1 (300 Turn)', '허수아비 파티 lv.1 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul006'), 'Simulation- 허수아비 Lv.60 (100 Turn)', 'simulation- 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul006'), '허수아비 Lv.60 (100 Turn)', '허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul007'), 'Simulation- 허수아비 Lv.60 (300 Turn)', 'simulation- 허수아비 lv.60 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul007'), '허수아비 Lv.60 (300 Turn)', '허수아비 lv.60 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul008'), 'Simulation- 허수아비 파티 Lv.60 (100 Turn)', 'simulation- 허수아비 파티 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul008'), '허수아비 파티 Lv.60 (100 Turn)', '허수아비 파티 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul009'), 'Simulation- 허수아비 파티 Lv.60 (300 Turn)', 'simulation- 허수아비 파티 lv.60 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul009'), '허수아비 파티 Lv.60 (300 Turn)', '허수아비 파티 lv.60 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul011'), 'Simulation- 보스 허수아비 Lv.1 (100 Turn)', 'simulation- 보스 허수아비 lv.1 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul011'), '보스 허수아비 Lv.1 (100 Turn)', '보스 허수아비 lv.1 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul012'), 'Simulation- 보스 허수아비 Lv.1 (300 Turn)', 'simulation- 보스 허수아비 lv.1 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul012'), '보스 허수아비 Lv.1 (300 Turn)', '보스 허수아비 lv.1 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul013'), 'Simulation- 보스 허수아비 파티 Lv.1 (100 Turn)', 'simulation- 보스 허수아비 파티 lv.1 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul013'), '보스 허수아비 파티 Lv.1 (100 Turn)', '보스 허수아비 파티 lv.1 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul014'), 'Simulation- 보스 허수아비 파티 Lv.1 (300 Turn)', 'simulation- 보스 허수아비 파티 lv.1 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul014'), '보스 허수아비 파티 Lv.1 (300 Turn)', '보스 허수아비 파티 lv.1 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul016'), 'Simulation- 보스 허수아비 Lv.60 (100 Turn)', 'simulation- 보스 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul016'), '보스 허수아비 Lv.60 (100 Turn)', '보스 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul017'), 'Simulation- 보스 허수아비 Lv.60 (300 Turn)', 'simulation- 보스 허수아비 lv.60 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul017'), '보스 허수아비 Lv.60 (300 Turn)', '보스 허수아비 lv.60 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul018'), 'Simulation- 보스 허수아비 파티 Lv.60 (100 Turn)', 'simulation- 보스 허수아비 파티 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul018'), '보스 허수아비 파티 Lv.60 (100 Turn)', '보스 허수아비 파티 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul019'), 'Simulation- 보스 허수아비 파티 Lv.60 (300 Turn)', 'simulation- 보스 허수아비 파티 lv.60 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul019'), '보스 허수아비 파티 Lv.60 (300 Turn)', '보스 허수아비 파티 lv.60 (300 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul101'), 'Simulation- 공격 허수아비 Lv.10 (100 Turn)', 'simulation- 공격 허수아비 lv.10 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul101'), '공격 허수아비 Lv.10 (100 Turn)', '공격 허수아비 lv.10 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul102'), 'Simulation- 공격 허수아비 Lv.60 (100 Turn)', 'simulation- 공격 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul102'), '공격 허수아비 Lv.60 (100 Turn)', '공격 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul103'), 'Simulation- 공격 허수아비 Lv.80 (100 Turn)', 'simulation- 공격 허수아비 lv.80 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul103'), '공격 허수아비 Lv.80 (100 Turn)', '공격 허수아비 lv.80 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul104'), 'Simulation- 전체 공격 허수아비 Lv.60 (100 Turn)', 'simulation- 전체 공격 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul104'), '전체 공격 허수아비 Lv.60 (100 Turn)', '전체 공격 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul105'), 'Simulation- 전체 공격 허수아비 Lv.80 (100 Turn)', 'simulation- 전체 공격 허수아비 lv.80 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul105'), '전체 공격 허수아비 Lv.80 (100 Turn)', '전체 공격 허수아비 lv.80 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul106'), 'Simulation- 공격 허수아비 파티 Lv.60 (200 Turn)', 'simulation- 공격 허수아비 파티 lv.60 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul106'), '공격 허수아비 파티 Lv.60 (200 Turn)', '공격 허수아비 파티 lv.60 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul107'), 'Simulation- 공격 허수아비 파티 Lv.80 (200 Turn)', 'simulation- 공격 허수아비 파티 lv.80 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul107'), '공격 허수아비 파티 Lv.80 (200 Turn)', '공격 허수아비 파티 lv.80 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul111'), 'Simulation- 마법 허수아비 Lv.10 (100 Turn)', 'simulation- 마법 허수아비 lv.10 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul111'), '마법 허수아비 Lv.10 (100 Turn)', '마법 허수아비 lv.10 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul112'), 'Simulation- 마법 허수아비 Lv.60 (100 Turn)', 'simulation- 마법 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul112'), '마법 허수아비 Lv.60 (100 Turn)', '마법 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul113'), 'Simulation- 마법 허수아비 Lv.80 (100 Turn)', 'simulation- 마법 허수아비 lv.80 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul113'), '마법 허수아비 Lv.80 (100 Turn)', '마법 허수아비 lv.80 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul114'), 'Simulation- 전체 마법 허수아비 Lv.60 (100 Turn)', 'simulation- 전체 마법 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul114'), '전체 마법 허수아비 Lv.60 (100 Turn)', '전체 마법 허수아비 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul115'), 'Simulation- 전체 마법 허수아비 Lv.80 (100 Turn)', 'simulation- 전체 마법 허수아비 lv.80 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul115'), '전체 마법 허수아비 Lv.80 (100 Turn)', '전체 마법 허수아비 lv.80 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul116'), 'Simulation- 마법 허수아비 파티 Lv.60 (200 Turn)', 'simulation- 마법 허수아비 파티 lv.60 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul116'), '마법 허수아비 파티 Lv.60 (200 Turn)', '마법 허수아비 파티 lv.60 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul117'), 'Simulation- 마법 허수아비 파티 Lv.80 (200 Turn)', 'simulation- 마법 허수아비 파티 lv.80 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul117'), '마법 허수아비 파티 Lv.80 (200 Turn)', '마법 허수아비 파티 lv.80 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul118'), 'Simulation- 시전 허수아비 -물리 Lv.60 (100 Turn)', 'simulation- 시전 허수아비 -물리 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul118'), '물리 Lv.60 (100 Turn)', '물리 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul118'), '시전 허수아비 -물리 Lv.60 (100 Turn)', '시전 허수아비 -물리 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul119'), 'Simulation- 시전 허수아비 -마법 Lv.60 (100 Turn)', 'simulation- 시전 허수아비 -마법 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul119'), '마법 Lv.60 (100 Turn)', '마법 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul119'), '시전 허수아비 -마법 Lv.60 (100 Turn)', '시전 허수아비 -마법 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul120'), 'Simulation- 시전 허수아비 -하이브리드 Lv.60 (100 Turn)', 'simulation- 시전 허수아비 -하이브리드 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul120'), '시전 허수아비 -하이브리드 Lv.60 (100 Turn)', '시전 허수아비 -하이브리드 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul120'), '하이브리드 Lv.60 (100 Turn)', '하이브리드 lv.60 (100 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul201'), 'Simulation- 조합 허수아비 파티 Lv.60 (200 Turn)', 'simulation- 조합 허수아비 파티 lv.60 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul201'), '조합 허수아비 파티 Lv.60 (200 Turn)', '조합 허수아비 파티 lv.60 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul203'), 'Simulation- 조합 허수아비 파티 Lv.80 (200 Turn)', 'simulation- 조합 허수아비 파티 lv.80 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Simul203'), '조합 허수아비 파티 Lv.80 (200 Turn)', '조합 허수아비 파티 lv.80 (200 turn)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'HerbS01'), 'Wandai- 완다이 산맥(빅풋의 영역)', 'wandai- 완다이 산맥(빅풋의 영역)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'HerbS01'), '완다이 산맥(빅풋의 영역)', '완다이 산맥(빅풋의 영역)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Fish01ex'), 'Collecting- 대해(재난 해역)', 'collecting- 대해(재난 해역)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Fish01ex'), '대해(재난 해역)', '대해(재난 해역)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sd2ex'), 'Collecting- 숨겨진 사막 협곡', 'collecting- 숨겨진 사막 협곡');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sd2ex'), '숨겨진 사막 협곡', '숨겨진 사막 협곡');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'gb7'), 'Goblin- 고블린 콜로세움', 'goblin- 고블린 콜로세움');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'gb7'), '고블린 콜로세움', '고블린 콜로세움');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1003R'), 'Day Quest- 칠요의 시련(水) - 별 바다(Easy)', 'day quest- 칠요의 시련(水) - 별 바다(easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1003R'), '별 바다(Easy)', '별 바다(easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1003R'), '칠요의 시련(水) - 별 바다(Easy)', '칠요의 시련(水) - 별 바다(easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1003'), 'Day Quest- 칠요의 시련(水) - 별 바다(Normal)', 'day quest- 칠요의 시련(水) - 별 바다(normal)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1003'), '별 바다(Normal)', '별 바다(normal)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1003'), '칠요의 시련(水) - 별 바다(Normal)', '칠요의 시련(水) - 별 바다(normal)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0004'), 'Day Quest- 성장의 신전 ~치유사의 시련~', 'day quest- 성장의 신전 ~치유사의 시련~');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0004'), '성장의 신전 ~치유사의 시련~', '성장의 신전 ~치유사의 시련~');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0014'), 'Day Quest- 용사의 신전 ~치유사의 시련~', 'day quest- 용사의 신전 ~치유사의 시련~');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0014'), '용사의 신전 ~치유사의 시련~', '용사의 신전 ~치유사의 시련~');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'acruin01'), 'Ancient Ruins-고대의 지하유적 (B1)', 'ancient ruins-고대의 지하유적 (b1)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'acruin01'), '고대의 지하유적 (B1)', '고대의 지하유적 (b1)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'acruin03'), 'Ancient Ruins-고대의 지하유적 (B3)', 'ancient ruins-고대의 지하유적 (b3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'acruin03'), '고대의 지하유적 (B3)', '고대의 지하유적 (b3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'acruin02'), '고대의 지하유적 (B2)', '고대의 지하유적 (b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'acruin04'), '고대의 지하유적 (B4)', '고대의 지하유적 (b4)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'acruin05'), '고대의 지하유적 (B5) - 내부 성소', '고대의 지하유적 (b5) - 내부 성소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'acruin05'), '내부 성소', '내부 성소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival01'), 'Arena- 천년제 무투회', 'arena- 천년제 무투회');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival01'), '천년제 무투회', '천년제 무투회');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival02'), 'Arena- 천년제 무투회 - 검과 방패의 자매', 'arena- 천년제 무투회 - 검과 방패의 자매');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival02'), '검과 방패의 자매', '검과 방패의 자매');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival02'), '천년제 무투회 - 검과 방패의 자매', '천년제 무투회 - 검과 방패의 자매');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival03'), 'Arena- 천년제 무투회 - 혈족의 후예', 'arena- 천년제 무투회 - 혈족의 후예');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival03'), '천년제 무투회 - 혈족의 후예', '천년제 무투회 - 혈족의 후예');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival03'), '혈족의 후예', '혈족의 후예');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival04'), 'Arena- 천년제 무투회 - 낫과 망치', 'arena- 천년제 무투회 - 낫과 망치');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival04'), '낫과 망치', '낫과 망치');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival04'), '천년제 무투회 - 낫과 망치', '천년제 무투회 - 낫과 망치');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival05'), 'Arena- 천년제 무투회 - 모래와 바람의 비술', 'arena- 천년제 무투회 - 모래와 바람의 비술');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival05'), '모래와 바람의 비술', '모래와 바람의 비술');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival05'), '천년제 무투회 - 모래와 바람의 비술', '천년제 무투회 - 모래와 바람의 비술');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival06'), 'Arena- 천년제 무투회 - 산중노인의 제자', 'arena- 천년제 무투회 - 산중노인의 제자');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival06'), '산중노인의 제자', '산중노인의 제자');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival06'), '천년제 무투회 - 산중노인의 제자', '천년제 무투회 - 산중노인의 제자');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival07'), 'Arena- 천년제 무투회 - 신비의 세계', 'arena- 천년제 무투회 - 신비의 세계');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival07'), '신비의 세계', '신비의 세계');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival07'), '천년제 무투회 - 신비의 세계', '천년제 무투회 - 신비의 세계');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival011'), 'Arena- 천년제 무투회(HARD)', 'arena- 천년제 무투회(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival011'), '천년제 무투회(HARD)', '천년제 무투회(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival022'), 'Arena- 천년제 무투회 - 검과 방패의 자매(HARD)', 'arena- 천년제 무투회 - 검과 방패의 자매(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival022'), '검과 방패의 자매(HARD)', '검과 방패의 자매(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival022'), '천년제 무투회 - 검과 방패의 자매(HARD)', '천년제 무투회 - 검과 방패의 자매(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival033'), 'Arena- 천년제 무투회 - 혈족의 후예(HARD)', 'arena- 천년제 무투회 - 혈족의 후예(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival033'), '천년제 무투회 - 혈족의 후예(HARD)', '천년제 무투회 - 혈족의 후예(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival033'), '혈족의 후예(HARD)', '혈족의 후예(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival044'), 'Arena- 천년제 무투회 - 낫과 망치(HARD)', 'arena- 천년제 무투회 - 낫과 망치(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival044'), '낫과 망치(HARD)', '낫과 망치(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival044'), '천년제 무투회 - 낫과 망치(HARD)', '천년제 무투회 - 낫과 망치(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival055'), 'Arena- 천년제 무투회 - 모래와 바람의 비술(HARD)', 'arena- 천년제 무투회 - 모래와 바람의 비술(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival055'), '모래와 바람의 비술(HARD)', '모래와 바람의 비술(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival055'), '천년제 무투회 - 모래와 바람의 비술(HARD)', '천년제 무투회 - 모래와 바람의 비술(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival066'), 'Arena- 천년제 무투회 - 산중노인의 제자(HARD)', 'arena- 천년제 무투회 - 산중노인의 제자(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival066'), '산중노인의 제자(HARD)', '산중노인의 제자(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival066'), '천년제 무투회 - 산중노인의 제자(HARD)', '천년제 무투회 - 산중노인의 제자(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival077'), 'Arena- 천년제 무투회 - 신비의 세계(HARD)', 'arena- 천년제 무투회 - 신비의 세계(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival077'), '신비의 세계(HARD)', '신비의 세계(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival077'), '천년제 무투회 - 신비의 세계(HARD)', '천년제 무투회 - 신비의 세계(hard)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival08'), 'Arena- 천년제 무투회 - 무투회 결승', 'arena- 천년제 무투회 - 무투회 결승');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival08'), '무투회 결승', '무투회 결승');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival08'), '천년제 무투회 - 무투회 결승', '천년제 무투회 - 무투회 결승');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival09'), 'Arena- 천년제 무투회 - 무투회 결승 난입전', 'arena- 천년제 무투회 - 무투회 결승 난입전');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival09'), '무투회 결승 난입전', '무투회 결승 난입전');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'festival09'), '천년제 무투회 - 무투회 결승 난입전', '천년제 무투회 - 무투회 결승 난입전');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'colo01'), 'Balzac''s Invitation - 제 1투기장', 'balzac''s invitation - 제 1투기장');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'colo01'), '제 1투기장', '제 1투기장');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Grave001'), 'Graveyard- 마을 묘지', 'graveyard- 마을 묘지');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Grave001'), '마을 묘지', '마을 묘지');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc001'), 'Catacomb- 지하 묘소 - 열기가 느껴지는 묘소', 'catacomb- 지하 묘소 - 열기가 느껴지는 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc001'), '열기', '열기');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc001'), '열기가 느껴지는 묘소', '열기가 느껴지는 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc001'), '지하 묘소 - 열기가 느껴지는 묘소', '지하 묘소 - 열기가 느껴지는 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc002'), 'Catacomb- 지하 묘소 - 차갑게 얼어붙은 묘소', 'catacomb- 지하 묘소 - 차갑게 얼어붙은 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc002'), '냉기', '냉기');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc002'), '지하 묘소 - 차갑게 얼어붙은 묘소', '지하 묘소 - 차갑게 얼어붙은 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc002'), '차갑게 얼어붙은 묘소', '차갑게 얼어붙은 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc003'), 'Catacomb- 지하 묘소 - 헤메는 사령의 묘소', 'catacomb- 지하 묘소 - 헤메는 사령의 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc003'), '지하 묘소 - 헤메는 사령의 묘소', '지하 묘소 - 헤메는 사령의 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc003'), '헤메', '헤메');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc003'), '헤메는 사령의 묘소', '헤메는 사령의 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc004'), 'Catacomb- 지하 묘소 - 명암이 교차하는 묘소', 'catacomb- 지하 묘소 - 명암이 교차하는 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc004'), '명암이 교차하는 묘소', '명암이 교차하는 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc004'), '어둠', '어둠');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc004'), '지하 묘소 - 명암이 교차하는 묘소', '지하 묘소 - 명암이 교차하는 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc005'), 'Catacomb- 지하 묘소 - 그림자가 짙게 깔린 묘소', 'catacomb- 지하 묘소 - 그림자가 짙게 깔린 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc005'), '그림자', '그림자');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc005'), '그림자가 짙게 깔린 묘소', '그림자가 짙게 깔린 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc005'), '지하 묘소 - 그림자가 짙게 깔린 묘소', '지하 묘소 - 그림자가 짙게 깔린 묘소');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc006'), 'Catacomb- 지하 묘소 - 묘소 대회랑', 'catacomb- 지하 묘소 - 묘소 대회랑');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc006'), '묘소 대회랑', '묘소 대회랑');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Conc006'), '지하 묘소 - 묘소 대회랑', '지하 묘소 - 묘소 대회랑');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'tnfh4'), 'Culvert- 마을 지하 수로(물 저장고)', 'culvert- 마을 지하 수로(물 저장고)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'tnfh4'), '마을 지하 수로(물 저장고)', '마을 지하 수로(물 저장고)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Mine01'), 'Collecting- 뒷산의 광산', 'collecting- 뒷산의 광산');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Mine01'), '뒷산의 광산', '뒷산의 광산');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Herb01'), 'Collecting- 뒷산의 숲', 'collecting- 뒷산의 숲');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Herb01'), '뒷산의 숲', '뒷산의 숲');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'mg06'), 'Tower of Magic- 마법신의 탑(상층(裏))', 'tower of magic- 마법신의 탑(상층(裏))');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'mg06'), '마법신의 탑(상층(裏))', '마법신의 탑(상층(裏))');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'mg04'), 'Tower of Magic- 천체관', 'tower of magic- 천체관');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'mg04'), '천체관', '천체관');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'mg044'), 'Tower of Magic- 천체관 심부', 'tower of magic- 천체관 심부');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'mg044'), '천체관 심부', '천체관 심부');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'for099'), 'Jungle- 오염된 숲', 'jungle- 오염된 숲');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'for099'), '오염된 숲', '오염된 숲');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Pyra55'), 'Anub의 왕묘(F5) - 태양의 제단', 'anub의 왕묘(f5) - 태양의 제단');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Pyra55'), 'Pyramid- Anub의 왕묘(F5) - 태양의 제단', 'pyramid- anub의 왕묘(f5) - 태양의 제단');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Pyra55'), '의 왕묘(F5) - 태양의 제단', '의 왕묘(f5) - 태양의 제단');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Pyra55'), '태양의 제단', '태양의 제단');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Pyra66'), 'Anub의 왕묘(F5) - 호루스의 의식', 'anub의 왕묘(f5) - 호루스의 의식');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Pyra66'), 'Pyramid- Anub의 왕묘(F5) - 호루스의 의식', 'pyramid- anub의 왕묘(f5) - 호루스의 의식');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Pyra66'), '의 왕묘(F5) - 호루스의 의식', '의 왕묘(f5) - 호루스의 의식');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Pyra66'), '호루스의 의식', '호루스의 의식');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'mt05'), 'Wandai- 완다이 산맥(거대한 둥지)', 'wandai- 완다이 산맥(거대한 둥지)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'mt05'), '완다이 산맥(거대한 둥지)', '완다이 산맥(거대한 둥지)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min08'), 'Dead Pit- 지각 내부 (B4) Tuls의 문( x )', 'dead pit- 지각 내부 (b4) tuls의 문( x )');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min08'), '지각 내부 (B4) Tuls의 문', '지각 내부 (b4) tuls의 문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min08'), '지각 내부 (B4) Tuls의 문( x )', '지각 내부 (b4) tuls의 문( x )');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min091'), 'Dead Pit- 지각 내부 (B5) 흑요석 성채- 성채 정문', 'dead pit- 지각 내부 (b5) 흑요석 성채- 성채 정문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min091'), '성채 정문', '성채 정문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min091'), '지각 내부 (B5) 흑요석 성채- 성채 정문', '지각 내부 (b5) 흑요석 성채- 성채 정문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min10'), 'Dead Pit- 지각 내부 (B6) 타오르는 세계', 'dead pit- 지각 내부 (b6) 타오르는 세계');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min10'), '지각 내부 (B6) 타오르는 세계', '지각 내부 (b6) 타오르는 세계');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min11'), 'Dead Pit- 지각 내부 (B??) 별의 심장부', 'dead pit- 지각 내부 (b??) 별의 심장부');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min11'), '지각 내부 (B??) 별의 심장부', '지각 내부 (b??) 별의 심장부');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min12'), 'Dead Pit- 지각 내부 (B??) 불의 바다', 'dead pit- 지각 내부 (b??) 불의 바다');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min12'), '지각 내부 (B??) 불의 바다', '지각 내부 (b??) 불의 바다');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Noble033'), 'Noble''s Manor- 귀족의 장원(문지기 호출)', 'noble''s manor- 귀족의 장원(문지기 호출)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Noble033'), '귀족의 장원(문지기 호출)', '귀족의 장원(문지기 호출)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Noble103'), 'Noble''s Mansion- 저택 동관(동관 안뜰)', 'noble''s mansion- 저택 동관(동관 안뜰)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Noble103'), '저택 동관(동관 안뜰)', '저택 동관(동관 안뜰)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Noble205'), 'Noble''s Mansion- 저택 서관(놀이방)', 'noble''s mansion- 저택 서관(놀이방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Noble205'), '저택 서관(놀이방)', '저택 서관(놀이방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'GS06'), 'G.S order Headquarters - 기원의 제단', 'g.s order headquarters - 기원의 제단');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'GS06'), '기원의 제단', '기원의 제단');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion00e'), 'Castle In The Sky- 천공성(순찰로)', 'castle in the sky- 천공성(순찰로)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion00e'), '천공성(순찰로)', '천공성(순찰로)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion03'), 'Castle In The Sky- 천공성(제 1탑) (EASY)', 'castle in the sky- 천공성(제 1탑) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion03'), '천공성(제 1탑) (EASY)', '천공성(제 1탑) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion04'), 'Castle In The Sky- 천공성(제 2탑) (EASY)', 'castle in the sky- 천공성(제 2탑) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion04'), '천공성(제 2탑) (EASY)', '천공성(제 2탑) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion05'), 'Castle In The Sky- 천공성(제 3탑) (EASY)', 'castle in the sky- 천공성(제 3탑) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion05'), '천공성(제 3탑) (EASY)', '천공성(제 3탑) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion06'), 'Castle In The Sky- 천공성(제 4탑) (EASY)', 'castle in the sky- 천공성(제 4탑) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion06'), '천공성(제 4탑) (EASY)', '천공성(제 4탑) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion07'), 'Castle In The Sky- 천공성(중앙 마력로) (EASY)', 'castle in the sky- 천공성(중앙 마력로) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Rsion07'), '천공성(중앙 마력로) (EASY)', '천공성(중앙 마력로) (easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion00'), 'Castle In The Sky- 천공성(외곽)', 'castle in the sky- 천공성(외곽)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion00'), '천공성(외곽)', '천공성(외곽)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion01'), 'Castle In The Sky- 천공성(내곽)', 'castle in the sky- 천공성(내곽)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion01'), '천공성(내곽)', '천공성(내곽)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion02'), 'Castle In The Sky- 천공성(중앙 구역)', 'castle in the sky- 천공성(중앙 구역)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion02'), '천공성(중앙 구역)', '천공성(중앙 구역)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion03'), 'Castle In The Sky- 천공성(제 1탑)', 'castle in the sky- 천공성(제 1탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion03'), '천공성(제 1탑)', '천공성(제 1탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion04'), 'Castle In The Sky- 천공성(제 2탑)', 'castle in the sky- 천공성(제 2탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion04'), '천공성(제 2탑)', '천공성(제 2탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion05'), 'Castle In The Sky- 천공성(제 3탑)', 'castle in the sky- 천공성(제 3탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion05'), '천공성(제 3탑)', '천공성(제 3탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion06'), 'Castle In The Sky- 천공성(제 4탑)', 'castle in the sky- 천공성(제 4탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion06'), '천공성(제 4탑)', '천공성(제 4탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion07'), 'Castle In The Sky- 천공성(중앙 마력로)', 'castle in the sky- 천공성(중앙 마력로)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion07'), '천공성(중앙 마력로)', '천공성(중앙 마력로)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion08'), 'Castle In The Sky- 천공성(중앙 탑)', 'castle in the sky- 천공성(중앙 탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'sion08'), '천공성(중앙 탑)', '천공성(중앙 탑)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Noble022'), 'Noble''s Manor- 북 에스타드 숲(마차 추적)', 'noble''s manor- 북 에스타드 숲(마차 추적)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Noble022'), '북 에스타드 숲(마차 추적)', '북 에스타드 숲(마차 추적)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'snow34'), '대충산(백계)', '대충산(백계)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1001'), '고요의 바다(Normal)', '고요의 바다(normal)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1001'), '칠요의 시련(月) - 고요의 바다(Normal)', '칠요의 시련(月) - 고요의 바다(normal)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1001R'), '고요의 바다(Easy)', '고요의 바다(easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1001R'), '칠요의 시련(月) - 고요의 바다(Easy)', '칠요의 시련(月) - 고요의 바다(easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1004R'), '잊혀진 숲(Easy)', '잊혀진 숲(easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1004R'), '칠요의 시련(木) - 잊혀진 숲(Easy)', '칠요의 시련(木) - 잊혀진 숲(easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1005R'), '금요', '금요');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1006R'), '사령의 안개(Easy)', '사령의 안개(easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAY1006R'), '칠요의 시련(土) - 사령의 안개(Easy)', '칠요의 시련(土) - 사령의 안개(easy)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min092'), '제1 저지선', '제1 저지선');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min092'), '지각 내부 (B5) 흑요석 성채- 제1 저지선', '지각 내부 (b5) 흑요석 성채- 제1 저지선');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min093'), '소환의 방', '소환의 방');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min093'), '지각 내부 (B5) 흑요석 성채- 소환의 방', '지각 내부 (b5) 흑요석 성채- 소환의 방');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min094'), '제2 저지선', '제2 저지선');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min094'), '지각 내부 (B5) 흑요석 성채- 제2 저지선', '지각 내부 (b5) 흑요석 성채- 제2 저지선');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min095'), '지각 내부 (B5) 흑요석 성채- 최종 저지선', '지각 내부 (b5) 흑요석 성채- 최종 저지선');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min095'), '최종 저지선', '최종 저지선');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min096'), '장군 호위대', '장군 호위대');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'min096'), '지각 내부 (B5) 흑요석 성채- 장군 호위대', '지각 내부 (b5) 흑요석 성채- 장군 호위대');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'MineS01'), '흑요석 동굴 통제 구역', '흑요석 동굴 통제 구역');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0001'), '성장의 신전', '성장의 신전');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0003'), '성장의 신전 ~마법사의 시련~', '성장의 신전 ~마법사의 시련~');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0005'), '성장의 신전 ~레인저의 시련~', '성장의 신전 ~레인저의 시련~');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0006'), '성장의 신전 ~도화사의 시련~', '성장의 신전 ~도화사의 시련~');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0012'), '용사의 신전 ~전사의 시련~', '용사의 신전 ~전사의 시련~');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'DAYM0050'), '조용한 도서관', '조용한 도서관');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'Fish01'), '용 낚시', '용 낚시');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'colo02'), 'Balzac''s Invitation - 제 2투기장', 'balzac''s invitation - 제 2투기장');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'adventure_map' and map_code = 'colo02'), '제 2투기장', '제 2투기장');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb0'), 'Goblin- 고블린과 놀기(가장 약함)', 'goblin- 고블린과 놀기(가장 약함)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb0'), '고블린과 놀기(가장 약함)', '고블린과 놀기(가장 약함)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb1'), 'Goblin- 조금 강한 고블린', 'goblin- 조금 강한 고블린');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb1'), '조금 강한 고블린', '조금 강한 고블린');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb2'), 'Goblin- 고블린의 전사들', 'goblin- 고블린의 전사들');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb2'), '고블린의 전사들', '고블린의 전사들');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb4'), 'Goblin- 고귀한 고블린들', 'goblin- 고귀한 고블린들');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb4'), '고귀한 고블린들', '고귀한 고블린들');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb5'), 'Goblin- 고블린 성채', 'goblin- 고블린 성채');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb5'), '고블린 성채', '고블린 성채');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb6'), 'Goblin- 고블린의 왕', 'goblin- 고블린의 왕');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'gb6'), '고블린의 왕', '고블린의 왕');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac0'), 'Ancient Cave- 고대의 동굴', 'ancient cave- 고대의 동굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac0'), '고대의 동굴', '고대의 동굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac1'), 'Ancient Cave- 고대의 동굴 (B2)', 'ancient cave- 고대의 동굴 (b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac1'), '고대의 동굴 (B2)', '고대의 동굴 (b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac2'), 'Ancient Cave- 고대의 동굴 (B3)', 'ancient cave- 고대의 동굴 (b3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac2'), '고대의 동굴 (B3)', '고대의 동굴 (b3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac3'), 'Ancient Cave- 고대의 동굴 (B4)', 'ancient cave- 고대의 동굴 (b4)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac3'), '고대의 동굴 (B4)', '고대의 동굴 (b4)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac4'), 'Ancient Cave- 고대의 동굴 (B5)', 'ancient cave- 고대의 동굴 (b5)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac4'), '고대의 동굴 (B5)', '고대의 동굴 (b5)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac33'), 'Ancient Maze- 고대의 지하미궁 (B1)', 'ancient maze- 고대의 지하미궁 (b1)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac33'), '고대의 지하미궁 (B1)', '고대의 지하미궁 (b1)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac44'), 'Ancient Maze-고대의 지하미궁 (B2)', 'ancient maze-고대의 지하미궁 (b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac44'), '고대의 지하미궁 (B2)', '고대의 지하미궁 (b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac55'), 'Ancient Maze-고대의 지하미궁 (B3)', 'ancient maze-고대의 지하미궁 (b3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ac55'), '고대의 지하미궁 (B3)', '고대의 지하미궁 (b3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'sd1'), 'Desert- 고요의 사막', 'desert- 고요의 사막');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'sd1'), '고요의 사막', '고요의 사막');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'sd2'), 'Desert- 신기루의 사막', 'desert- 신기루의 사막');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'sd2'), '신기루의 사막', '신기루의 사막');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ant1'), 'Desert- 개미굴', 'desert- 개미굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ant1'), '개미굴', '개미굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ant2'), 'Desert- 깊은 개미굴', 'desert- 깊은 개미굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'ant2'), '깊은 개미굴', '깊은 개미굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra1'), 'Anub의 왕묘 입구', 'anub의 왕묘 입구');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra1'), 'Pyramid- Anub의 왕묘 입구', 'pyramid- anub의 왕묘 입구');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra1'), '의 왕묘 입구', '의 왕묘 입구');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra2'), 'Anub의 왕묘(B1)', 'anub의 왕묘(b1)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra2'), 'Pyramid- Anub의 왕묘(B1)', 'pyramid- anub의 왕묘(b1)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra2'), '의 왕묘(B1)', '의 왕묘(b1)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra3'), 'Anub의 왕묘(B2)', 'anub의 왕묘(b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra3'), 'Pyramid- Anub의 왕묘(B2)', 'pyramid- anub의 왕묘(b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra3'), '의 왕묘(B2)', '의 왕묘(b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra4'), 'Anub의 왕묘(B3)', 'anub의 왕묘(b3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra4'), 'Pyramid- Anub의 왕묘(B3)', 'pyramid- anub의 왕묘(b3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra4'), '의 왕묘(B3)', '의 왕묘(b3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra5'), 'Anub의 왕묘(B4) - 왕묘 내실', 'anub의 왕묘(b4) - 왕묘 내실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra5'), 'Pyramid- Anub의 왕묘(B4) - 왕묘 내실', 'pyramid- anub의 왕묘(b4) - 왕묘 내실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra5'), '왕묘 내실', '왕묘 내실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra5'), '의 왕묘(B4) - 왕묘 내실', '의 왕묘(b4) - 왕묘 내실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra6'), 'Anub의 왕묘(B5) - 왕의 안식처', 'anub의 왕묘(b5) - 왕의 안식처');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra6'), 'Pyramid- Anub의 왕묘(B5) - 왕의 안식처', 'pyramid- anub의 왕묘(b5) - 왕의 안식처');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra6'), '왕의 안식처', '왕의 안식처');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra6'), '의 왕묘(B5) - 왕의 안식처', '의 왕묘(b5) - 왕의 안식처');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra22'), 'Anub의 왕묘(F2)', 'anub의 왕묘(f2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra22'), 'Pyramid- Anub의 왕묘(F2)', 'pyramid- anub의 왕묘(f2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra22'), '의 왕묘(F2)', '의 왕묘(f2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra33'), 'Anub의 왕묘(F3)', 'anub의 왕묘(f3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra33'), 'Pyramid- Anub의 왕묘(F3)', 'pyramid- anub의 왕묘(f3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra33'), '의 왕묘(F3)', '의 왕묘(f3)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra44'), 'Anub의 왕묘(F4)', 'anub의 왕묘(f4)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra44'), 'Pyramid- Anub의 왕묘(F4)', 'pyramid- anub의 왕묘(f4)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Pyra44'), '의 왕묘(F4)', '의 왕묘(f4)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'tnfh1'), 'Culvert- 마을 지하 수로(입구)', 'culvert- 마을 지하 수로(입구)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'tnfh1'), '마을 지하 수로(입구)', '마을 지하 수로(입구)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'tnfh2'), 'Culvert- 마을 지하 수로(안쪽)', 'culvert- 마을 지하 수로(안쪽)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'tnfh2'), '마을 지하 수로(안쪽)', '마을 지하 수로(안쪽)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'tnfh22'), 'Culvert- 마을 지하 수로(쓰레기장)', 'culvert- 마을 지하 수로(쓰레기장)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'tnfh22'), '마을 지하 수로(쓰레기장)', '마을 지하 수로(쓰레기장)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'tnfh3'), 'Culvert- 마을 지하 수로(폐수 처리장)', 'culvert- 마을 지하 수로(폐수 처리장)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'tnfh3'), '마을 지하 수로(폐수 처리장)', '마을 지하 수로(폐수 처리장)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow0'), 'Frosty Mountain- 대충산(기슭)', 'frosty mountain- 대충산(기슭)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow0'), '대충산(기슭)', '대충산(기슭)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow1'), 'Frosty Mountain- 대충산(산 중턱)', 'frosty mountain- 대충산(산 중턱)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow1'), '대충산(산 중턱)', '대충산(산 중턱)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow2'), 'Frosty Mountain- 대충산(고원)', 'frosty mountain- 대충산(고원)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow2'), '대충산(고원)', '대충산(고원)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow3'), 'Frosty Mountain- 대충산(만년설봉)', 'frosty mountain- 대충산(만년설봉)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow3'), '대충산(만년설봉)', '대충산(만년설봉)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow4'), 'Frosty Mountain- 대충산(제2봉우리)', 'frosty mountain- 대충산(제2봉우리)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow4'), '대충산(제2봉우리)', '대충산(제2봉우리)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow21'), 'Frosty Mountain- 대충산(얼음 동굴)', 'frosty mountain- 대충산(얼음 동굴)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow21'), '대충산(얼음 동굴)', '대충산(얼음 동굴)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow22'), 'Frosty Mountain- 대충산(마도사의 은신처)', 'frosty mountain- 대충산(마도사의 은신처)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow22'), '대충산(마도사의 은신처)', '대충산(마도사의 은신처)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow23'), 'Frosty Mountain- 대충산(발자두스의 연구실)', 'frosty mountain- 대충산(발자두스의 연구실)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow23'), '대충산(발자두스의 연구실)', '대충산(발자두스의 연구실)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow24'), 'Frosty Mountain- 대충산(리치의 창고)', 'frosty mountain- 대충산(리치의 창고)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow24'), '대충산(리치의 창고)', '대충산(리치의 창고)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow33'), 'Frosty Mountain- 대충산(환상봉)', 'frosty mountain- 대충산(환상봉)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'snow33'), '대충산(환상봉)', '대충산(환상봉)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for01'), 'Jungle- 밀림 입구', 'jungle- 밀림 입구');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for01'), '밀림 입구', '밀림 입구');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for02'), 'Jungle- 원시림', 'jungle- 원시림');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for02'), '원시림', '원시림');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for03'), 'Jungle- 야만인의 숲', 'jungle- 야만인의 숲');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for03'), '야만인의 숲', '야만인의 숲');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for04'), 'Jungle- 야만인 부락 입구', 'jungle- 야만인 부락 입구');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for04'), '야만인 부락 입구', '야만인 부락 입구');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for05'), 'Jungle- 야만인 부락', 'jungle- 야만인 부락');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for05'), '야만인 부락', '야만인 부락');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for06'), 'Jungle- 검은 늪', 'jungle- 검은 늪');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'for06'), '검은 늪', '검은 늪');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mg01'), 'Tower of Magic- 마법신의 탑(하층)', 'tower of magic- 마법신의 탑(하층)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mg01'), '마법신의 탑(하층)', '마법신의 탑(하층)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mg02'), 'Tower of Magic- 마법신의 탑(상층)', 'tower of magic- 마법신의 탑(상층)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mg02'), '마법신의 탑(상층)', '마법신의 탑(상층)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mg03'), 'Tower of Magic- 마법신의 탑 - 봉인 서고', 'tower of magic- 마법신의 탑 - 봉인 서고');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mg03'), '마법신의 탑 - 봉인 서고', '마법신의 탑 - 봉인 서고');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mg03'), '봉인 서고', '봉인 서고');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mg05'), 'Tower of Magic- 마법신의 탑(하층(裏))', 'tower of magic- 마법신의 탑(하층(裏))');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mg05'), '마법신의 탑(하층(裏))', '마법신의 탑(하층(裏))');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt01'), 'Wandai- 완다이 산맥(숲길)', 'wandai- 완다이 산맥(숲길)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt01'), '완다이 산맥(숲길)', '완다이 산맥(숲길)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt02'), 'Wandai- 완다이 산맥(기슭)', 'wandai- 완다이 산맥(기슭)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt02'), '완다이 산맥(기슭)', '완다이 산맥(기슭)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt03'), 'Wandai- 완다이 산맥(산길)', 'wandai- 완다이 산맥(산길)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt03'), '완다이 산맥(산길)', '완다이 산맥(산길)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt032'), 'Wandai- 완다이 산맥(깊은 숲)', 'wandai- 완다이 산맥(깊은 숲)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt032'), '완다이 산맥(깊은 숲)', '완다이 산맥(깊은 숲)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt04'), 'Wandai- 완다이 산맥(절벽길)', 'wandai- 완다이 산맥(절벽길)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt04'), '완다이 산맥(절벽길)', '완다이 산맥(절벽길)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt042'), 'Wandai- 완다이 산맥(오크의 숲)', 'wandai- 완다이 산맥(오크의 숲)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'mt042'), '완다이 산맥(오크의 숲)', '완다이 산맥(오크의 숲)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min01'), 'Dead Pit- 죽음의 폐광', 'dead pit- 죽음의 폐광');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min01'), '죽음의 폐광', '죽음의 폐광');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min02'), 'Dead Pit- 죽음의 폐광 B1', 'dead pit- 죽음의 폐광 b1');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min02'), '죽음의 폐광 B1', '죽음의 폐광 b1');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min03'), 'Dead Pit- 죽음의 폐광 B2 - 불타는 광산', 'dead pit- 죽음의 폐광 b2 - 불타는 광산');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min03'), '불타는 광산', '불타는 광산');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min03'), '죽음의 폐광 B2 - 불타는 광산', '죽음의 폐광 b2 - 불타는 광산');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min04'), 'Dead Pit- 흑요석 동굴', 'dead pit- 흑요석 동굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min04'), '흑요석 동굴', '흑요석 동굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min05'), 'Dead Pit- 지각 내부 (B1)', 'dead pit- 지각 내부 (b1)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min05'), '지각 내부 (B1)', '지각 내부 (b1)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min06'), 'Dead Pit- 지각 내부 (B2)', 'dead pit- 지각 내부 (b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min06'), '지각 내부 (B2)', '지각 내부 (b2)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min07'), 'Dead Pit- 지각 내부 (B3) 화염의 대장간', 'dead pit- 지각 내부 (b3) 화염의 대장간');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min07'), '지각 내부 (B3) 화염의 대장간', '지각 내부 (b3) 화염의 대장간');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min072'), 'Dead Pit- 지각 내부 (B3) 화룡굴', 'dead pit- 지각 내부 (b3) 화룡굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min072'), '지각 내부 (B3) 화룡굴', '지각 내부 (b3) 화룡굴');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min082'), 'Dead Pit- 지각 내부 (B5) 증열의 평원', 'dead pit- 지각 내부 (b5) 증열의 평원');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'min082'), '지각 내부 (B5) 증열의 평원', '지각 내부 (b5) 증열의 평원');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble01'), 'Noble''s Manor- 북 에스타드 숲', 'noble''s manor- 북 에스타드 숲');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble01'), '북 에스타드 숲', '북 에스타드 숲');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble02'), 'Noble''s Manor- 북 에스타드 숲(숲길)', 'noble''s manor- 북 에스타드 숲(숲길)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble02'), '북 에스타드 숲(숲길)', '북 에스타드 숲(숲길)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble0222'), 'Noble''s Manor- 귀족의 장원(사냥 숲)', 'noble''s manor- 귀족의 장원(사냥 숲)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble0222'), '귀족의 장원(사냥 숲)', '귀족의 장원(사냥 숲)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble03'), 'Noble''s Manor- 귀족의 장원(저택 정문)', 'noble''s manor- 귀족의 장원(저택 정문)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble03'), '귀족의 장원(저택 정문)', '귀족의 장원(저택 정문)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble04'), 'Noble''s Manor- 귀족의 장원(저택 정원)', 'noble''s manor- 귀족의 장원(저택 정원)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble04'), '귀족의 장원(저택 정원)', '귀족의 장원(저택 정원)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble102'), 'Noble''s Mansion- 저택 동관(복도)', 'noble''s mansion- 저택 동관(복도)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble102'), '저택 동관(복도)', '저택 동관(복도)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble1021'), 'Noble''s Mansion- 저택 동관(보쉬의 방)', 'noble''s mansion- 저택 동관(보쉬의 방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble1021'), '저택 동관(보쉬의 방)', '저택 동관(보쉬의 방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble1022'), 'Noble''s Mansion- 저택 동관(하인켈의 방)', 'noble''s mansion- 저택 동관(하인켈의 방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble1022'), '저택 동관(하인켈의 방)', '저택 동관(하인켈의 방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble1023'), 'Noble''s Mansion- 저택 동관(커티스의 방)', 'noble''s mansion- 저택 동관(커티스의 방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble1023'), '저택 동관(커티스의 방)', '저택 동관(커티스의 방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble1024'), 'Noble''s Mansion- 저택 동관(장서고)', 'noble''s mansion- 저택 동관(장서고)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble1024'), '저택 동관(장서고)', '저택 동관(장서고)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble201'), 'Noble''s Mansion- 저택 서관(복도)', 'noble''s mansion- 저택 서관(복도)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble201'), '저택 서관(복도)', '저택 서관(복도)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble202'), 'Noble''s Mansion- 저택 서관(인형사의 창고)', 'noble''s mansion- 저택 서관(인형사의 창고)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble202'), '저택 서관(인형사의 창고)', '저택 서관(인형사의 창고)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble203'), 'Noble''s Mansion- 저택 서관(해와 달의 방)', 'noble''s mansion- 저택 서관(해와 달의 방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble203'), '저택 서관(해와 달의 방)', '저택 서관(해와 달의 방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble204'), 'Noble''s Mansion- 저택 서관(인형사의 공방)', 'noble''s mansion- 저택 서관(인형사의 공방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble204'), '저택 서관(인형사의 공방)', '저택 서관(인형사의 공방)');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble303'), 'Noble''s Mansion- 지하 감옥(III) -제196 수감실', 'noble''s mansion- 지하 감옥(iii) -제196 수감실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble303'), '제196 수감실', '제196 수감실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble303'), '지하 감옥(III) -제196 수감실', '지하 감옥(iii) -제196 수감실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble304'), 'Noble''s Mansion- 지하 감옥(IV) -제54 수감실', 'noble''s mansion- 지하 감옥(iv) -제54 수감실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble304'), '제54 수감실', '제54 수감실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble304'), '지하 감옥(IV) -제54 수감실', '지하 감옥(iv) -제54 수감실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble305'), 'Noble''s Mansion- 지하 감옥(V) -고문실', 'noble''s mansion- 지하 감옥(v) -고문실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble305'), '고문실', '고문실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble305'), '지하 감옥(V) -고문실', '지하 감옥(v) -고문실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS01'), 'G.S order Headquarters - 본부 입구', 'g.s order headquarters - 본부 입구');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS01'), '본부 입구', '본부 입구');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS02'), 'G.S order Headquarters - 자애의 관문', 'g.s order headquarters - 자애의 관문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS02'), '자애의 관문', '자애의 관문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS041'), 'G.S order Headquarters - 숭배하는 자의 방', 'g.s order headquarters - 숭배하는 자의 방');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS041'), '숭배하는 자의 방', '숭배하는 자의 방');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS042'), 'G.S order Headquarters - 고행하는 자의 방', 'g.s order headquarters - 고행하는 자의 방');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS042'), '고행하는 자의 방', '고행하는 자의 방');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS043'), 'G.S order Headquarters - 전도하는 자의 방', 'g.s order headquarters - 전도하는 자의 방');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS043'), '전도하는 자의 방', '전도하는 자의 방');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS03'), 'G.S order Headquarters - 슬픔의 관문', 'g.s order headquarters - 슬픔의 관문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS03'), '슬픔의 관문', '슬픔의 관문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS04'), 'G.S order Headquarters - 사도 천사의 관문', 'g.s order headquarters - 사도 천사의 관문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS04'), '사도 천사의 관문', '사도 천사의 관문');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS05'), 'G.S order Headquarters - 삼위일체의 회랑', 'g.s order headquarters - 삼위일체의 회랑');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'GS05'), '삼위일체의 회랑', '삼위일체의 회랑');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble301'), 'Noble''s Mansion- 지하 감옥(I) -제14 수감실', 'noble''s mansion- 지하 감옥(i) -제14 수감실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble301'), '제14 수감실', '제14 수감실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Noble301'), '지하 감옥(I) -제14 수감실', '지하 감옥(i) -제14 수감실');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Fish02'), '피라냐', '피라냐');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Fish03'), '악어', '악어');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Fish04'), '전기 뱀장어', '전기 뱀장어');
insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values ((select id from battle_maps where category_id = 'battle_map' and map_code = 'Sink04'), '선실 내부', '선실 내부');
-- END GENERATED BATTLE MAP SEED

create table refresh_tokens (
    id bigserial,
    account_id bigint not null,
    token_hash varchar(64) not null,
    family_id varchar(36) not null,
    client_type varchar(20) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    rotated_at timestamp with time zone,
    revoked_at timestamp with time zone,
    constraint pk_refresh_tokens primary key (id),
    constraint fk_refresh_tokens_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_refresh_tokens_token_hash unique (token_hash)
);

create index idx_refresh_tokens_family_created
    on refresh_tokens (family_id, created_at, id);

create index idx_refresh_tokens_account_active
    on refresh_tokens (account_id, revoked_at, expires_at, id);

alter table automation_jobs
    add column current_module varchar(50);
alter table automation_jobs
    add column current_action varchar(255);
alter table automation_jobs
    add column next_run_at timestamp with time zone;
alter table automation_jobs
    add column last_heartbeat_at timestamp with time zone;
alter table automation_jobs
    add column version bigint not null default 0;

alter table automation_profile_maps
    add column module_type varchar(50) not null default 'NORMAL_MAP';
alter table automation_profile_maps
    add column purpose varchar(50) not null default 'PRIMARY';

create table automation_module_configs (
    id bigserial,
    profile_id bigint not null,
    module_type varchar(50) not null,
    enabled boolean not null,
    priority integer not null,
    settings_json text not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_automation_module_configs primary key (id),
    constraint fk_automation_module_configs_profile foreign key (profile_id)
        references automation_profiles (id) on delete cascade,
    constraint uk_automation_module_configs_profile_module unique (profile_id, module_type),
    constraint ck_automation_module_configs_priority check (priority >= 0)
);

create index idx_automation_module_configs_profile_priority
    on automation_module_configs (profile_id, priority, id);

create table automation_action_runs (
    id bigserial,
    job_id bigint not null,
    module_type varchar(50) not null,
    action_type varchar(80) not null,
    action_key varchar(255),
    status varchar(50) not null,
    request_key varchar(255) not null,
    payload_json text not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    constraint pk_automation_action_runs primary key (id),
    constraint fk_automation_action_runs_job foreign key (job_id)
        references automation_jobs (id) on delete cascade,
    constraint uk_automation_action_runs_request_key unique (request_key),
    constraint ck_automation_action_runs_attempt_count check (attempt_count >= 0)
);

create index idx_automation_action_runs_job_status_updated
    on automation_action_runs (job_id, status, updated_at, id);

create index idx_automation_jobs_recovery
    on automation_jobs (status, next_run_at, last_heartbeat_at, id);

create table automation_outbox (
    id bigserial,
    event_id varchar(80) not null,
    account_id bigint not null,
    topic varchar(120) not null,
    event_key varchar(80) not null,
    payload text not null,
    created_at timestamp with time zone not null,
    available_at timestamp with time zone not null,
    published_at timestamp with time zone,
    constraint pk_automation_outbox primary key (id),
    constraint uk_automation_outbox_event_id unique (event_id),
    constraint fk_automation_outbox_account foreign key (account_id)
        references hof_accounts (id) on delete cascade
);

create index idx_automation_outbox_unpublished
    on automation_outbox (published_at, available_at, id);

create table automation_consumed_events (
    event_id varchar(80) not null,
    consumed_at timestamp with time zone not null,
    constraint pk_automation_consumed_events primary key (event_id)
);

create table account_automation_leases (
    account_id bigint not null,
    owner_id varchar(120) not null,
    lease_until timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_account_automation_leases primary key (account_id),
    constraint fk_account_automation_leases_account foreign key (account_id)
        references hof_accounts (id) on delete cascade
);

create index idx_account_automation_leases_until
    on account_automation_leases (lease_until, account_id);

create table device_push_targets (
    id bigserial,
    account_id bigint not null,
    platform varchar(20) not null,
    target_type varchar(20) not null,
    installation_id varchar(160) not null,
    target_value text not null,
    active boolean not null,
    last_seen_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    constraint pk_device_push_targets primary key (id),
    constraint fk_device_push_targets_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_device_push_targets_account_installation unique (account_id, installation_id)
);

create index idx_device_push_targets_account_active
    on device_push_targets (account_id, active, last_seen_at, id);

alter table captcha_challenges
    add column automation_action_run_id bigint;

alter table captcha_challenges
    add constraint fk_captcha_challenges_automation_action
    foreign key (automation_action_run_id)
    references automation_action_runs (id) on delete set null;

create index idx_captcha_challenges_automation_action
    on captcha_challenges (automation_action_run_id);

-- AES-GCM ciphertext is longer than the original cookie value because it includes a nonce and tag.
alter table hof_cookies alter column cookie_value type text;

delete from automation_action_runs
where job_id in (
    select jobs.id
    from automation_jobs jobs
    join automation_profiles profiles on profiles.id = jobs.profile_id
    where profiles.mode = 'UNIFIED'
);

delete from automation_jobs
where profile_id in (
    select id
    from automation_profiles
    where mode = 'UNIFIED'
);

delete from automation_profile_maps
where profile_id in (
    select id
    from automation_profiles
    where mode = 'UNIFIED'
);

delete from automation_module_configs
where profile_id in (
    select id
    from automation_profiles
    where mode = 'UNIFIED'
);

alter table automation_module_configs
    drop constraint uk_automation_module_configs_profile_module;

alter table automation_module_configs
    add column display_name varchar(50);

update automation_module_configs
set display_name = module_type;

alter table automation_module_configs
    alter column display_name set not null;

alter table automation_module_configs
    add column threshold_percent integer;

alter table automation_module_configs
    add constraint ck_automation_module_configs_threshold_percent
        check (threshold_percent is null or threshold_percent between 1 and 100);

create table automation_module_legacy_settings (
    module_config_id bigint,
    settings_json text not null,
    constraint pk_automation_module_legacy_settings primary key (module_config_id),
    constraint fk_automation_module_legacy_settings_config foreign key (module_config_id)
        references automation_module_configs (id) on delete cascade
);

insert into automation_module_legacy_settings (module_config_id, settings_json)
select id, settings_json
from automation_module_configs;

alter table automation_module_configs
    drop column settings_json;

create table automation_module_maps (
    id bigserial,
    module_config_id bigint not null,
    battle_map_id bigint not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_automation_module_maps primary key (id),
    constraint fk_automation_module_maps_config foreign key (module_config_id)
        references automation_module_configs (id) on delete cascade,
    constraint fk_automation_module_maps_battle_map foreign key (battle_map_id)
        references battle_maps (id),
    constraint fk_automation_module_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_automation_module_maps_module_map unique (module_config_id, battle_map_id),
    constraint ck_automation_module_maps_execution_order check (execution_order >= 0)
);

create index idx_automation_module_maps_module_order
    on automation_module_maps (module_config_id, execution_order, id);

create index idx_automation_module_maps_battle_map
    on automation_module_maps (battle_map_id, id);

create index idx_automation_module_maps_party_preset
    on automation_module_maps (party_preset_id, id);

create table automation_module_quests (
    id bigserial,
    module_config_id bigint not null,
    quest_code varchar(100) not null,
    execution_order integer not null,
    constraint pk_automation_module_quests primary key (id),
    constraint fk_automation_module_quests_config foreign key (module_config_id)
        references automation_module_configs (id) on delete cascade,
    constraint uk_automation_module_quests_module_quest unique (module_config_id, quest_code),
    constraint ck_automation_module_quests_execution_order check (execution_order >= 0)
);

create index idx_automation_module_quests_module_order
    on automation_module_quests (module_config_id, execution_order, id);

create table automation_module_quest_maps (
    id bigserial,
    module_quest_id bigint not null,
    battle_map_id bigint not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_automation_module_quest_maps primary key (id),
    constraint fk_automation_module_quest_maps_quest foreign key (module_quest_id)
        references automation_module_quests (id) on delete cascade,
    constraint fk_automation_module_quest_maps_battle_map foreign key (battle_map_id)
        references battle_maps (id),
    constraint fk_automation_module_quest_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_automation_module_quest_maps_quest_map unique (module_quest_id, battle_map_id),
    constraint ck_automation_module_quest_maps_execution_order check (execution_order >= 0)
);

create index idx_automation_module_quest_maps_quest_order
    on automation_module_quest_maps (module_quest_id, execution_order, id);

create index idx_automation_module_quest_maps_battle_map
    on automation_module_quest_maps (battle_map_id, id);

create index idx_automation_module_quest_maps_party_preset
    on automation_module_quest_maps (party_preset_id, id);

alter table automation_jobs
    add column current_module_config_id bigint;

alter table automation_jobs
    add constraint fk_automation_jobs_current_module_config foreign key (current_module_config_id)
        references automation_module_configs (id) on delete set null;

create index idx_automation_jobs_current_module_config
    on automation_jobs (current_module_config_id, id);

alter table automation_action_runs
    add column module_config_id bigint;

alter table automation_action_runs
    add constraint fk_automation_action_runs_module_config foreign key (module_config_id)
        references automation_module_configs (id) on delete set null;

create index idx_automation_action_runs_module_config
    on automation_action_runs (module_config_id, id);

delete from automation_action_runs;
delete from automation_jobs;
delete from automation_outbox;
delete from automation_consumed_events;
delete from account_automation_leases;
delete from automation_module_quest_maps;
delete from automation_module_quests;
delete from automation_module_maps;
delete from automation_module_legacy_settings;
delete from automation_module_configs;
delete from automation_profile_maps;
delete from automation_profiles;

alter table party_presets
    add column is_primary boolean not null default false;

-- PostgreSQL partial indexes are not accepted by H2 in PostgreSQL mode. A nullable marker preserves
-- unlimited non-primary presets while a portable unique constraint permits only one primary/account.
alter table party_presets
    add column primary_marker integer;

alter table party_presets
    add constraint ck_party_presets_primary_marker
        check ((is_primary and primary_marker = 1) or (not is_primary and primary_marker is null));

alter table party_presets
    add constraint uk_party_presets_account_primary_marker unique (account_id, primary_marker);

create table automation_entries (
    id bigserial,
    account_id bigint not null,
    automation_type varchar(30) not null,
    priority integer not null,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_automation_entries primary key (id),
    constraint fk_automation_entries_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_automation_entries_account_type unique (account_id, automation_type),
    constraint ck_automation_entries_type
        check (case automation_type
            when 'QUEST' then true
            when 'BATTLE_MAP' then true
            when 'ADVENTURE_MAP' then true
            else false
        end),
    constraint ck_automation_entries_priority check (priority >= 0)
);

create index idx_automation_entries_account_priority
    on automation_entries (account_id, priority, id);

create table quest_automation_selections (
    id bigserial,
    automation_entry_id bigint not null,
    quest_code varchar(100) not null,
    enabled boolean not null,
    source_order integer not null,
    constraint pk_quest_automation_selections primary key (id),
    constraint fk_quest_automation_selections_entry foreign key (automation_entry_id)
        references automation_entries (id) on delete cascade,
    constraint uk_quest_automation_selections_entry_quest unique (automation_entry_id, quest_code),
    constraint ck_quest_automation_selections_source_order check (source_order >= 0)
);

create index idx_quest_automation_selections_entry_order
    on quest_automation_selections (automation_entry_id, source_order, id);

create table quest_automation_maps (
    id bigserial,
    quest_selection_id bigint not null,
    mission_key varchar(100) not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    execution_order integer not null,
    manually_overridden boolean not null,
    constraint pk_quest_automation_maps primary key (id),
    constraint fk_quest_automation_maps_selection foreign key (quest_selection_id)
        references quest_automation_selections (id) on delete cascade,
    constraint fk_quest_automation_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_quest_automation_maps_selection_mission_map
        unique (quest_selection_id, mission_key, category_id, map_code),
    constraint ck_quest_automation_maps_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end),
    constraint ck_quest_automation_maps_execution_order check (execution_order >= 0)
);

create index idx_quest_automation_maps_selection_order
    on quest_automation_maps (quest_selection_id, execution_order, id);
create index idx_quest_automation_maps_identity
    on quest_automation_maps (category_id, map_code, id);
create index idx_quest_automation_maps_party_preset
    on quest_automation_maps (party_preset_id, id);

create table quest_map_execution_counters (
    id bigserial,
    account_id bigint not null,
    quest_code varchar(100) not null,
    quest_cycle varchar(100) not null,
    mission_key varchar(100) not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    successful_runs integer not null,
    constraint pk_quest_map_execution_counters primary key (id),
    constraint fk_quest_map_execution_counters_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_quest_map_execution_counters_identity
        unique (account_id, quest_code, quest_cycle, mission_key, category_id, map_code),
    constraint ck_quest_map_execution_counters_successful_runs check (successful_runs >= 0)
);

create index idx_quest_map_execution_counters_account_cycle
    on quest_map_execution_counters (account_id, quest_cycle, quest_code, id);
create index idx_quest_map_execution_counters_map_identity
    on quest_map_execution_counters (category_id, map_code, id);

create table battle_automation_maps (
    id bigserial,
    automation_entry_id bigint not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    daily_target_count integer not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_battle_automation_maps primary key (id),
    constraint fk_battle_automation_maps_entry foreign key (automation_entry_id)
        references automation_entries (id) on delete cascade,
    constraint fk_battle_automation_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_battle_automation_maps_entry_map unique (automation_entry_id, category_id, map_code),
    constraint ck_battle_automation_maps_daily_target check (daily_target_count > 0),
    constraint ck_battle_automation_maps_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end),
    constraint ck_battle_automation_maps_execution_order check (execution_order >= 0)
);

create index idx_battle_automation_maps_entry_order
    on battle_automation_maps (automation_entry_id, execution_order, id);
create index idx_battle_automation_maps_identity
    on battle_automation_maps (category_id, map_code, id);
create index idx_battle_automation_maps_party_preset
    on battle_automation_maps (party_preset_id, id);

create table battle_automation_daily_progress (
    id bigserial,
    account_id bigint not null,
    progress_date date not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    source varchar(50) not null,
    successful_runs integer not null,
    updated_at timestamp with time zone not null,
    constraint pk_battle_automation_daily_progress primary key (id),
    constraint fk_battle_automation_daily_progress_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_battle_automation_daily_progress_identity
        unique (account_id, progress_date, category_id, map_code, source),
    constraint ck_battle_automation_daily_progress_successful_runs check (successful_runs >= 0)
);

create index idx_battle_automation_daily_progress_account_date
    on battle_automation_daily_progress (account_id, progress_date, source, id);
create index idx_battle_automation_daily_progress_map_identity
    on battle_automation_daily_progress (category_id, map_code, progress_date, id);

create table adventure_automation_maps (
    id bigserial,
    automation_entry_id bigint not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_adventure_automation_maps primary key (id),
    constraint fk_adventure_automation_maps_entry foreign key (automation_entry_id)
        references automation_entries (id) on delete cascade,
    constraint fk_adventure_automation_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_adventure_automation_maps_entry_map unique (automation_entry_id, category_id, map_code),
    constraint ck_adventure_automation_maps_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end),
    constraint ck_adventure_automation_maps_execution_order check (execution_order >= 0)
);

create index idx_adventure_automation_maps_entry_order
    on adventure_automation_maps (automation_entry_id, execution_order, id);
create index idx_adventure_automation_maps_identity
    on adventure_automation_maps (category_id, map_code, id);
create index idx_adventure_automation_maps_party_preset
    on adventure_automation_maps (party_preset_id, id);

create table adventure_daily_refresh (
    id bigserial,
    account_id bigint not null,
    refresh_date date not null,
    refreshed_at timestamp with time zone not null,
    constraint pk_adventure_daily_refresh primary key (id),
    constraint fk_adventure_daily_refresh_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_adventure_daily_refresh_account_date unique (account_id, refresh_date)
);

create index idx_adventure_daily_refresh_account_date
    on adventure_daily_refresh (account_id, refresh_date, id);

create table adventure_daily_preflight_states (
    id bigserial,
    account_id bigint not null,
    refresh_date date not null,
    failed_attempts integer not null,
    next_attempt_at timestamp with time zone,
    stop_reason varchar(30),
    updated_at timestamp with time zone not null,
    constraint pk_adventure_daily_preflight_states primary key (id),
    constraint fk_adventure_daily_preflight_states_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_adventure_daily_preflight_states_account unique (account_id),
    constraint ck_adventure_daily_preflight_states_attempts check (failed_attempts >= 0)
);

create index idx_adventure_daily_preflight_states_next_attempt
    on adventure_daily_preflight_states (next_attempt_at, account_id);

alter table adventure_daily_preflight_states
    add column in_flight_token varchar(36);

alter table adventure_daily_preflight_states
    add column in_flight_until timestamp with time zone;

alter table adventure_daily_preflight_states
    add constraint ck_adventure_daily_preflight_states_in_flight
        check (case
            when in_flight_token is null and in_flight_until is null then true
            when in_flight_token is not null and in_flight_until is not null then true
            else false
        end);

create index idx_adventure_daily_preflight_states_in_flight
    on adventure_daily_preflight_states (in_flight_until, account_id);

create table quest_automation_cycles (
    id bigserial,
    account_id bigint not null,
    quest_code varchar(100) not null,
    current_cycle bigint not null,
    constraint pk_quest_automation_cycles primary key (id),
    constraint fk_quest_automation_cycles_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_quest_automation_cycles_account_quest unique (account_id, quest_code),
    constraint ck_quest_automation_cycles_current_cycle check (current_cycle > 0)
);

create table quest_automation_processed_results (
    id bigserial,
    account_id bigint not null,
    result_kind varchar(30) not null,
    result_identity varchar(128) not null,
    action_fingerprint varchar(64) not null,
    result_value varchar(100),
    processed_at timestamp with time zone not null,
    constraint pk_quest_automation_processed_results primary key (id),
    constraint fk_quest_automation_processed_results_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_quest_automation_processed_results_identity
        unique (account_id, result_identity),
    constraint ck_quest_automation_processed_results_identity
        check (char_length(trim(result_identity)) between 1 and 128),
    constraint ck_quest_automation_processed_results_fingerprint
        check (char_length(action_fingerprint) = 64),
    constraint ck_quest_automation_processed_results_kind_value
        check (case result_kind
            when 'ACCEPT' then result_value is not null and cast(result_value as bigint) > 0
            when 'BATTLE_VICTORY' then result_value is null
            else false
        end)
);

alter table account_battle_map_states
    add column supports_three_battles boolean not null default false;

create table battle_automation_processed_results (
    id bigserial,
    account_id bigint not null,
    result_identity varchar(128) not null,
    execution_identity varchar(128) not null,
    action_fingerprint varchar(64) not null,
    outcome_fingerprint varchar(64) not null,
    victory_count integer not null,
    processed_at timestamp with time zone not null,
    constraint pk_battle_automation_processed_results primary key (id),
    constraint fk_battle_automation_processed_results_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_battle_automation_processed_results_identity unique (account_id, result_identity),
    constraint uk_battle_automation_processed_results_execution unique (account_id, execution_identity),
    constraint ck_battle_automation_processed_results_result_identity
        check (char_length(trim(result_identity)) between 1 and 128),
    constraint ck_battle_automation_processed_results_execution_identity
        check (char_length(trim(execution_identity)) between 1 and 128),
    constraint ck_battle_automation_processed_results_action_fingerprint
        check (char_length(action_fingerprint) = 64),
    constraint ck_battle_automation_processed_results_outcome_fingerprint
        check (char_length(outcome_fingerprint) = 64),
    constraint ck_battle_automation_processed_results_victories check (victory_count between 0 and 3)
);

create table typed_automation_runtime_states (
    account_id bigint,
    lifecycle_status varchar(20) not null,
    stop_reason varchar(30),
    retry_attempt integer not null default 0,
    next_attempt_at timestamp with time zone,
    lease_token varchar(128),
    lease_until timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint pk_typed_automation_runtime_states primary key (account_id),
    constraint fk_typed_runtime_account foreign key (account_id) references hof_accounts(id) on delete cascade,
    constraint ck_typed_runtime_lifecycle check (lifecycle_status in ('RUNNING','PAUSED','STOPPED')),
    constraint ck_typed_runtime_stop check (
        (lifecycle_status = 'STOPPED' and stop_reason is not null) or
        (lifecycle_status <> 'STOPPED' and stop_reason is null)
    ),
    constraint ck_typed_runtime_retry check (retry_attempt >= 0),
    constraint ck_typed_runtime_lease check (
        (lease_token is null and lease_until is null) or (lease_token is not null and lease_until is not null)
    )
);

create table typed_automation_action_runs (
    id bigserial,
    account_id bigint not null,
    automation_entry_id bigint not null,
    execution_identity varchar(128) not null,
    action_kind varchar(30) not null,
    schema_version integer not null,
    payload_json text not null,
    action_fingerprint varchar(64) not null,
    status varchar(20) not null,
    retry_attempt integer not null default 0,
    next_attempt_at timestamp with time zone,
    lease_token varchar(128) not null,
    last_error text,
    created_at timestamp with time zone not null,
    submitted_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    constraint pk_typed_automation_action_runs primary key (id),
    constraint fk_typed_action_account foreign key (account_id) references hof_accounts(id) on delete cascade,
    constraint fk_typed_action_entry foreign key (automation_entry_id) references automation_entries(id) on delete cascade,
    constraint uk_typed_action_execution unique (account_id, execution_identity),
    constraint ck_typed_action_status check (status in ('PREPARED','SUBMITTING','SUCCEEDED','FAILED','AMBIGUOUS')),
    constraint ck_typed_action_schema check (schema_version > 0),
    constraint ck_typed_action_retry check (retry_attempt >= 0),
    constraint ck_typed_action_fingerprint check (length(action_fingerprint) = 64)
);

create index idx_typed_action_account_status on typed_automation_action_runs(account_id, status, updated_at, id);

alter table typed_automation_runtime_states add column warning_text text;
alter table typed_automation_runtime_states add column last_error text;

-- H2 2.4 rejects valid VARCHAR rows against its migrated IN predicate in PostgreSQL mode.
-- SQL-standard POSITION preserves the same exact enum invariant on both H2 and PostgreSQL.
alter table typed_automation_runtime_states drop constraint ck_typed_runtime_lifecycle;
alter table typed_automation_runtime_states add constraint ck_typed_runtime_lifecycle
    check (position(',' || lifecycle_status || ',' in ',RUNNING,PAUSED,STOPPED,') > 0);

alter table typed_automation_action_runs drop constraint ck_typed_action_status;
alter table typed_automation_action_runs add constraint ck_typed_action_status
    check (position(',' || status || ',' in ',PREPARED,SUBMITTING,SUCCEEDED,FAILED,AMBIGUOUS,') > 0);

alter table typed_automation_action_runs drop constraint fk_typed_action_entry;
alter table typed_automation_action_runs alter column automation_entry_id drop not null;
alter table typed_automation_action_runs add constraint fk_typed_action_entry
    foreign key (automation_entry_id) references automation_entries(id) on delete set null;

alter table typed_automation_runtime_states add column stop_action_id bigint;

alter table typed_automation_runtime_states add constraint fk_typed_runtime_stop_action
    foreign key (stop_action_id) references typed_automation_action_runs(id) on delete set null;

alter table typed_automation_runtime_states add constraint ck_typed_runtime_stop_action
    check (lifecycle_status = 'STOPPED' or stop_action_id is null);

update automation_action_runs
set status = 'ABORTED',
    next_attempt_at = null,
    last_error = coalesce(last_error, 'Legacy automation job runtime retired'),
    finished_at = coalesce(finished_at, current_timestamp),
    updated_at = current_timestamp
where status in ('PLANNED', 'RUNNING', 'RETRY_WAIT', 'WAITING_CAPTCHA', 'WAITING_CONFIG')
  and job_id in (
      select id
      from automation_jobs
      where status in ('PENDING', 'RUNNING', 'WAITING_CAPTCHA', 'WAITING_CONFIG', 'WAITING_LOGIN', 'PAUSED')
  );

update automation_jobs
set status = 'CANCELLED',
    message = '레거시 자동화 실행 경로 종료로 취소됨',
    current_module = null,
    current_module_config_id = null,
    current_action = null,
    next_run_at = null,
    last_heartbeat_at = null,
    finished_at = coalesce(finished_at, current_timestamp),
    updated_at = current_timestamp
where status in ('PENDING', 'RUNNING', 'WAITING_CAPTCHA', 'WAITING_CONFIG', 'WAITING_LOGIN', 'PAUSED');

update automation_jobs
set current_module = null,
    current_module_config_id = null,
    current_action = null,
    next_run_at = null,
    last_heartbeat_at = null
where current_module is not null
   or current_module_config_id is not null
   or current_action is not null
   or next_run_at is not null
   or last_heartbeat_at is not null;

alter table account_battle_map_states
    add column key_mode varchar(20) default 'UNKNOWN' not null;

alter table unresolved_battle_maps
    add column key_mode varchar(20) default 'UNKNOWN' not null;

update account_battle_map_states
set key_mode = 'LIMITED'
where key_count is not null;

update unresolved_battle_maps
set key_mode = 'LIMITED'
where key_count is not null;

alter table account_battle_map_states add constraint ck_account_battle_map_states_key_mode
    check (
        case key_mode
            when 'NOT_REQUIRED' then true
            when 'LIMITED' then true
            when 'UNLIMITED' then true
            when 'UNKNOWN' then true
            else false
        end
    );

alter table account_battle_map_states add constraint ck_account_battle_map_states_key_consistency
    check (
        (key_mode = 'LIMITED' and key_count is not null)
        or (key_mode <> 'LIMITED' and key_count is null)
    );

alter table unresolved_battle_maps add constraint ck_unresolved_battle_maps_key_mode
    check (
        case key_mode
            when 'NOT_REQUIRED' then true
            when 'LIMITED' then true
            when 'UNLIMITED' then true
            when 'UNKNOWN' then true
            else false
        end
    );

alter table unresolved_battle_maps add constraint ck_unresolved_battle_maps_key_consistency
    check (
        (key_mode = 'LIMITED' and key_count is not null)
        or (key_mode <> 'LIMITED' and key_count is null)
    );

update battle_maps
set name = 'Dead Pit- 지각 내부 (B4) Tuls의 문',
    normalized_name = 'dead pit- 지각 내부 (b4) tuls의 문'
where category_id = 'adventure_map'
  and map_code = 'min08';

alter table captcha_challenges
    drop constraint fk_captcha_challenges_automation_action;

drop index idx_captcha_challenges_automation_action;

alter table captcha_challenges
    drop column automation_action_run_id;

drop table automation_module_quest_maps;
drop table automation_module_quests;
drop table automation_module_maps;
drop table automation_module_legacy_settings;
drop table automation_action_runs;
drop table automation_jobs;
drop table automation_module_configs;
drop table automation_profile_maps;
drop table automation_profiles;
