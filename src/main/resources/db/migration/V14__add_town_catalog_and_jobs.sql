create table shop_catalog_item (
    shop_id varchar(20) not null,
    item_key varchar(200) not null,
    name varchar(300) not null,
    item_type varchar(100),
    description text,
    price bigint not null,
    active boolean not null,
    last_seen_at timestamp with time zone not null,
    constraint pk_shop_catalog_item primary key (shop_id, item_key),
    constraint ck_shop_catalog_item_shop check (shop_id in ('GENERAL', 'SUNDRIES', 'DARK')),
    constraint ck_shop_catalog_item_price check (price >= 0)
);

create index idx_shop_catalog_item_active_name
    on shop_catalog_item (shop_id, active, name, item_key);

create table town_global_job_lease (
    job_key varchar(100) not null,
    lease_owner varchar(100),
    lease_until timestamp with time zone,
    last_attempt_at timestamp with time zone,
    last_success_at timestamp with time zone,
    constraint pk_town_global_job_lease primary key (job_key),
    constraint ck_town_global_job_lease_pair
        check ((lease_owner is null and lease_until is null) or (lease_owner is not null and lease_until is not null))
);

insert into town_global_job_lease (job_key)
values ('SHOP_GENERAL'), ('SHOP_SUNDRIES'), ('SHOP_DARK');
