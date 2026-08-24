alter table typed_automation_action_runs
    add column entry_display_name varchar(100);

alter table automation_decision_events
    add column entry_display_name varchar(100);

update typed_automation_action_runs action
set entry_display_name = (
    select entry.display_name
    from automation_entries entry
    where entry.id = action.automation_entry_id
)
where action.automation_entry_id is not null;

update automation_decision_events event
set entry_display_name = (
    select entry.display_name
    from automation_entries entry
    where entry.id = event.automation_entry_id
)
where event.automation_entry_id is not null;
