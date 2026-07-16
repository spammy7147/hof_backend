create table adventure_daily_preflight_states (
    id bigserial,
    account_id bigint not null,
    refresh_date date not null,
    failed_attempts integer not null,
    next_attempt_at timestamp with time zone,
    stop_reason varchar(30),
    updated_at timestamp with time zone not null,
    constraint pk_adventure_daily_preflight_states primary key (id),
    constraint fk_adventure_daily_preflight_states_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_adventure_daily_preflight_states_account unique (account_id),
    constraint ck_adventure_daily_preflight_states_attempts check (failed_attempts >= 0)
);

create index idx_adventure_daily_preflight_states_next_attempt
    on adventure_daily_preflight_states (next_attempt_at, account_id);
