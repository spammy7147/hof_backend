alter table automation_entries drop constraint ck_automation_entries_type;
alter table automation_entries add constraint ck_automation_entries_type
    check (position(',' || automation_type || ',' in ',QUEST,HOME_QUEST,BATTLE_MAP,ADVENTURE_MAP,RAID,UNION,FISHING,COLOSSEUM,') > 0);
alter table automation_work_sessions drop constraint ck_automation_work_type;
alter table automation_work_sessions add constraint ck_automation_work_type
    check (position(',' || work_type || ',' in ',QUEST,HOME_QUEST,BATTLE_MAP,ADVENTURE_MAP,RAID,UNION,FISHING,COLOSSEUM,') > 0);
