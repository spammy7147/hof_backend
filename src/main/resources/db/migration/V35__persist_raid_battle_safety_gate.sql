alter table raid_automation_cycles
    add column battle_cooldown_not_before timestamp with time zone;
alter table raid_automation_cycles
    add column battle_cooldown_source varchar(40);
alter table raid_automation_cycles
    add column battle_cooldown_started_at timestamp with time zone;
alter table raid_automation_cycles
    add column battle_cooldown_raid_id varchar(200);
alter table raid_automation_cycles
    add column battle_cooldown_category_id varchar(50);
alter table raid_automation_cycles
    add column battle_cooldown_map_code varchar(100);
alter table raid_automation_cycles
    add column battle_cooldown_execution_identity varchar(128);
alter table raid_automation_cycles
    add column battle_cooldown_first_incomplete_at timestamp with time zone;
alter table raid_automation_cycles
    add column battle_cooldown_incomplete_observations integer;
alter table raid_automation_cycles
    add column battle_cooldown_last_observed_at timestamp with time zone;
alter table raid_automation_cycles
    add column battle_cooldown_evidence_case_id varchar(64);
alter table raid_automation_cycles
    add column battle_cooldown_held boolean not null default false;
alter table raid_automation_cycles
    add column battle_safety_version integer not null default 0;
alter table raid_automation_cycles
    add column reward_recovery_kind varchar(32);
alter table raid_automation_cycles
    add column reward_recovery_execution_identity varchar(128);
alter table raid_automation_cycles
    add column reward_recovery_first_ambiguous_at timestamp with time zone;
alter table raid_automation_cycles
    add column reward_recovery_observation_count integer;
alter table raid_automation_cycles
    add column reward_recovery_retry_count integer;
alter table raid_automation_cycles
    add column reward_recovery_held boolean not null default false;

alter table raid_automation_cycles
    add constraint ck_raid_battle_cooldown_incomplete_count
        check (battle_cooldown_incomplete_observations is null or battle_cooldown_incomplete_observations >= 0);
alter table raid_automation_cycles
    add constraint ck_raid_battle_safety_version
        check (battle_safety_version >= 0);
alter table raid_automation_cycles
    add constraint ck_raid_reward_recovery_counts
        check ((reward_recovery_observation_count is null or reward_recovery_observation_count >= 0)
            and (reward_recovery_retry_count is null or reward_recovery_retry_count between 0 and 1));
alter table raid_automation_cycles
    add constraint ck_raid_reward_recovery_kind
        check (reward_recovery_kind is null or
            position(',' || reward_recovery_kind || ',' in ',WINDOW_OBSERVATION,ACTION_RESULT,') > 0);
