alter table captcha_challenges
    drop constraint fk_captcha_challenges_automation_action;

drop index idx_captcha_challenges_automation_action;

alter table captcha_challenges
    drop column automation_action_run_id;

drop table automation_module_quest_maps;
drop table automation_module_quests;
drop table automation_module_maps;
drop table automation_module_legacy_settings;
drop table automation_action_runs;
drop table automation_jobs;
drop table automation_module_configs;
drop table automation_profile_maps;
drop table automation_profiles;
