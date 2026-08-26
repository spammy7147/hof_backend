alter table typed_automation_runtime_states
    add column auth_suspended boolean not null default false;

alter table typed_automation_runtime_states
    add column resume_after_auth boolean not null default false;

create table account_auth_execution_states (
    account_id bigint,
    suspended boolean not null default false,
    suspended_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    version bigint,
    constraint pk_account_auth_execution_states primary key (account_id),
    constraint fk_account_auth_execution_states_account foreign key (account_id)
        references hof_accounts (id) on delete cascade
);

insert into account_auth_execution_states (account_id, suspended, suspended_at, updated_at, version)
select
    id,
    false,
    null,
    current_timestamp,
    0
from hof_accounts;

create index idx_account_auth_execution_active
    on account_auth_execution_states (suspended, account_id);
