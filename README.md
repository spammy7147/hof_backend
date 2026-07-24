# HOF backend

## Runtime profile

- `dev`: 홈서버 PostgreSQL·Kafka·Redis 설정을 사용하지만 캡차 push event를 만들지 않는다.
- `prod`: 같은 인프라를 사용하며 캡차 push outbox, Firebase Admin과 push consumer를 활성화한다.

로컬 실행은 `SPRING_PROFILES_ACTIVE=dev`를 사용한다. Jenkins 운영 배포는 `SPRING_PROFILES_ACTIVE=prod`와
Secret file credential `hof-spammy-fcm`을 사용한다. Android 앱의 `google-services.json`은 FCM token을
받기 위한 앱 빌드 설정이고, Jenkins Secret file은 백엔드가 FCM을 보내기 위한 Admin SDK 자격증명이다.
두 JSON 파일을 서로 바꾸거나 저장소에 커밋하지 않는다.

## 통합 자동화 동작

Backend 또는 Kafka가 재시작되면 DB recovery scan이 `PENDING`, `RUNNING`, 실행 시각이 지난 job을 다시 Kafka에 넣는다. `WAITING_CAPTCHA`와 `PAUSED`는 사용자의 인증 또는 재개 전까지 실행하지 않는다. DB가 진행 상태의 원본이고 Kafka는 계정을 깨우는 용도이므로 중복 메시지는 계정 lease와 action checkpoint로 직렬화한다.

### 세션 기반 요청 절감

자동화는 퀘스트·전투맵·모험맵의 논리 작업을 DB 세션으로 유지한다. 재료나 쿨타임을 기다리는 대상은 `nextCheckAt` 전까지 HOF를 조회하지 않으며, 관련 전리품 신호가 들어오거나 기본 30분 보정 시각이 되면 해당 대상만 다시 확인한다. Redis는 정확성의 원본이 아닌 만기 인덱스 가속 용도다.

- `HOF_AUTOMATION_REDIS_ENABLED=false` (기본값): Redis 연결 없이 DB 인덱스만 사용한다.
- `HOF_AUTOMATION_REDIS_ENABLED=true`: Spring Data Redis 접속 정보를 설정하며, 만기 시각을 `hof:automation:due` Sorted Set에 미러링한다. 반환 대상은 실행 전 DB에서 다시 검증한다.
- `HOF_AUTOMATION_RECONCILIATION_INTERVAL=30m`: 재료 및 알 수 없는 퀘스트 쿨타임의 전체 보정 간격이다.
- `HOF_AUTOMATION_RECONCILIATION_JITTER=2m`: 계정별 보정 요청이 한 시점에 몰리는 것을 줄이는 최대 지터다.
