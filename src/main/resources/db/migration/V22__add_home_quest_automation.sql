alter table automation_entries drop constraint ck_automation_entries_type;
alter table automation_entries add constraint ck_automation_entries_type
    check (position(',' || automation_type || ',' in ',QUEST,HOME_QUEST,BATTLE_MAP,ADVENTURE_MAP,RAID,UNION,FISHING,') > 0);

alter table automation_work_sessions drop constraint ck_automation_work_type;
alter table automation_work_sessions add constraint ck_automation_work_type
    check (position(',' || work_type || ',' in ',QUEST,HOME_QUEST,BATTLE_MAP,ADVENTURE_MAP,RAID,UNION,FISHING,') > 0);

create table home_quest_automation_selections (
    id bigserial,
    automation_entry_id bigint not null,
    quest_id varchar(64) not null,
    quest_name varchar(300) not null,
    enabled boolean not null,
    source_order integer not null,
    constraint pk_home_quest_automation_selections primary key (id),
    constraint fk_home_quest_automation_selections_entry foreign key (automation_entry_id)
        references automation_entries(id) on delete cascade,
    constraint uk_home_quest_automation_selection unique (automation_entry_id, quest_id),
    constraint uk_home_quest_automation_order unique (automation_entry_id, source_order),
    constraint ck_home_quest_automation_order check (source_order >= 0)
);

create index idx_home_quest_automation_entry_order
    on home_quest_automation_selections (automation_entry_id, source_order, id);
