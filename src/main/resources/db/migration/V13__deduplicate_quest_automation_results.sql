create table quest_automation_processed_results (
    id bigserial,
    account_id bigint not null,
    result_kind varchar(30) not null,
    result_identity varchar(150) not null,
    result_value varchar(100),
    processed_at timestamp with time zone not null,
    constraint pk_quest_automation_processed_results primary key (id),
    constraint fk_quest_automation_processed_results_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_quest_automation_processed_results_identity
        unique (account_id, result_kind, result_identity),
    constraint ck_quest_automation_processed_results_kind
        check (case result_kind when 'ACCEPT' then true when 'BATTLE_VICTORY' then true else false end)
);
