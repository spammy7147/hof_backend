alter table battle_automation_maps
    add column account_id bigint;

update battle_automation_maps
set account_id = (
    select entry.account_id
    from automation_entries entry
    where entry.id = battle_automation_maps.automation_entry_id
);

alter table battle_automation_maps
    alter column account_id set not null;

alter table automation_entries
    add constraint uk_automation_entries_id_account
        unique (id, account_id);

alter table battle_automation_maps
    add constraint fk_battle_automation_maps_account
        foreign key (account_id) references hof_accounts(id) on delete cascade;

alter table battle_automation_maps
    drop constraint fk_battle_automation_maps_entry;

alter table battle_automation_maps
    add constraint fk_battle_automation_maps_entry_account
        foreign key (automation_entry_id, account_id) references automation_entries(id, account_id) on delete cascade;

alter table battle_automation_maps
    add constraint uk_battle_automation_maps_account_map
        unique (account_id, category_id, map_code);

alter table adventure_automation_maps
    add column account_id bigint;

update adventure_automation_maps
set account_id = (
    select entry.account_id
    from automation_entries entry
    where entry.id = adventure_automation_maps.automation_entry_id
);

alter table adventure_automation_maps
    alter column account_id set not null;

alter table adventure_automation_maps
    add constraint fk_adventure_automation_maps_account
        foreign key (account_id) references hof_accounts(id) on delete cascade;

alter table adventure_automation_maps
    drop constraint fk_adventure_automation_maps_entry;

alter table adventure_automation_maps
    add constraint fk_adventure_automation_maps_entry_account
        foreign key (automation_entry_id, account_id) references automation_entries(id, account_id) on delete cascade;

alter table adventure_automation_maps
    add constraint uk_adventure_automation_maps_account_map
        unique (account_id, category_id, map_code);
