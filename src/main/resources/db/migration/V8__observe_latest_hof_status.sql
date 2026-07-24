create table latest_hof_status (
    id bigserial,
    account_id bigint not null,
    player_name varchar(255) not null,
    funds bigint not null,
    time_current integer not null,
    time_max integer not null,
    work varchar(255) not null,
    auction varchar(255) not null,
    observed_at timestamp with time zone not null,
    constraint pk_latest_hof_status primary key (id),
    constraint fk_latest_hof_status_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_latest_hof_status_account unique (account_id)
);
