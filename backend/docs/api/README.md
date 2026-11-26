# API 구현 인덱스

이 인덱스는 코드 위치와 구현 범위를 찾기 위한 저장소 안내다. API 설계와 업무 기준은 연결된 Notion 명세를 따른다. 새 API를 구현하면 행을 추가한다.

A팀의 완료 범위·남은 작업과 B팀 연결 정보는 [A팀 인계](A-TEAM-HANDOFF.md)를 참고한다.

| API | 기능 | 경로 | 작업 범위·브랜치 | 현재 범위 | 구현 문서 |
| --- | --- | --- | --- | --- | --- |
| API-001 | 고객 예약 상세 조회 | `GET /api/v1/bookings/{bookingId}` | A팀 · `1116cy` 초기 구현 | DB 조회·인가 판단·DTO·오류 처리, 공통 Bearer 인증 연결 및 ID 형식 검증은 남음 | [API-001 구현·검토](API-001.md) |
| API-002 | 고객 체크인 문제 신고 | `POST /api/v1/bookings/{bookingId}/incident-reports` | A팀 · `1124cy-core-flow` | 소유권·멱등 스냅샷·원자 접수·활성 사건 재사용·정형 재확인 작업, 별도 고객 시연. 운영 인증·자연어 자동 수집은 후속 | [계약·시연·검증](API-002.md) |
| API-009 | 사건의 대체 견적 조회 | `GET /api/v1/cases/{caseId}/quotes` | `1124cy-core-flow` | 저장 견적·검색 상태·기간별 그룹·비교 결과 조회. 조회 자체는 검색하지 않음 | [최소 계약](API-009-010-PROPOSAL.md) · [실행·검증](QUOTES-DEMO.md) |
| API-010 | 대체 견적 검색 접수 | `POST /api/v1/cases/{caseId}/quote-searches` | `1124cy-core-flow` | 202 접수, 동일 키 재사용, QUOTE_SEARCH·견적 저장·025 비교, 격리 시연 Worker | [최소 계약](API-009-010-PROPOSAL.md) · [실행·검증](QUOTES-DEMO.md) |
| API-011 | 사건별 상담 채팅 메시지 조회 | `GET /api/v1/cases/{caseId}/messages` | 상담 백엔드 · `1124cy-core-flow` 확장 | 소유 사건·상담사 큐 조회, 이전 페이지·제안 카드·현재 제안/선택·서버 판단 시각 | [API-011 구현 메모](#api-011-구현-메모) · [제안·선택 연결](OFFERS-DEMO.md) |
| API-012 | 사건별 상담 채팅 메시지 전송 | `POST /api/v1/cases/{caseId}/messages` | 상담 백엔드 | 활성 고객·상담사의 일반 텍스트 저장 | [API-012 구현 메모](#api-012-구현-메모) |
| API-013 | 고객 후보 제안 | `POST /api/v1/cases/{caseId}/offers` | `1124cy-core-flow` | 상담사가 유효한 저장 견적의 서로 다른 숙소 3곳 발송, 카드·메시지·감사 기록 원자 저장·동일 키 재생 | [사용자 승인 최소 계약](API-013-014-PROPOSAL.md) · [실행·경계](OFFERS-DEMO.md) |
| API-014 | 고객 선택 저장 | `PUT /api/v1/cases/{caseId}/selection` | `1124cy-core-flow` | 예약 소유 고객이 현재 발송 항목 선택, 이력·SYSTEM 알림·현재 선택 조회. 예약 접수는 API-015 별도 | [사용자 승인 최소 계약](API-013-014-PROPOSAL.md) · [실행·경계](OFFERS-DEMO.md) |
| API-015 | 상담사 대체 예약 접수 | `POST /api/v1/cases/{caseId}/replacement-bookings` | `1124cy-core-flow` | 현재 선택·가격 동결 후 202 접수, 같은 BRQ의 가상 공급사 실행·복구 Worker. 입실·종료는 API-017/018 | [사용자 승인 최소 계약](API-015-016-PROPOSAL.md) · [실행·검증 범위](REPLACEMENT-DEMO.md) |
| API-016 | 대체 예약 처리 상태 조회 | `GET /api/v1/replacement-bookings/{replacementBookingId}` | `1124cy-core-flow` | 상담사·예약 소유 고객의 저장 상태 읽기. 공급사 호출·예약 실행·재시도 한도 초기화 없음 | [사용자 승인 최소 계약](API-015-016-PROPOSAL.md) · [실행·검증 범위](REPLACEMENT-DEMO.md) |
| API-024 | 호텔 자연어 회신 주장 추출 | `POST /internal/v1/hotel-reply-extractions` | B팀 제공자 · A팀 `1125cy-api024` 호출·저장 | 저장 자연어 원문 한 건의 입력·응답 검증·분석 저장·필수 수동 확인. 정형 JSON 자동 분석·자동 사실 채택·화면 표시는 후속 | [Spring 연결·시연·범위](API-024-SPRING.md) · [기존 제공자](../../../llm/agent/README.md) |
| API-006 | 호텔 수동 확인 근거 등록 | `POST /api/v1/cases/{caseId}/evidence` | `1126cy-hotel-evidence` | 상담사 통화 결과를 자동 재확인과 같은 규칙으로 숙박일에 반영, 미확정 시 확인 대기·전부 확정 시 VERIFY_HOTEL 완료 | [사용자 승인 최소 계약](API-006-PROPOSAL.md) · [실행·검증](CLOSURE-DEMO.md#시연-3--호텔-직접-확인-api-006) |
| API-008 | 필수 후속 업무 완료(축소) | `PATCH /api/v1/cases/{caseId}/tasks/{taskId}` | `1126cy-closure` | 상담사가 처리 메모로 DONE 처리·감사 기록. 입실 업무는 API-017로만 완료. 담당자·기한 변경은 범위 밖 | [사용자 승인 최소 계약](API-008-017-018-PROPOSAL.md) · [실행·검증](CLOSURE-DEMO.md) |
| API-017 | 고객 대체 숙소 입실 확인 | `POST /api/v1/replacement-bookings/{replacementBookingId}/checkin-confirmations` | `1126cy-closure` | 예약 소유 고객의 CONFIRMED 예약 입실 1회 기록, 입실 업무 자동 완료, 동일 키 재생 | [사용자 승인 최소 계약](API-008-017-018-PROPOSAL.md) · [실행·검증](CLOSURE-DEMO.md) |
| API-018 | 사건 종료 | `POST /api/v1/cases/{caseId}/closures` | `1126cy-closure` | 입실·숙박일 보장·필수 업무 완료를 함께 검사, 미충족은 409 차단 사유, 사건당 1회 종료 기록 | [사용자 승인 최소 계약](API-008-017-018-PROPOSAL.md) · [실행·검증](CLOSURE-DEMO.md) |
| API-025 | 견적 비교·요약 | `POST /internal/v1/alternative-rankings` | `llm/agent` | Spring에 저장한 견적 ID의 규칙 기반 순서·근거 반환, 유효 결과 저장. 고객 발송은 Spring API-013 책임 | [구현 범위](../../../llm/agent/docs/API-025.md) |
| API-028 | 가상 공급사 대체 숙소 검색 | `POST /mock-supplier/v1/availability-searches` | `mock-server` | 고정 시연 기간의 견적·검색별 스냅샷 ID, Spring 검증·저장 연결. 예약·재고 차감은 없음 | [구현 범위](../../../mock-server/docs/API-028.md) |
| API-029/030 | 가상 공급사 예약·결과 조회 | `POST /mock-supplier/v1/replacement-bookings`, `GET /mock-supplier/v1/replacement-booking-requests/{requestId}` | `mock-server` 선택적 PostgreSQL 모드 | 공급사 전용 DB의 전일 재고·요청 결과 원자 저장·동일 키 재생·최종 결과 조회. 기존 stateless 모드 유지 | [별도 DB·HTTP·검사](../../../mock-server/docs/API-029-030.md) |
| API-023 Spring 호출·후속 처리 | 호텔 위험 평가 저장·재확인 선별 | Spring → `POST /internal/v1/risk-assessments` | A팀 · `1122cy-risk-link` | 합성 관측 이벤트의 오프라인 특징값 생성, 평가·선별 작업 원자 저장, 동일 요청 ID로 재확인 재개. 실시간 수집·자동 스케줄러·ML 추론 실패 영속 저장은 미구현 | [2단계 실행·경계](ML-RISK-RECHECK-STAGE2.md) · [1단계 기록](ML-RISK-RECHECK-STAGE1.md) |
| 내부 자동 점검 배치 | 대상 호텔 자동 조회·평가·작업 재개 | 로컬 CLI → Spring 비웹 배치 → 기존 API-023/026/027 | A팀 · `1122cy-risk-link` | 고정 시연 날짜·합성 관측 공급자, 공통 집계와 Python 기준 대조, 반복 평가·작업 재사용, 명시적 간격 반복. 운영 스케줄러·새 HTTP API 없음 | [자동화 실행·경계](ML-RISK-AUTOMATION.md) |

API-001의 구현은 `src/main/java/com/csro/backend/booking/`, 테스트는 같은 패키지의 `src/test/java/`에 있다. 각 클래스 JavaDoc과 JUnit DisplayName에도 API 번호가 표시된다. API 목록 전체가 구현됐다는 인덱스가 아니다.

화면 문구의 "상담 대응 건"은 문서·API의 상담 사건(Case)과 같다. API-003의 `CLOSED` 필터·검색어 `q`, API-004의 `rooms`·`originalPrice`, 고객 화면의 API-001 연결은 [사용자 승인 최소 변경](CONSOLE-VIEWS-PROPOSAL.md)을 따른다.

공통 ML 평가·저장 및 PR #14의 Job 성공 처리를 연결한 범위는 [공통 경로](ML-RISK-COMMON-PATH.md)를 참고한다.

## API-011 구현 메모

`CaseController`와 `CaseQueries`에서 [Notion API-011 검토안](https://app.notion.com/p/ab66adbd23b3830daa1481395fa135b5)을 구현한다. 인증 주체는 기존 사건 조회의 Servlet `Principal` 또는 격리 `local-demo`에서 설정된다. 운영용 Bearer 검증은 공통 인증 계층 연결 후 사용할 수 있다.

활성 고객은 소유 예약의 사건을, 활성 상담사는 단일 큐의 사건을 읽는다. 접근 가능한 종료 사건도 조회한다. `limit` 기본값은 30, 범위는 1~50이다. `beforeMessageId`는 같은 사건의 공개 메시지 번호다. `(sent_at, id)`로 메시지 페이지를 먼저 선택하고, 선택된 페이지를 오래된 순으로 반환한다.

제안은 V1에 저장된 카드를 발송 순서대로 읽는다. 사용자 승인 API-013/014 계약에 따라 최상위 `activeOfferId`, `currentSelection`, `effectiveAt`, `clockMode`를 반환한다. 현재 요약은 해당 메시지 페이지에 카드가 있는지와 무관하다. 새 전체 기간 제안은 발송 당시 `case_actions.details`의 불변 비교 기준으로 `priceDifference`를 표시한다. 부분 기간·기준 스냅샷이 없는 과거 제안은 `null`이다. 현재 예약 금액으로 과거 차액을 다시 계산하지 않는다. 서로 다른 숙소 3곳의 수량·권한·기한 검증은 API-013 발송 시점의 책임이다.

사용자 승인 API-015/016 계약으로 `latestReplacementBooking: null|{replacementBookingId,selectionId}`를 추가한다. 사건의 최신 접수를 가리키는 포인터만 반환하며 결과 상세는 API-016으로 읽는다. 최신 접수는 현재 선택이 바뀐 뒤에도 찾을 수 있다. API-011/016 조회 자체는 예약·선점·공급사 호출을 실행하지 않는다.

통합 테스트는 새 격리 PostgreSQL을 사용한다. `CHATTING_API_INTEGRATION_TEST=true`, `CHATTING_API_TEST_DB_URL=jdbc:postgresql://127.0.0.1:25433/yeohaengbom_chat_api_test`와 `CHATTING_API_TEST_DB_PASSWORD`를 테스트 프로세스에 설정한 뒤 `backend`에서 `.\gradlew.bat test --tests '*CaseMessagesIntegrationTest'`를 실행한다. 테스트는 Spring과 Flyway를 시작하기 전에 DB 이름과 사용자 `yb`를 확인한다.

## API-012 구현 메모

`CaseController`와 `CaseMessageCommands`는 [Notion API-012 상세 검토안](https://app.notion.com/p/1166adbd23b38225b6ae812a4807287a)에 따라 `{"body":"메시지"}`를 받고 API-011의 메시지 항목과 같은 형식으로 `201 Created`를 반환한다. 요청에는 문자열 `body` 필드 하나만 허용하고, 본문의 앞뒤 공백과 줄바꿈을 보존한다. 메시지 번호와 발송 시각은 서버에서 생성한다. 유효한 POST가 반복되면 각각 메시지를 생성한다. 본문 길이 제한과 재전송 중복 방지 키는 팀 검토 항목이다.

- 서블릿 인증 주체를 활성 사용자와 대조한다. 고객은 본인 예약에 연결된 사건, 상담사는 단일 큐의 사건에 보낼 수 있다. 공통 Bearer 인증 연결은 후속 작업이며 `local-demo`의 조회용 인증은 전송에 적용하지 않는다.
- 인증에 실패하면 `401 UNAUTHENTICATED`, 사건이 없거나 고객이 소유하지 않으면 `404 CASE_NOT_FOUND`를 반환한다. 접근 가능한 종료 사건의 이력은 API-011에서 열람할 수 있으며 전송은 `403 CASE_CLOSED`를 반환한다. 잘못된 JSON 또는 본문은 `400 INVALID_MESSAGE_BODY`, 지원하지 않는 요청 형식은 `415 UNSUPPORTED_MEDIA_TYPE`를 반환한다.
- 위 격리 PostgreSQL 테스트에서 별도 트랜잭션으로 저장된 발신자와 메시지를 확인하고 API-011로 다시 조회한다.

### Spring HTTP 로컬 시연

`local-chat-demo` 단독 프로필은 `127.0.0.1:18082`에서 실행한다. 시작 전에 `127.0.0.1:25434`의 전용 DB `yeohaengbom_chat_demo`와 사용자 `yb_chat_demo`를 검증한다. 이어 같은 DataSource로 기존 Flyway 마이그레이션과 로컬 예약·사용자 시드를 적용한다. 사건 목록·상세·대화 조회와 메시지 전송에 별도의 Bearer 토큰으로 `demo-counselor-001`을 서블릿 인증 주체에 연결한다.

저장소의 `backend` 디렉터리에서 두 포트가 비어 있는 상태로 실행한다. 시연 비밀번호와 토큰은 새 PowerShell 세션의 환경변수로 전달한다.

```powershell
$env:CSRO_CHAT_DEMO_DB_PASSWORD = [guid]::NewGuid().ToString('N')
$env:CSRO_CHAT_DEMO_TOKEN = [guid]::NewGuid().ToString('N')
$env:POSTGRES_PASSWORD = $env:CSRO_CHAT_DEMO_DB_PASSWORD
docker run --rm --detach --name csro-local-chat-demo-db --tmpfs /var/lib/postgresql -p 127.0.0.1:25434:5432 -e POSTGRES_USER=yb_chat_demo -e POSTGRES_DB=yeohaengbom_chat_demo -e POSTGRES_PASSWORD postgres:18.7
Remove-Item Env:POSTGRES_PASSWORD
# 아래 명령이 성공하면 Spring을 빌드하고 시작한다.
docker exec csro-local-chat-demo-db pg_isready -h 127.0.0.1 -U yb_chat_demo -d yeohaengbom_chat_demo
.\gradlew.bat bootJar
$chatDemoJar = (Get-ChildItem build/libs/*.jar | Where-Object Name -NotLike '*-plain.jar' | Select-Object -First 1).FullName
$chatDemoSpring = Start-Process java -ArgumentList @('-jar', ('"' + $chatDemoJar + '"'), '--spring.profiles.active=local-chat-demo') -WindowStyle Hidden -PassThru
```

`GET http://127.0.0.1:18082/hello`가 응답하면 전용 컨테이너에 시연용 `C-002 / YB-002`를 추가한다. 이 시드는 DB 이름과 계정을 확인하고, 재실행할 때 이미 준비된 사례와 이후 상담사가 보낸 답장을 보존한다.

```powershell
$chatDemoBinding = docker port csro-local-chat-demo-db 5432/tcp
if ($LASTEXITCODE -ne 0 -or $chatDemoBinding -ne '127.0.0.1:25434') { throw '전용 시연 DB 포트를 확인해 주세요.' }
$chatDemoSeed = (Resolve-Path 'tools/seed-chat-demo-case.sql').Path
docker cp $chatDemoSeed csro-local-chat-demo-db:/tmp/csro-seed-chat-demo-case.sql
if ($LASTEXITCODE -ne 0) { throw '시드 복사에 실패했습니다.' }
docker exec csro-local-chat-demo-db psql -X -U yb_chat_demo -d yeohaengbom_chat_demo -f /tmp/csro-seed-chat-demo-case.sql
```

같은 시연 DB를 브라우저에서 조회하려면 토큰을 발급한 PowerShell의 `frontend/travel_bom`에서 빌드 후 프록시를 실행한다. `http://127.0.0.1:3100/cases`에서 C-002를 열어 첫날 제공 불가, 고객 신고, 호텔 회신 원문과 다음 업무를 확인한다. 채팅에서 답장한 뒤 새로고침하고 같은 사건을 다시 열면 저장된 답장을 볼 수 있다.

```powershell
pnpm build
$env:PORT = '3100'
$env:CSRO_DEMO_SPRING_PORT = '18082'
$env:CSRO_DEMO_TOKEN = $env:CSRO_CHAT_DEMO_TOKEN
$env:CSRO_LOCAL_DEMO = 'true'
$env:CSRO_CHAT_DEMO_PROXY = 'true'
node demo-server.mjs
```

`CSRO_CHAT_DEMO_PROXY=true`인 `3100 → 18082` 구성에서는 같은 출처의 브라우저 요청에 한해 `POST /demo/cases/{caseId}/messages`를 허용한다. 프록시는 JSON 본문에 상담사 인증 헤더를 붙여 Spring으로 전달한다. 브라우저의 `Origin`은 `http://127.0.0.1:3100`, `Sec-Fetch-Site`는 `same-origin`이어야 한다. 이 설정을 생략하면 조회 전용 프록시로 실행된다.

Notion API-003·004의 상태 표기에는 구현이 남아 있지만, 현재 시연은 Spring 목록·상세 응답을 사용한다. API-004의 예시에는 근거 요약이 있으나 현재 응답은 `evidence.summary`를 `null`로 반환한다. 화면은 회신에서 채택된 숙박일 상태와 저장된 원문을 표시한다. 고객 신고 응답에는 유형·현장 여부·접수 시각이 있고, 신고 원문은 연결된 첫 고객 메시지로 API-011에서 조회한다.

API-011은 최신 이력 조회와 이전 방향 커서를 제공하며 새 대화 확인은 채팅의 조회 버튼으로 실행한다. API-012의 공통 Bearer 연동은 후속 작업이며 로컬 시연에서는 위 전용 프로필의 상담사 주체를 사용한다.

- API-009/010은 사용자 승인 최소 계약으로 Spring 저장·사건 화면에 연결했다. [실행·검증 및 시연 시각](QUOTES-DEMO.md)을 확인한다. 검색 전·진행 중·실패·완료 0건은 서로 구분한다.
- API-013/014는 [사용자 승인 최소 계약](API-013-014-PROPOSAL.md)으로 전송·선택 저장에 연결했다. [고객 전용 시연](OFFERS-DEMO.md)은 별도 고객 토큰·3101 프록시를 명시적으로 켜며, 기존 DB·loopback·프로필 검증을 유지한다. 별도 대체 숙소 탐색 시연은 이 저장 견적·제안 흐름과 독립적이다.
- API-015/016의 [가상 예약 시연](REPLACEMENT-DEMO.md)은 별도 Mock DB와 상태 저장 서버, Spring `replacement.worker.enabled=true`, 양 프록시의 `CSRO_REPLACEMENT_DEMO_PROXY=true`를 추가로 명시한다. 상담사만 예약을 접수하고 고객은 API-011·014와 자신의 예약 상태 API-016을 사용한다. 일반 인증·입실·사건 종료 구현 완료를 뜻하지 않는다.

시연을 마치면 이 세션에서 `Stop-Process -Id $chatDemoSpring.Id`와 `docker stop csro-local-chat-demo-db`를 실행한다. 컨테이너의 메모리 볼륨에 저장한 시연 메시지는 컨테이너 종료 시 사라진다.
