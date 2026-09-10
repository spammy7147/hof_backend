alter table automation_decision_cycles add column next_event_sequence integer not null default 0;

update automation_decision_cycles
set next_event_sequence = (
    select coalesce(max(e.sequence_no), -1) + 1
    from automation_decision_events e
    where e.decision_cycle_id = automation_decision_cycles.id
);

alter table automation_decision_cycles add constraint ck_automation_history_next_sequence check (next_event_sequence >= 0);
