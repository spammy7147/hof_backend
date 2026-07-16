alter table adventure_daily_preflight_states
    add column in_flight_token varchar(36);

alter table adventure_daily_preflight_states
    add column in_flight_until timestamp with time zone;

alter table adventure_daily_preflight_states
    add constraint ck_adventure_daily_preflight_states_in_flight
        check (case
            when in_flight_token is null and in_flight_until is null then true
            when in_flight_token is not null and in_flight_until is not null then true
            else false
        end);

create index idx_adventure_daily_preflight_states_in_flight
    on adventure_daily_preflight_states (in_flight_until, account_id);
