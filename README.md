# CSRO · 여행봄

여행봄은 숙박 예약 위험 탐지, 객실 재확인, 상담 사건, 대체 숙소 처리를 지원한다. 프로젝트에는 Spring 업무 DB의 로컬 구성과 API-001 예약 상세 조회 코드가 있다. API-001의 실제 Bearer 인증은 연결 전이다. 상담 조회 시연은 [상담 화면 실행 절차](backend/docs/api/booking-reconfirmation-demo.md)를 참고한다. [API 구현 인덱스](backend/docs/api/README.md)

독립 ML 모듈의 과정 기반 더미 데이터 생성·LightGBM 학습·저장 모델 예측은 [ml/README.md](ml/README.md)를 참고한다. [API-023 HTTP 추론 서버](ml/docs/api/API-023_HANDOFF.md)는 저장한 LightGBM 모델로 `risk-features-0.2` 요청을 처리한다. 합성 관측 파일을 사용한 Spring 공통 집계·평가 저장·재확인 자동 배치는 [공통 위험 점검 경로](backend/docs/api/ML-RISK-COMMON-PATH.md)를 참고한다. 운영 관측 수집·상시 서비스와 배포는 후속 범위다.

## 로컬 환경

아래는 Windows PowerShell 기준 절차입니다. 상태와 응답은 예상 결과입니다.

### 1. 도구 준비와 저장소 내려받기

Git, JDK 21, Docker Desktop과 `docker compose` 명령이 필요합니다. 저장소의 Gradle Wrapper가 9.9.1을 사용하므로 Gradle을 따로 설치할 필요는 없습니다. 프로젝트 설정은 Spring Boot 4.0.9와 PostgreSQL 18.7을 사용합니다. [Gradle 설정](backend/build.gradle) · [Wrapper 버전](backend/gradle/wrapper/gradle-wrapper.properties) · [Compose 설정](compose.yaml)

```powershell
git clone https://github.com/csro-travel/CSRO.git
Set-Location .\CSRO
java -version
docker compose version
```

이미 내려받았다면 기존 `CSRO` 저장소 루트에서 아래 단계를 시작합니다.

### 2. 저장소 루트에서 로컬 DB 설정

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
notepad .env
```

새로 복사했다면 `POSTGRES_PASSWORD`의 자리표시자를 로컬 DB용 비밀번호로 바꿉니다. 기존 DB 볼륨을 재사용한다면 현재 DB 계정에 설정된 비밀번호를 사용합니다. `.env`는 Git에서 무시되며, 실제 값은 로그·문서·커밋에 남기지 않습니다.

- Compose는 루트 `.env`를 보간해 PostgreSQL 컨테이너의 `POSTGRES_PASSWORD`로 전달합니다. Spring은 같은 파일을 Java properties로 읽어 `yb` 계정의 접속 비밀번호로 사용합니다.
- 두 파서에서 같은 값이 되도록 `POSTGRES_PASSWORD=값` 한 줄로 작성합니다. 로컬에서 새로 정할 때는 충분히 길고 무작위인 영문·숫자·`_`·`-` 조합을 사용합니다. 따옴표, 공백, 인라인 주석, `$` 보간, 역슬래시 이스케이프는 두 파서에서 다르게 해석될 수 있습니다.
- 셸에 `POSTGRES_PASSWORD`가 설정돼 있으면 Compose에서 `.env`보다 우선하고, Spring도 실행 환경변수를 파일 값보다 우선합니다. `.env`를 사용할 때는 아래 확인 결과가 `False`인 터미널에서 실행하고 IDE 실행 구성의 환경변수도 확인합니다.

```powershell
Test-Path Env:POSTGRES_PASSWORD
docker compose config --quiet
netstat -ano -p tcp | Select-String ':5432\s+.*LISTENING'
```

구성 검사 성공 시 출력 없이 종료합니다. 포트 조회에 리스너가 나오면 PID와 `docker compose ps`를 확인해 이 프로젝트의 기존 컨테이너인지 구분합니다. 다른 서비스가 포트를 사용 중이면 해당 포트를 해제할 수 있는지 확인한 뒤 계속합니다. 현재 설정은 호스트의 `127.0.0.1:5432`에만 바인딩합니다.

### 3. 저장소 루트에서 PostgreSQL 시작

```powershell
docker compose up -d
docker compose ps
docker compose port postgres 5432
```

예상 상태는 `postgres`의 `healthy`, 예상 포트는 `127.0.0.1:5432`입니다. 같은 Compose 프로젝트의 `postgres_data` 볼륨을 다시 사용하면 기존 데이터가 유지됩니다. 빈 볼륨에서는 이미지가 `yeohaengbom` DB와 `yb` 사용자를 초기화합니다. 기존 볼륨에서는 이전 DB와 사용자를 그대로 열며, `.env`를 편집해도 DB에 저장된 비밀번호가 자동 변경되지 않습니다. `healthy`는 `pg_isready`로 접속을 받을 준비가 됐는지 확인한 상태입니다. Spring의 실제 JDBC 인증과 Flyway 성공은 다음 단계에서 확인합니다.

### 4. `backend`에서 Spring Boot 실행

저장소 루트에서 시작합니다. IDE에서 실행할 때도 작업 디렉터리를 `<저장소 루트>/backend`, 활성 프로필을 `local`로 지정합니다. [Spring local 설정](backend/src/main/resources/application-local.properties)

```powershell
Set-Location .\backend
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

`file:../.env[.properties]`의 `..`는 Spring 프로세스의 작업 디렉터리 기준입니다. `backend`에서 실행하면 루트 `.env`를 읽고, DataSource는 `127.0.0.1:5432/yeohaengbom`에 연결합니다. 시작 시 Flyway는 공통 [V1](backend/src/main/resources/db/migration/V1__initial_business_schema.sql)을 적용하고, `local` 프로필에서는 [YB-001 시드](backend/src/main/resources/db/local/R__seed_yb001.sql)도 적용합니다. 기존 DB에서는 적용된 V1을 검증하고 반복 시드는 미적용이거나 내용이 달라졌을 때 적용합니다. 예상 결과는 Flyway 적용·검증과 Spring 부팅 성공입니다.

Spring을 실행한 채 별도 PowerShell에서 확인합니다.

```powershell
Invoke-RestMethod http://localhost:8080/hello
```

예상 응답은 `Hello, CSRO!`입니다.

### 5. `backend`에서 기존 테스트 실행

테스트용 로컬 DB는 켜 둡니다. 별도 PowerShell을 저장소 루트에서 열어 실행합니다.

```powershell
Set-Location .\backend
.\gradlew.bat test
```

예상 결과는 `BUILD SUCCESSFUL`이며 상세 결과는 `backend/build/reports/tests/test/index.html`에서 확인합니다. 통합 테스트는 `local` 프로필의 PostgreSQL을 사용합니다.

### 6. 볼륨을 유지하며 종료

Spring을 실행한 터미널에서 `Ctrl+C`로 종료한 뒤, 저장소 루트 터미널에서 실행합니다.

```powershell
docker compose down
```

이 명령은 이름 있는 볼륨을 보존합니다. 같은 저장소 루트에서 `docker compose up -d`로 다시 실행합니다. 종료에 `-v` 옵션을 붙이면 볼륨이 삭제되므로 데이터 보존이 필요한 경우 사용하지 않습니다.

### 흔한 오류

| 증상 | 확인할 사항 |
| --- | --- |
| Compose가 비밀번호 필수 오류를 반환함 | 저장소 루트인지, `.env`에 값이 채워졌는지, 셸의 동명 변수가 의도치 않게 설정돼 있는지 확인합니다. |
| Spring이 `../.env`를 찾지 못함 | PowerShell·IDE의 작업 디렉터리를 `backend`로 맞추고 `local` 프로필을 확인합니다. |
| 5432 바인딩 실패 | 포트 조회로 점유 PID와 기존 Compose 컨테이너를 확인합니다. 다른 서비스와 충돌하면 팀 설정을 임의로 바꾸기 전에 충돌 원인을 확인합니다. |
| PostgreSQL은 `healthy`인데 Spring 인증 실패 | 셸·IDE 환경변수 우선순위와 기존 볼륨의 DB 계정 비밀번호를 대조합니다. |
| Flyway 검증 실패 | 오류가 가리키는 버전과 로컬 마이그레이션을 확인합니다. 적용된 V1은 수정하거나 이름을 바꾸지 않으며 `repair`나 볼륨 초기화 전에 오류를 공유합니다. |

## Codex 문서 확인

저장소 루트를 작업 디렉터리로 새 Codex 세션을 연다. Codex는 세션 시작 시 사용자 전역 지침과 저장소 루트부터 작업 디렉터리까지의 지침을 탐색한다. 이 저장소의 기본 지침은 루트 [AGENTS.md](AGENTS.md)이며 ML 하위 작업에는 [ml/AGENTS.md](ml/AGENTS.md)도 적용한다. 같은 디렉터리에 `AGENTS.override.md`가 있으면 우선한다. [Codex 공식 탐색 규칙](https://learn.chatgpt.com/docs/agent-configuration/agents-md)

[업무](docs/business.md)·[API](docs/api.md)·[DB](docs/db.md) 문서는 일반 Markdown이다. Codex는 작업에 해당하는 문서와 원본을 `AGENTS.md`의 표에 따라 명시적으로 읽는다.

새 세션에서 아래를 입력하고, 루트 `AGENTS.md`가 적용 지침으로 표시되는지와 API 문서가 작업별 읽기 대상으로 선택되는지 확인한다.

> 파일 수정 없이 적용된 지침 파일의 경로를 알려줘. API-001의 DTO 응답 필드 변경 요청을 받았다고 가정하고, 수정 전에 읽을 파일과 원본, 승인 필요 여부를 알려줘.
