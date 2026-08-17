alter table character_stats
    add column status_points integer;

alter table character_stats
    add column skill_points integer;

alter table character_stats
    add constraint ck_character_stats_status_points
        check (status_points is null or status_points >= 0);

alter table character_stats
    add constraint ck_character_stats_skill_points
        check (skill_points is null or skill_points >= 0);
