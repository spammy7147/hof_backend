create table captcha_pass_maintenance (
    id bigserial,
    account_id bigint not null,
    enabled boolean not null default true,
    auth_suspended boolean not null default false,
    pass_state varchar(20) not null default 'UNKNOWN',
    remaining_seconds integer,
    valid_until timestamp with time zone,
    observed_at timestamp with time zone,
    next_refresh_at timestamp with time zone,
    last_attempt_at timestamp with time zone,
    last_result varchar(40),
    retry_count integer not null default 0,
    lease_token varchar(100),
    lease_until timestamp with time zone,
    run_phase varchar(20),
    manual_challenge_id bigint,
    notification_key varchar(100),
    updated_at timestamp with time zone not null,
    constraint pk_captcha_pass_maintenance primary key (id),
    constraint fk_captcha_pass_maintenance_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint fk_captcha_pass_maintenance_challenge foreign key (manual_challenge_id)
        references captcha_challenges (id) on delete set null,
    constraint uk_captcha_pass_maintenance_account unique (account_id),
    constraint ck_captcha_pass_maintenance_remaining check (remaining_seconds is null or remaining_seconds >= 0),
    constraint ck_captcha_pass_maintenance_retry check (retry_count >= 0)
);

create index idx_captcha_pass_maintenance_due
    on captcha_pass_maintenance (enabled, next_refresh_at, lease_until, account_id);

insert into captcha_pass_maintenance (
    account_id,
    enabled,
    auth_suspended,
    pass_state,
    next_refresh_at,
    updated_at
)
select
    id,
    true,
    case when exists (
        select 1
        from refresh_tokens rt
        where rt.account_id = hof_accounts.id
          and rt.expires_at > current_timestamp
          and rt.rotated_at is null
          and rt.revoked_at is null
    ) then false else true end,
    'UNKNOWN',
    case when exists (
        select 1
        from refresh_tokens rt
        where rt.account_id = hof_accounts.id
          and rt.expires_at > current_timestamp
          and rt.rotated_at is null
          and rt.revoked_at is null
    ) then current_timestamp + (mod(id, 300) * interval '1' second) else null end,
    current_timestamp
from hof_accounts;
