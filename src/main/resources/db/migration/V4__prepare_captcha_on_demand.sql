delete from captcha_form_fields
where challenge_id in (
    select id
    from captcha_challenges
    where status = 'PENDING'
);

update captcha_challenges
set status = 'DETECTED',
    image_url = null,
    answer = null,
    answered_at = null,
    submit_url = null,
    submit_method = 'POST',
    answer_field_name = 'captcha'
where status = 'PENDING';

alter table captcha_challenges
    add column preparation_version integer not null default 0;

alter table captcha_challenges
    add constraint ck_captcha_challenges_preparation_version
        check (preparation_version >= 0);
