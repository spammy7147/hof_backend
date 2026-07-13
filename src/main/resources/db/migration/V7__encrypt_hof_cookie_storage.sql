-- AES-GCM ciphertext is longer than the original cookie value because it includes a nonce and tag.
alter table hof_cookies alter column cookie_value type text;
