create table automation_decision_cycles (
    id bigserial,
    account_id bigint not null,
    result varchar(32) not null,
    selected_entry_id bigint,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone not null,
    constraint pk_automation_decision_cycles primary key (id),
    constraint fk_automation_decision_cycles_account foreign key (account_id) references hof_accounts(id) on delete cascade,
    constraint fk_automation_decision_cycles_entry foreign key (selected_entry_id) references automation_entries(id) on delete set null,
    constraint ck_automation_decision_cycles_result check (position(',' || result || ',' in ',ACTION_SELECTED,WAITING,IDLE,FATAL,') > 0)
);
create index idx_automation_decision_cycles_account_cursor on automation_decision_cycles (account_id, id desc);
create index idx_automation_decision_cycles_account_time on automation_decision_cycles (account_id, started_at desc, id desc);

create table automation_decision_events (
    id bigserial,
    decision_cycle_id bigint not null,
    sequence_no integer not null,
    automation_entry_id bigint,
    automation_type varchar(30), event_kind varchar(32) not null,
    reason_code varchar(100) not null, message varchar(1000) not null,
    target_key varchar(255), target_name varchar(255), action_kind varchar(64),
    preset_id bigint, preset_name varchar(255), next_run_at timestamp with time zone,
    occurred_at timestamp with time zone not null,
    constraint pk_automation_decision_events primary key (id),
    constraint fk_automation_decision_events_cycle foreign key (decision_cycle_id) references automation_decision_cycles(id) on delete cascade,
    constraint fk_automation_decision_events_entry foreign key (automation_entry_id) references automation_entries(id) on delete set null,
    constraint uk_automation_decision_events_sequence unique (decision_cycle_id, sequence_no)
);
create index idx_automation_decision_events_cycle_sequence on automation_decision_events (decision_cycle_id, sequence_no, id);
create index idx_automation_decision_events_filters on automation_decision_events (automation_type, event_kind, decision_cycle_id);
