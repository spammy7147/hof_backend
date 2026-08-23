alter table automation_decision_events
    add column diagnostic_kind varchar(64);
alter table automation_decision_events
    add column cooldown_source varchar(40);
alter table automation_decision_events
    add column impact_scope varchar(64);
alter table automation_decision_events
    add column release_condition varchar(255);

alter table automation_decision_events
    add constraint ck_automation_decision_events_diagnostic_kind check (
        diagnostic_kind is null or position(',' || diagnostic_kind || ',' in ',RAID_HOF_COOLDOWN,RAID_SINGLE_TARGET_TIMER,RAID_LOCAL_SAFETY_GATE,RAID_DEPLOYMENT_SAFETY_GATE,RAID_COOLDOWN_OBSERVATION_AMBIGUOUS,RAID_COOLDOWN_OBSERVATION_HELD,RAID_EXPLICIT_COOLDOWN_WAIT,RAID_REWARD_CONFIRMATION_WAIT,RAID_REWARD_RESULT_RECHECK,RAID_REWARD_OBSERVATION_HELD,RAID_BATTLE_RESULT_UNKNOWN,RAID_REWARD_RESULT_HELD,') > 0
    );
alter table automation_decision_events
    add constraint ck_automation_decision_events_cooldown_source check (
        cooldown_source is null or position(',' || cooldown_source || ',' in ',HOF_DIRECT,HOF_SINGLE_TARGET_INFERENCE,LOCAL_FALLBACK,DEPLOYMENT_FALLBACK,') > 0
    );
alter table automation_decision_events
    add constraint ck_automation_decision_events_impact_scope check (
        impact_scope is null or impact_scope = 'RAID_ONLY'
    );
