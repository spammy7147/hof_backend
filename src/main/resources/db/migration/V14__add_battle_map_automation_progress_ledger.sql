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
    constraint ck_battle_automation_processed_results_victories check (victory_count between 0 and 3)
);

create index idx_battle_automation_processed_results_execution
    on battle_automation_processed_results (account_id, execution_identity, id);
