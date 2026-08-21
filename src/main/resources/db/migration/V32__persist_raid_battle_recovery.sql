alter table raid_automation_cycles
    add column battle_recovery_chain_id varchar(64);
alter table raid_automation_cycles
    add column battle_recovery_original_execution_identity varchar(128);
alter table raid_automation_cycles
    add column battle_recovery_latest_execution_identity varchar(128);
alter table raid_automation_cycles
    add column battle_recovery_first_ambiguous_at timestamp with time zone;
alter table raid_automation_cycles
    add column battle_recovery_last_submitted_at timestamp with time zone;
alter table raid_automation_cycles
    add column battle_recovery_retransmission_count integer;
alter table raid_automation_cycles
    add column battle_recovery_next_check_at timestamp with time zone;
alter table raid_automation_cycles
    add column battle_recovery_category_id varchar(50);
alter table raid_automation_cycles
    add column battle_recovery_map_code varchar(100);
alter table raid_automation_cycles
    add column battle_recovery_submitted_from_runnable boolean;
alter table raid_automation_cycles
    add column battle_recovery_last_observation varchar(32);

alter table raid_automation_cycles
    add constraint ck_raid_battle_recovery_count
        check (battle_recovery_retransmission_count is null or battle_recovery_retransmission_count >= 0);
