create table town_feature_locations (
    feature_id varchar(50),
    href varchar(500) not null,
    observed_at timestamp with time zone not null,
    constraint pk_town_feature_locations primary key (feature_id),
    constraint ck_town_feature_locations_public_menu
        check (href ~ '^[?]menu=[A-Za-z0-9_-]{1,80}$')
);
