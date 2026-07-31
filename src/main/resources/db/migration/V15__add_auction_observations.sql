create table auction_observation (
    observation_key varchar(128) not null,
    listing_id varchar(120),
    observation_kind varchar(20) not null,
    item_key varchar(128) not null,
    item_name varchar(300) not null,
    item_type varchar(100),
    quantity integer not null,
    total_price bigint not null,
    unit_price bigint not null,
    observed_at timestamp with time zone not null,
    last_seen_at timestamp with time zone not null,
    constraint pk_auction_observation primary key (observation_key),
    constraint ck_auction_observation_kind check (observation_kind in ('CURRENT', 'SOLD')),
    constraint ck_auction_observation_quantity check (quantity > 0),
    constraint ck_auction_observation_prices check (total_price >= 0 and unit_price >= 0)
);

create index idx_auction_observation_market
    on auction_observation (item_key, observation_kind, observed_at);

insert into town_global_job_lease (job_key) values ('AUCTION_HOURLY');
