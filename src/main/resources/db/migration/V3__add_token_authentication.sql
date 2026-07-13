create table refresh_tokens (
    id bigserial,
    account_id bigint not null,
    token_hash varchar(64) not null,
    family_id varchar(36) not null,
    client_type varchar(20) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    rotated_at timestamp with time zone,
    revoked_at timestamp with time zone,
    constraint pk_refresh_tokens primary key (id),
    constraint fk_refresh_tokens_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_refresh_tokens_token_hash unique (token_hash)
);

create index idx_refresh_tokens_family_created
    on refresh_tokens (family_id, created_at, id);

create index idx_refresh_tokens_account_active
    on refresh_tokens (account_id, revoked_at, expires_at, id);
