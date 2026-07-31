create table town_feature_locations (
    feature_id varchar(50),
    href varchar(500),
    observed_at timestamp with time zone,
    constraint pk_town_feature_locations primary key (feature_id),
    constraint ck_town_feature_locations_public_menu
        check (href ~ '^[?]menu=[A-Za-z0-9_-]{1,80}$'),
    constraint ck_town_feature_locations_observation_pair
        check ((href is null and observed_at is null) or (href is not null and observed_at is not null))
);

insert into town_feature_locations (feature_id)
values
    ('FISHING'), ('FISHING_EXCHANGE'), ('REST_ROOM'),
    ('GENERAL_STORE'), ('SUNDRIES_STORE'), ('DARK_STORE'), ('SELL'), ('COMBINE'), ('AUCTION'), ('AUCTION_MARKET'),
    ('COLOSSEUM_BATTLE'), ('COLOSSEUM_EXCHANGE'),
    ('ADVENTURE_AGENCY'), ('TALENT_AGENCY'),
    ('HOME_MANAGEMENT'), ('WORKBASE'),
    ('REFINE_WORKSHOP'), ('CREATE_WORKSHOP'), ('VETERAN_SMITHY'),
    ('EMBLEM_SHOP'), ('EVENT_SHOP'), ('SEWING_SHOP'), ('LEGACY_SHOP'), ('ANN_SHOP'),
    ('CARD_IDENTIFY'), ('CARD_UPGRADE'), ('CARD_CHANGE'), ('CARD_SELL'), ('SOUL_ECHO'),
    ('ORB_EXCHANGE'), ('STASH'), ('RAID_INFO'), ('PANTHEON');
