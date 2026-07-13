# HOF backend

## 통합 자동화 운영

`docker compose up -d --build`로 PostgreSQL, Kafka KRaft, backend를 시작한다. 다음 환경 변수가 필요하다.

- `POSTGRES_PASSWORD`
- `HOF_AUTH_JWT_SECRET`
- `HOF_CREDENTIAL_ENCRYPTION_KEY`
- `FIREBASE_SERVICE_ACCOUNT_PATH` (기본값: `./secrets/firebase-service-account.json`)

Backend 또는 Kafka가 재시작되면 DB recovery scan이 `PENDING`, `RUNNING`, 실행 시각이 지난 job을 다시 Kafka에 넣는다. `WAITING_CAPTCHA`와 `PAUSED`는 사용자의 인증 또는 재개 전까지 실행하지 않는다. DB가 진행 상태의 원본이고 Kafka는 계정을 깨우는 용도이므로 중복 메시지는 계정 lease와 action checkpoint로 직렬화한다.
