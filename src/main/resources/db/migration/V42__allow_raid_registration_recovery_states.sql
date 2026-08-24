alter table raid_automation_cycles
    drop constraint ck_raid_automation_cycles_open;

alter table raid_automation_cycles
    drop constraint ck_raid_automation_cycles_status;

alter table raid_automation_cycles
    add constraint ck_raid_automation_cycles_status
        check (position(',' || status || ',' in ',PREPARING,REGISTRATION_REFRESH_REQUIRED,REGISTRATION_COOLDOWN,REGISTERED_WAITING,IN_BATTLE,REWARD_PENDING,POST_REWARD_CHECK,COMPLETED,ABORTED_CLOSED,ABORTED_REGISTRATION_LOST,HANDED_OFF_MANUAL,SUPERSEDED_BY_OBSERVED_RAID,') > 0);

alter table raid_automation_cycles
    add constraint ck_raid_automation_cycles_open
        check (
            (position(',' || status || ',' in ',COMPLETED,ABORTED_CLOSED,ABORTED_REGISTRATION_LOST,HANDED_OFF_MANUAL,SUPERSEDED_BY_OBSERVED_RAID,') > 0 and open_marker is null and finished_at is not null)
            or
            (position(',' || status || ',' in ',PREPARING,REGISTRATION_REFRESH_REQUIRED,REGISTRATION_COOLDOWN,REGISTERED_WAITING,IN_BATTLE,REWARD_PENDING,POST_REWARD_CHECK,') > 0 and open_marker = 1 and finished_at is null)
        );
