create table quest_automation_cycles (
    id bigserial,
    account_id bigint not null,
    quest_code varchar(100) not null,
    current_cycle bigint not null,
    constraint pk_quest_automation_cycles primary key (id),
    constraint fk_quest_automation_cycles_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_quest_automation_cycles_account_quest unique (account_id, quest_code),
    constraint ck_quest_automation_cycles_current_cycle check (current_cycle > 0)
);
