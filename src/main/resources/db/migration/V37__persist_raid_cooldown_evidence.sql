create table raid_cooldown_evidence_cases (
    id varchar(64) not null,
    account_id bigint not null,
    association_mode varchar(40) not null,
    reason_code varchar(100) not null,
    timer_shape varchar(40) not null,
    candidate_seconds varchar(255) not null,
    candidate_count integer not null,
    map_count integer not null,
    active_joined_raid_count integer not null,
    dom_fingerprint varchar(64) not null,
    response_shape_fingerprint varchar(64) not null,
    policy_version varchar(80) not null,
    build_version varchar(80) not null,
    first_observed_at timestamp with time zone not null,
    last_observed_at timestamp with time zone not null,
    observation_count integer not null,
    expires_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint pk_raid_cooldown_evidence_cases primary key (id),
    constraint fk_raid_cooldown_evidence_account foreign key (account_id) references hof_accounts(id) on delete cascade,
    constraint uk_raid_cooldown_evidence_shape unique (
        account_id,
        association_mode,
        reason_code,
        dom_fingerprint,
        response_shape_fingerprint
    ),
    constraint ck_raid_cooldown_evidence_mode check (
        position(',' || association_mode || ',' in ',NONE,HOF_DIRECT,AMBIGUOUS,PARSE_FAILED,') > 0
    ),
    constraint ck_raid_cooldown_evidence_timer_shape check (
        position(',' || timer_shape || ',' in ',MARKER_UNPARSEABLE,SINGLE_POSITIVE_SECONDS,MULTIPLE_POSITIVE_SECONDS,NO_PARSED_SECONDS,') > 0
    ),
    constraint ck_raid_cooldown_evidence_counts check (
        candidate_count >= 0 and map_count >= 0 and active_joined_raid_count >= 0 and observation_count > 0
    ),
    constraint ck_raid_cooldown_evidence_fingerprints check (
        char_length(dom_fingerprint) = 64 and char_length(response_shape_fingerprint) = 64
    )
);

create index idx_raid_cooldown_evidence_expiry
    on raid_cooldown_evidence_cases (expires_at, id);
