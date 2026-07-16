create table quest_automation_processed_results (
    id bigserial,
    account_id bigint not null,
    result_kind varchar(30) not null,
    result_identity varchar(128) not null,
    action_fingerprint varchar(64) not null,
    result_value varchar(100),
    processed_at timestamp with time zone not null,
    constraint pk_quest_automation_processed_results primary key (id),
    constraint fk_quest_automation_processed_results_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_quest_automation_processed_results_identity
        unique (account_id, result_identity),
    constraint ck_quest_automation_processed_results_identity
        check (char_length(trim(result_identity)) between 1 and 128),
    constraint ck_quest_automation_processed_results_fingerprint
        check (char_length(action_fingerprint) = 64),
    constraint ck_quest_automation_processed_results_kind_value
        check (case result_kind
            when 'ACCEPT' then result_value is not null and cast(result_value as bigint) > 0
            when 'BATTLE_VICTORY' then result_value is null
            else false
        end)
);
