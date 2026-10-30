alter table automation_presets add column revision bigint not null default 0;
alter table hof_accounts add column applied_automation_preset_id bigint;
alter table hof_accounts add constraint fk_account_applied_automation_preset
    foreign key (applied_automation_preset_id) references automation_presets(id) on delete set null;
