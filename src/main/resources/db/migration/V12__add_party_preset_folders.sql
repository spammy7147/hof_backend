create table party_preset_folders (
    id bigserial not null,
    account_id bigint not null,
    parent_folder_id bigint,
    name varchar(100) not null,
    display_order integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_party_preset_folders primary key (id),
    constraint fk_party_preset_folders_account foreign key (account_id) references hof_accounts (id) on delete cascade,
    constraint fk_party_preset_folders_parent foreign key (parent_folder_id) references party_preset_folders (id) on delete cascade,
    constraint ck_party_preset_folders_display_order check (display_order >= 0)
);

create index idx_party_preset_folders_account_parent_order
    on party_preset_folders (account_id, parent_folder_id, display_order, id);

alter table party_presets add column folder_id bigint;

alter table party_presets add constraint fk_party_presets_folder
    foreign key (folder_id) references party_preset_folders (id) on delete set null;

drop index if exists idx_party_presets_account_order;

create index idx_party_presets_account_folder_order
    on party_presets (account_id, folder_id, display_order, id);
