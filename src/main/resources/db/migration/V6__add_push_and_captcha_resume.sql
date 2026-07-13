create table device_push_targets (
    id bigserial,
    account_id bigint not null,
    platform varchar(20) not null,
    target_type varchar(20) not null,
    installation_id varchar(160) not null,
    target_value text not null,
    active boolean not null,
    last_seen_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    constraint pk_device_push_targets primary key (id),
    constraint fk_device_push_targets_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_device_push_targets_account_installation unique (account_id, installation_id)
);

create index idx_device_push_targets_account_active
    on device_push_targets (account_id, active, last_seen_at, id);

alter table captcha_challenges
    add column automation_action_run_id bigint;

alter table captcha_challenges
    add constraint fk_captcha_challenges_automation_action
    foreign key (automation_action_run_id)
    references automation_action_runs (id) on delete set null;

create index idx_captcha_challenges_automation_action
    on captcha_challenges (automation_action_run_id);
