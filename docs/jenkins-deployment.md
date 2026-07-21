# HOF 백엔드 Jenkins 배포 설정

## 구성

- Jenkins 컨트롤러: `192.168.50.200` (`jenkins.spammy.app`)
- Jenkins 빌드 워커: `192.168.50.201`, 라벨 `spammy-builder`
- Docker 배포 서버: `spammy@192.168.50.202`
- 서비스 포트: `192.168.50.202:8080`
- Git 저장소: `https://github.com/spammy7147/hof_backend.git`, `master` 브랜치

201은 Git Checkout, Gradle 테스트, Docker 이미지 빌드를 수행한다. 완성된 이미지는 Docker Hub를 거치지 않고 `docker save | gzip | ssh`로 202에 전달한다. 202는 이미지를 불러온 뒤 기존 `hof-backend` 컨테이너를 교체한다.

## 1. 필요한 Jenkins 플러그인 확인

`Jenkins 관리(Manage Jenkins) → Plugins → Installed plugins`에서 다음 플러그인을 확인한다.

- Pipeline
- Git
- Credentials Binding
- SSH Agent

없다면 `Available plugins`에서 설치한다. SSH Agent 플러그인은 Pipeline의 `sshagent` 단계에서 SSH 개인 키를 사용하기 위해 필요하다.

## 2. 201 빌드 워커 확인

`Jenkins 관리 → Nodes`에서 201 워커를 열고 라벨에 `spammy-builder`가 정확히 등록되어 있는지 확인한다. 워커에서 Jenkins 작업을 실행하는 OS 계정으로 다음 명령이 성공해야 한다.

```bash
git --version
java -version
docker --version
docker info
command -v bash
command -v ssh-agent
gzip --version
ssh -V
```

Java는 21 이상이어야 한다. `docker info`가 권한 오류로 실패하면 Jenkins 워커 계정에 Docker 실행 권한을 부여한 뒤 워커 세션을 다시 시작한다.

## 3. Jenkins 전용 SSH 키 생성

관리자 PC처럼 안전한 위치에서 배포 전용 키를 생성한다. 기존 개인 SSH 키를 재사용하지 않는다.

```bash
ssh-keygen -t ed25519 -C 'jenkins-hof-deploy' -f ./jenkins-hof-deploy
```

생성된 공개 키 `jenkins-hof-deploy.pub`만 202의 `spammy` 계정에 등록한다.

```bash
ssh-copy-id -i ./jenkins-hof-deploy.pub spammy@192.168.50.202
```

202에서 `spammy` 계정이 암호 입력 없이 Docker를 실행할 수 있어야 한다.

```bash
ssh spammy@192.168.50.202 'docker info >/dev/null && echo OK'
```

Docker 권한을 새로 부여해야 한다면 202에서 관리자가 다음 작업을 수행하고 `spammy` 계정으로 다시 로그인한다.

```bash
sudo usermod -aG docker spammy
```

SSH 키 로그인이 확인된 뒤, 대화 중 노출된 기존 서버 로그인 비밀번호를 변경한다.

## 4. 202 SSH 호스트 키 등록

파이프라인은 `StrictHostKeyChecking=no`를 사용하지 않는다. 202의 콘솔에서 실제 Ed25519 호스트 키 fingerprint를 확인한다.

```bash
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

201에서 Jenkins 워커를 실행하는 OS 계정으로 호스트 키를 조회해 fingerprint가 위 결과와 같은지 확인한다.

```bash
mkdir -p ~/.ssh
chmod 700 ~/.ssh
ssh-keyscan -t ed25519 192.168.50.202 > /tmp/hof-202-host-key
ssh-keygen -lf /tmp/hof-202-host-key
cat /tmp/hof-202-host-key >> ~/.ssh/known_hosts
chmod 600 ~/.ssh/known_hosts
rm /tmp/hof-202-host-key
```

반드시 fingerprint를 비교한 후 `known_hosts`에 추가한다.

## 5. Jenkins Credentials 등록

`Jenkins 관리 → Credentials → System → Global credentials (unrestricted) → Add Credentials`로 이동한다. Folder 안에서 Job을 운영한다면 접근 범위를 줄이기 위해 Folder Credentials에 등록해도 된다.

### 5.1 GitHub 자격증명

비공개 저장소일 때 등록한다.

- Kind: `Username with password`
- Username: GitHub 사용자명
- Password: GitHub Personal Access Token
- ID: `HOF-github-token`
- Description: `HOF backend GitHub checkout`

Pipeline은 이 ID를 요구하므로 공개 저장소라도 현재 Jenkinsfile을 그대로 사용하려면 해당 ID가 있어야 한다.

### 5.2 202 SSH 개인 키

- Kind: `SSH Username with private key`
- Username: `spammy`
- Private Key: `Enter directly`
- Key: `jenkins-hof-deploy` 개인 키 내용
- Passphrase: 키 생성 시 설정했다면 입력
- ID: `hof-deploy-ssh`
- Description: `HOF deploy SSH key for 192.168.50.202`

서버 로그인 비밀번호를 Jenkinsfile에 넣지 않는다.

### 5.3 운영 환경변수 파일

운영용 파일을 로컬에서 만든다. 다음은 필요한 변수 이름의 예시이며 실제 값은 Git에 저장하지 않는다.

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://DATABASE_HOST:5432/hof
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
SPRING_DATASOURCE_USERNAME=REPLACE_ME
SPRING_DATASOURCE_PASSWORD=REPLACE_ME
SPRING_KAFKA_BOOTSTRAP_SERVERS=KAFKA_HOST:9092
HOF_AUTH_JWT_SECRET=REPLACE_WITH_A_LONG_RANDOM_SECRET
HOF_AUTH_CREDENTIAL_ENCRYPTION_KEY=REPLACE_ME
HOF_AUTH_COOKIE_ENCRYPTION_KEY=REPLACE_ME
HOF_AUTH_ALLOWED_ORIGINS=https://YOUR_FRONTEND_DOMAIN
HOF_AUTH_REQUIRE_HTTPS=true
HOF_AUTH_REFRESH_COOKIE_SECURE=true
```

Jenkins에 다음과 같이 업로드한다.

- Kind: `Secret file`
- File: 위 운영 환경변수 파일
- ID: `hof-backend-env`
- Description: `HOF backend production environment`

Pipeline은 이 파일을 빌드 중에만 바인딩하고 202의 빌드별 임시 경로로 복사한다. 컨테이너가 생성되면 원격 임시 파일을 삭제한다.

## 6. Jenkins Pipeline Job 설정

현재 화면의 `app.spammy.hof → Configure → Pipeline`으로 이동한다.

1. `Definition`을 `Pipeline script`에서 `Pipeline script from SCM`으로 변경한다.
2. `SCM`은 `Git`을 선택한다.
3. `Repository URL`에 `https://github.com/spammy7147/hof_backend.git`을 입력한다.
4. `Credentials`에서 `HOF-github-token`을 선택한다.
5. `Branches to build → Branch Specifier`에 `*/master`를 입력한다.
6. `Script Path`에 `Jenkinsfile`을 입력한다.
7. `Lightweight checkout`은 활성화해도 된다.
8. `Save`를 누른다.

Groovy 코드를 화면의 `Script` 입력란에 직접 붙여 넣지 않는다. 저장소의 Jenkinsfile을 사용해야 변경 이력과 코드 검토가 유지된다.

## 7. 첫 수동 배포

Job 화면에서 `Build Now`를 누르고 다음 단계가 차례로 성공하는지 확인한다.

```text
Checkout → Preflight → Test → Build Image → Transfer Image → Deploy
```

202에서 결과를 확인한다.

```bash
docker ps --filter name=hof-backend
docker inspect hof-backend --format '{{.Config.Image}}'
curl -fsS http://127.0.0.1:8080/actuator/health
ls /tmp/hof-backend-env-*
```

정상 결과는 다음과 같다.

- `hof-backend` 컨테이너가 실행 중이다.
- 이미지 태그가 `<Jenkins 빌드 번호>-<Git 커밋>` 형태다.
- Actuator 응답에 `"status":"UP"`가 포함된다.
- `/tmp/hof-backend-env-*` 파일이 남아 있지 않다.

새 컨테이너가 60초 안에 정상 상태가 되지 않으면 Pipeline은 최근 로그 100줄을 출력하고 직전 컨테이너를 복원한다. 첫 배포처럼 직전 컨테이너가 없다면 실패한 새 컨테이너만 제거한다.

## 8. 자동 빌드 활성화

첫 수동 배포가 성공한 뒤 GitHub webhook 또는 `Poll SCM`을 설정한다. 처음부터 자동 실행을 켜면 SSH 키, 환경변수, 포트 또는 Docker 권한 설정 오류가 반복 배포될 수 있으므로 수동 검증 후 활성화한다.

## Flyway 주의사항

현재 작업 트리에 있는 `V1` 단일 기준선 통합 변경은 빈 데이터베이스에만 적용할 수 있다. 기존에 Flyway `V1`부터 `V22`까지 적용된 데이터베이스에 이 변경을 배포하면 checksum 및 누락 migration 검증 오류가 발생한다. 기존 DB를 사용할 경우 이 migration 통합 변경을 커밋하거나 배포하지 말고 기존 append-only migration 파일을 유지해야 한다.
