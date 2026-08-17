alter table character_sync_jobs
    add column stop_requested boolean not null default false;

alter table character_sync_jobs
    add column last_completed_roster_index integer not null default -1;

alter table character_sync_jobs
    add column current_hof_character_id varchar(50);

alter table character_sync_jobs
    add constraint ck_character_sync_jobs_last_completed_index
        check (last_completed_roster_index >= -1);
