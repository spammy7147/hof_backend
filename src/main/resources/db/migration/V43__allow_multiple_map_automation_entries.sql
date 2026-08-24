alter table automation_entries
    drop constraint uk_automation_entries_account_type;

alter table automation_entries
    add column singleton_type_marker varchar(30);

update automation_entries
set singleton_type_marker = automation_type
where automation_type not in ('BATTLE_MAP', 'ADVENTURE_MAP');

alter table automation_entries
    add column display_name varchar(100);

alter table automation_entries
    add column settings_revision bigint not null default 0;

alter table automation_entries
    add constraint uk_automation_entries_account_singleton
        unique (account_id, singleton_type_marker);

alter table automation_entries
    add constraint ck_automation_entries_singleton_marker
        check (
            case automation_type
                when 'BATTLE_MAP' then singleton_type_marker is null
                when 'ADVENTURE_MAP' then singleton_type_marker is null
                else singleton_type_marker is not null and singleton_type_marker = automation_type
            end
        );
