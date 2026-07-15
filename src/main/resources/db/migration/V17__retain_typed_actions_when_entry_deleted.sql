alter table typed_automation_action_runs drop constraint fk_typed_action_entry;
alter table typed_automation_action_runs alter column automation_entry_id drop not null;
alter table typed_automation_action_runs add constraint fk_typed_action_entry
    foreign key (automation_entry_id) references automation_entries(id) on delete set null;
