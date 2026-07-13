create table automation_outbox (
    id bigserial,
    event_id varchar(80) not null,
    account_id bigint not null,
    topic varchar(120) not null,
    event_key varchar(80) not null,
    payload text not null,
    created_at timestamp with time zone not null,
    available_at timestamp with time zone not null,
    published_at timestamp with time zone,
    constraint pk_automation_outbox primary key (id),
    constraint uk_automation_outbox_event_id unique (event_id),
    constraint fk_automation_outbox_account foreign key (account_id)
        references hof_accounts (id) on delete cascade
);

create index idx_automation_outbox_unpublished
    on automation_outbox (published_at, available_at, id);

create table automation_consumed_events (
    event_id varchar(80) not null,
    consumed_at timestamp with time zone not null,
    constraint pk_automation_consumed_events primary key (event_id)
);

create table account_automation_leases (
    account_id bigint not null,
    owner_id varchar(120) not null,
    lease_until timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_account_automation_leases primary key (account_id),
    constraint fk_account_automation_leases_account foreign key (account_id)
        references hof_accounts (id) on delete cascade
);

create index idx_account_automation_leases_until
    on account_automation_leases (lease_until, account_id);
