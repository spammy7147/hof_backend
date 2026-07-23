alter table party_presets add column display_order integer;

update party_presets target
set display_order = cast((
    select count(*)
    from party_presets preceding
    where preceding.account_id = target.account_id
      and (
          preceding.updated_at > target.updated_at
          or (preceding.updated_at = target.updated_at and preceding.id > target.id)
      )
) as integer);

alter table party_presets alter column display_order set not null;

alter table party_presets
    add constraint ck_party_presets_display_order check (display_order >= 0);

create index idx_party_presets_account_order
    on party_presets (account_id, display_order, id);
