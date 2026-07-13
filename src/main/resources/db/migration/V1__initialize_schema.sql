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
