alter table captcha_challenges
    add column challenge_kind varchar(20) not null default 'CAPTCHA';

update captcha_challenges
set challenge_kind = 'VIGILANTE_PASS'
where prompt like '%통행증%'
   or answer_field_name = 'AnswerV';

alter table captcha_challenges
    add constraint ck_captcha_challenges_kind
        check (position(',' || challenge_kind || ',' in ',CAPTCHA,VIGILANTE_PASS,') > 0);
