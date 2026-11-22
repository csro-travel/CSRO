# 예약 재확인 시연과 상담사 조회 인계

YB-001의 공급사 계약 조회 → 호텔 회신 → 검증 근거 저장 → C-001과 최초 상담 업무 생성을 재현한다. Spring 구현 기준은 `ed563bb`, 가상 공급사·호텔 FastAPI 기준은 `1118cy`의 `9cef484`다. 날짜는 2026년 11월 시연 fixture다. PC 시계는 그대로 둔다.

## 로컬 시연

Windows PowerShell에서 JDK 21, Docker Desktop Linux Engine, Python 3.13을 사용한다. 아래 두 worktree 경로는 각 PC에 맞게 설정한다. 포트 8001과 25433이 이미 사용 중이면 기존 프로세스에 연결하거나 종료하지 않고 비어 있는 시연 환경을 준비한다.

### 1. 터미널 A에서 Mock 시작

```powershell
Set-Location 'C:/Users/whqja/CSRouter/CSRO-mock-1118cy/mock-server'
if ((git rev-parse --short HEAD) -ne '9cef484') { throw 'Mock 기준 커밋 불일치' }
if (-not (Test-Path ./.venv/Scripts/python.exe)) {
    py -3.13 -m venv .venv
    ./.venv/Scripts/python.exe -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Mock 패키지 설치 실패' }
}
if (Get-NetTCPConnection -State Listen -LocalPort 8001 -ErrorAction SilentlyContinue) {
    throw '8001 포트 사용 중'
}
$env:MOCK_SERVICE_TOKEN = 'csro-local-mock-token'
./.venv/Scripts/python.exe -B -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

공개 개발용 Mock 토큰을 사용한다. 터미널 B에서 `Invoke-RestMethod http://127.0.0.1:8001/healthz`를 실행하면 `status=ok`, `isSynthetic=true`를 확인할 수 있다.

### 2. 터미널 B에서 격리 PostgreSQL 시작 및 초기 상태 확인

기존 Compose DB 대신 loopback `25433`에 임시 PostgreSQL 18.7을 시작한다. 아래 세션 변수는 해당 PowerShell에서만 사용한다. 컨테이너에 기존 볼륨을 연결하지 않는다.

```powershell
Set-Location 'C:/Users/whqja/CSRouter/CSRO-booking-reconfirmation/backend'
if ($env:SPRING_FLYWAY_URL -or $env:SPRING_APPLICATION_JSON -or
    (Test-Path ./__reconfirmation_demo__.properties)) {
    throw '별도 Spring 설정이 없는 시연용 터미널에서 실행'
}
if (Get-NetTCPConnection -State Listen -LocalPort 25433 -ErrorAction SilentlyContinue) {
    throw '25433 포트 사용 중'
}
$demoName = 'csro-reconfirmation-demo-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$env:POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N')
$demoId = docker run --rm -d --name $demoName --tmpfs /var/lib/postgresql `
    -p 127.0.0.1:25433:5432 -e POSTGRES_USER=yb -e POSTGRES_DB=yeohaengbom_reconfirmation_test `
    -e POSTGRES_PASSWORD postgres:18.7
if ($LASTEXITCODE -ne 0 -or -not $demoId) { throw '격리 DB 시작 실패' }
docker port $demoId 5432/tcp
if ((docker port $demoId 5432/tcp) -ne '127.0.0.1:25433') { throw '격리 DB 포트 불일치' }
$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    docker exec $demoId pg_isready -h 127.0.0.1 -U yb -d yeohaengbom_reconfirmation_test
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw '격리 DB 준비 실패' }

$env:SPRING_CONFIG_LOCATION = 'optional:file:./__reconfirmation_demo__.properties'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:25433/yeohaengbom_reconfirmation_test'
$env:SPRING_DATASOURCE_USERNAME = 'yb'
$env:SPRING_DATASOURCE_PASSWORD = $env:POSTGRES_PASSWORD
$env:SPRING_FLYWAY_ENABLED = 'true'
$env:SPRING_FLYWAY_LOCATIONS = 'classpath:db/migration,classpath:db/local'
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'none'
$env:BOOKING_RECONFIRMATION_MOCK_BASEURL = 'http://127.0.0.1:8001'
$env:BOOKING_RECONFIRMATION_MOCK_SERVICETOKEN = 'csro-local-mock-token'
$env:BOOKING_RECONFIRMATION_EVIDENCE_TEST = 'false'
$env:BOOKING_RECONFIRMATION_LIVE_TEST = 'false'

./gradlew.bat --no-daemon test --tests '*BackendApplicationTests'
if ($LASTEXITCODE -ne 0) { throw 'Spring/Flyway 초기화 실패' }
```

`SPRING_CONFIG_LOCATION`의 선택적 경로는 의도적으로 존재하지 않는 파일이다. 기존 worktree의 `.env` 대신 위 세션의 격리 접속 정보를 사용한다. 컨텍스트 테스트의 `local` 프로필에서 공통 Flyway V1과 로컬 시드가 적용된다. 시드는 YB-001의 예약과 3박만 준비하며 YB-002는 아래 통합 테스트에서 준비한다.

### 3. 실행 전과 실행 후에 같은 읽기 전용 SQL 조회

터미널 B에 아래 블록을 먼저 붙여 넣는다. 테스트 수행 뒤에는 마지막 `$snapshotSql | docker exec …` 명령을 한 번 더 실행한다. 숫자 PK는 시연 및 조인 확인용이다. 호텔 근거는 숙박일별 주장과 조인되므로 같은 evidence_pk가 세 행에 표시된다.

```powershell
$snapshotSql = @'
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;

SELECT b.booking_code, b.supplier_status, h.hotel_code
FROM bookings b JOIN hotels h ON h.id = b.hotel_id
WHERE b.booking_code IN ('YB-001', 'YB-002')
ORDER BY b.booking_code;

SELECT b.booking_code, n.stay_date, n.fulfillment_status, n.fact_status,
       n.accepted_assertion_id, a.evidence_id AS accepted_evidence_id
FROM bookings b JOIN booking_nights n ON n.booking_id = b.id
LEFT JOIN evidence_nights a ON a.id = n.accepted_assertion_id AND a.booking_night_id = n.id
WHERE b.booking_code IN ('YB-001', 'YB-002')
ORDER BY b.booking_code, n.stay_date;

SELECT b.booking_code, e.id AS evidence_pk, e.source_type, e.provider_code,
       e.source_authenticated, e.occurred_at, e.received_at, e.raw_text IS NOT NULL AS response_saved,
       e.raw_payload ? 'requestJson' AS request_saved,
       n.stay_date, a.reported_status, a.review_status
FROM bookings b JOIN evidence_records e ON e.booking_id = b.id
LEFT JOIN evidence_nights a ON a.evidence_id = e.id AND a.booking_id = b.id
LEFT JOIN booking_nights n ON n.id = a.booking_night_id AND n.booking_id = b.id
WHERE b.booking_code IN ('YB-001', 'YB-002')
ORDER BY b.booking_code, e.id, n.stay_date;

SELECT b.booking_code, c.case_code, c.status, c.priority,
       t.id AS next_task_pk, t.task_type, t.status AS task_status, t.due_at
FROM bookings b JOIN cases c ON c.booking_id = b.id
LEFT JOIN LATERAL (
    SELECT id, task_type, status, due_at FROM case_tasks
    WHERE case_id = c.id AND booking_id = b.id AND status IN ('TODO', 'IN_PROGRESS')
    ORDER BY due_at ASC NULLS LAST, created_at ASC, id ASC
    LIMIT 1
) t ON true
WHERE b.booking_code IN ('YB-001', 'YB-002')
  AND c.status IN ('OPEN', 'IN_PROGRESS', 'WAITING')
ORDER BY b.booking_code, c.case_code;

COMMIT;
'@
$snapshotSql | docker exec -i $demoId psql -X -v ON_ERROR_STOP=1 -P 'null=(null)' -U yb -d yeohaengbom_reconfirmation_test
```

초기에는 YB-001이 `CONFIRMED`, 3박은 `UNKNOWN / UNCONFIRMED`다. 근거와 사건이 없고 YB-002는 아직 없다.

### 4. Spring 서비스 호출과 결과 확인

```powershell
$env:BOOKING_RECONFIRMATION_TEST_DB_URL = $env:SPRING_DATASOURCE_URL
$env:BOOKING_RECONFIRMATION_TEST_DB_PASSWORD = $env:POSTGRES_PASSWORD
$env:BOOKING_RECONFIRMATION_EVIDENCE_TEST = 'true'
./gradlew.bat --no-daemon test --rerun --tests '*BookingReconfirmationEvidenceIntegrationTest'
if ($LASTEXITCODE -ne 0) { throw '재확인 통합 시연 실패' }
$env:BOOKING_RECONFIRMATION_EVIDENCE_TEST = 'false'
$snapshotSql | docker exec -i $demoId psql -X -v ON_ERROR_STOP=1 -P 'null=(null)' -U yb -d yeohaengbom_reconfirmation_test
```

쓰기 통합 테스트에는 플래그와 전용 URL·비밀번호가 모두 필요하다. 테스트는 Spring 시작 전에 전용 DB 주소를 확인하고, JDBC와 Flyway를 그 주소에만 연결한다. 누락된 값이나 기본 개발 DB 주소는 즉시 거부된다. 전용 포트와 DB 이름을 가진 새 임시 컨테이너에서 실행한다.

기존 [통합 테스트](../../src/test/java/com/csro/backend/reconfirmation/BookingReconfirmationEvidenceIntegrationTest.java)가 Spring 컨텍스트에서 `BookingReconfirmationEvidenceService.reconfirmAndApply("YB-001", "RECHECK-001")`를 호출한다. 같은 요청을 한 번 더 실행해 근거 ID와 활성 Case를 재사용하는지 검증한다. 이 Java 메서드는 내부 실행 진입점이다. 사건 생성은 이 내부 메서드로 실행하고, 생성된 사건은 아래 상담사 조회 화면에서 연다. Mock의 Swagger는 외부 회신을 확인하는 별도 화면이다.

| 확인 대상 | 시연 결과 |
| --- | --- |
| YB-001 공급사 | API-026 계약 `CONFIRMED` 유지 |
| YB-001 근거 | 공급사·호텔 응답 각각 한 건. 호텔은 발신 요청도 보존. 첫날 주장만 `ACCEPTED`, 나머지 두 주장은 `PENDING_REVIEW` |
| YB-001 숙박일 | 11/10 `UNAVAILABLE / VERIFIED`, 11/11·12 `UNKNOWN / UNCONFIRMED`. 첫날만 채택 주장 PK와 연결 |
| YB-001 상담 | 깨끗한 시연 DB에서 `C-001 / OPEN / URGENT` 한 건과 `CONTACT_CUSTOMER / TODO` 한 건. `due_at`은 NULL |
| YB-002 정상 비교 | 테스트 fixture에서 3박 `AVAILABLE / VERIFIED`, 활성 Case와 태스크 없음 |

같은 DB에서 전체 통합 테스트를 다시 실행하면 최초 상태 가드가 실패한다. 새 시연에는 새 임시 DB를 생성한다. 멘토링에서는 11/10만 제공 불가로 확인됐고 상담 업무가 등록됐음을 DB로 설명한다. 실제 고객 연락, 상담사 화면, 대체 예약은 이 실행에 포함되지 않는다.

### 5. 회귀와 종료

```powershell
./gradlew.bat --no-daemon test --tests 'com.csro.backend.booking.*' `
    --tests '*BackendApplicationTests' --tests '*BookingReconfirmationServiceTest'
if ($LASTEXITCODE -ne 0) { throw '회귀 테스트 실패' }

$writableUrl = $env:SPRING_DATASOURCE_URL
$env:SPRING_DATASOURCE_URL = $writableUrl + '?options=-c%20default_transaction_read_only%3Don'
$env:BOOKING_RECONFIRMATION_LIVE_TEST = 'true'
./gradlew.bat --no-daemon test --tests '*BookingReconfirmationLiveTest'
$liveTestExit = $LASTEXITCODE
$env:BOOKING_RECONFIRMATION_LIVE_TEST = 'false'
$env:SPRING_DATASOURCE_URL = $writableUrl
if ($liveTestExit -ne 0) { throw '읽기 전용 HTTP 연동 검증 실패' }
```

터미널 A에서 Ctrl+C로 직접 시작한 Mock을 종료한다. 터미널 B에서 같은 세션의 `$demoId`로만 임시 DB를 종료한 뒤 터미널을 닫는다.

```powershell
if ($demoId -and (docker inspect --format '{{.Name}}' $demoId) -eq "/$demoName") {
    docker stop $demoId
}
```

## Agent팀 공통 DB 매핑

API-003 `GET /api/v1/cases`는 활성 사건 목록, API-004 `GET /api/v1/cases/{caseId}`는 권한 있는 상담사의 단건 상세다. 상세는 종료된 사건도 조회할 수 있다. 두 API 모두 공통 Spring 업무 DB를 읽고 재확인, 근거 판정, Case·CaseTask 생성을 호출하지 않는다. 같은 데이터를 별도 테이블이나 별도 DB에 복제하지 않는다. [API-003 계약 검토안](https://app.notion.com/p/417e04d22bed814cae30c2e4b8c3af99) · [API-004 계약 검토안](https://app.notion.com/p/417e04d22bed81aa9d5afcd7a4f9ff85) · [공통 Flyway V1](../../src/main/resources/db/migration/V1__initial_business_schema.sql)

| 반환 필드 | 실제 컬럼과 연결 |
| --- | --- |
| `caseId`, `status`, `priority`, `updatedAt`, `version` | `cases.case_code/status/priority/updated_at/version`. `cases.booking_id → bookings.id`. 생성 시 V1의 version 기본값은 0 |
| `bookingId`, 호텔, 기간·인원, `booking.supplierStatus` | `bookings.booking_code/check_in_date/check_out_date/adults/supplier_status`, `bookings.hotel_id → hotels.id`, `hotels.hotel_code/name` |
| `assigneeId` | `cases.assignee_id` 또는 `case_tasks.assignee_id → users.id`. 미배정은 NULL. 배정된 사용자는 `users.subject_key`를 반환 |
| `nights[]`와 날짜별 목록 요약 | `booking_nights.stay_date/fulfillment_status/fact_status`. `VERIFIED + UNAVAILABLE`은 `unavailableStayDates`, `CONFLICT`는 `conflictStayDates`, `UNKNOWN + UNCONFIRMED`는 `unknownStayDates` |
| `nights[].evidenceId` | `(accepted_assertion_id, booking_nights.id) → (evidence_nights.id, evidence_nights.booking_night_id) → evidence_records.id`. 채택 주장이 없으면 NULL. 충돌 시 기존 채택 링크가 남을 수 있으므로 `factStatus`를 함께 표시 |
| `evidence[]` | `evidence_records.booking_id`로 원문을 연결하고 `source_type/source_authenticated/raw_text/occurred_at/received_at`를 참조. `assertions[]`는 날짜별 주장과 검토 상태를 반환하고 `sourceType`은 `HOTEL_API` 등 DB 출처 코드를 유지 |
| `tasks[]`, `nextAction` | `(case_tasks.case_id, case_tasks.booking_id) → (cases.id, cases.booking_id)`. `task_type/status/due_at`을 읽고 `nextAction`은 미완료 업무 한 건에서 계산 |
| `customerReports[]` | 별도 신고 테이블 `incident_reports`의 `(case_id, booking_id)`로 연결. 이번 시연에서는 신고 없음 |

**다음 업무 선택.** 미완료는 `TODO`, `IN_PROGRESS`다. 기한 오름차순, NULL 마지막으로 조회한다. 동률이면 두 API 모두 `created_at`, `id` 오름차순으로 선택한다. 기한 미정 업무도 선택될 수 있어 `nextAction.dueAt`은 NULL로 전달할 수 있다. 미완료 업무 자체가 없으면 `nextAction=null`이다. API-003 사건 정렬의 명세는 긴급 우선 → 다음 업무 기한 오름차순(NULL 마지막) → 사건 갱신 시각 내림차순 → `caseId` 오름차순이다.

**공개 ID와 표시.** `case_code`는 실제 공개 사건 코드다. 업무와 근거는 각각 `TASK-{case_tasks.id}`, `EV-{evidence_records.id}`이며 앞에 0을 채우지 않는다. Mock에서 `sourceAuthenticated=false`를 그대로 전달한다. 발신 Bearer는 Spring 호출자 인증이며, 격리 Mock에서 원문 내용 검증을 통과해 `VERIFIED`인 숙박일과 별개다. 상담사 권한 범위에서만 원문을 조회하고, 목록 요약은 검증된 사실을 사용한다.

### 상담사 대시보드 실행

4단계에서 C-001을 생성한 뒤 격리 DB를 종료하기 전에 실행한다. 같은 PowerShell의 DB 접속 정보와 임의 생성한 시연 토큰을 Spring·Next.js 서버 프로세스에만 전달한다. 아래 명령은 저장소 루트에서 실행하며, `Start-Process`로 시작한 Spring 프로세스는 시연 후 종료한다.

```powershell
Remove-Item Env:SPRING_CONFIG_LOCATION -ErrorAction SilentlyContinue
$env:SPRING_PROFILES_ACTIVE = 'local-demo'
$env:SPRING_FLYWAY_ENABLED = 'false'
$env:SERVER_PORT = '18080'
$env:CSRO_DEMO_SPRING_PORT = '18080'
$env:CSRO_DEMO_SUBJECT = 'demo-counselor-001'
$env:CSRO_DEMO_TOKEN = [guid]::NewGuid().ToString('N')
$env:CSRO_LOCAL_DEMO = 'true'
if ($env:SPRING_DATASOURCE_URL -ne 'jdbc:postgresql://127.0.0.1:25433/yeohaengbom_reconfirmation_test') { throw '격리 DB 주소 확인 필요' }
Push-Location backend
./gradlew.bat bootJar
if ($LASTEXITCODE) { throw 'Spring 빌드 실패' }
$jar = (Get-ChildItem build/libs/*.jar | Where-Object Name -NotLike '*-plain.jar' | Select-Object -First 1).FullName
$spring = Start-Process java -ArgumentList @('-jar', ('"' + $jar + '"')) -WindowStyle Hidden -PassThru
Pop-Location
Push-Location frontend/travel_bom
try {
    pnpm install --frozen-lockfile
    if ($LASTEXITCODE) { throw '패키지 설치 실패' }
    pnpm build
    if ($LASTEXITCODE) { throw '화면 빌드 실패' }
    node demo-server.mjs
} finally {
    Pop-Location
    if (!$spring.HasExited) { $spring.Kill() }
}
```

같은 PC에서 `http://127.0.0.1:3000/cases`를 열어 C-001을 선택한다. 이 화면은 실제 Spring 조회 결과를 표시한다. 대체 숙소 탐색은 별도 시연 화면이다. 토큰은 서버 환경에만 설정한다. 운영용 Bearer 인증 연결은 별도 작업이다.

격리 시연 서버가 실행된 상태에서 같은 환경변수로 `python backend/tests/counselor_demo.py`를 실행하면 API 응답을 확인한다. Chrome과 `playwright-core`가 준비된 테스트 환경에서 `node frontend/travel_bom/tests/counselor-demo.cjs`로 목록·상세 전환과 조회 실패 표시를 확인한다.
