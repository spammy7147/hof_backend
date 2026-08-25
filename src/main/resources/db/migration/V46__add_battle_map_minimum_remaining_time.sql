alter table automation_entries
    add column minimum_remaining_time integer;

alter table automation_entries
    add constraint ck_automation_entries_minimum_remaining_time
        check (
            minimum_remaining_time is null
            or (
                automation_type = 'BATTLE_MAP'
                and minimum_remaining_time between 1 and 2147483347
            )
        );
