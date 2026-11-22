# API 구현 인덱스

이 인덱스는 코드 위치와 구현 범위를 찾기 위한 저장소 안내다. API 설계와 업무 기준은 연결된 Notion 명세를 따른다. 새 API를 구현하면 행을 추가한다.

| API | 기능 | 경로 | 작업 범위·브랜치 | 현재 범위 | 구현 문서 |
| --- | --- | --- | --- | --- | --- |
| API-001 | 고객 예약 상세 조회 | `GET /api/v1/bookings/{bookingId}` | A팀 · `1116cy` 초기 구현 | DB 조회·인가 판단·DTO·오류 처리, 공통 Bearer 인증 연결 및 ID 형식 검증은 남음 | [API-001 구현·검토](API-001.md) |
| API-011 | 사건별 상담 채팅 메시지 조회 | `GET /api/v1/cases/{caseId}/messages` | 상담 백엔드 | 활성 고객의 소유 사건·활성 상담사 큐 조회, 이전 페이지와 제안 카드 이력 | [API-011 구현 메모](#api-011-구현-메모) |
| API-012 | 사건별 상담 채팅 메시지 전송 | `POST /api/v1/cases/{caseId}/messages` | 상담 백엔드 | 활성 고객·상담사의 일반 텍스트 저장 | [API-012 구현 메모](#api-012-구현-메모) |

API-001의 구현은 `src/main/java/com/csro/backend/booking/`, 테스트는 같은 패키지의 `src/test/java/`에 있다. 각 클래스 JavaDoc과 JUnit DisplayName에도 API 번호가 표시된다. API 목록 전체가 구현됐다는 인덱스가 아니다.

## API-011 구현 메모

`CaseController`와 `CaseQueries`에서 [Notion API-011 검토안](https://app.notion.com/p/ab66adbd23b3830daa1481395fa135b5)을 구현한다. 인증 주체는 기존 사건 조회의 Servlet `Principal` 또는 격리 `local-demo`에서 설정된다. 운영용 Bearer 검증은 공통 인증 계층 연결 후 사용할 수 있다.

활성 고객은 소유 예약의 사건을, 활성 상담사는 단일 큐의 사건을 읽는다. 접근 가능한 종료 사건도 조회한다. `limit` 기본값은 30, 범위는 1~50이다. `beforeMessageId`는 같은 사건의 공개 메시지 번호다. `(sent_at, id)`로 메시지 페이지를 먼저 선택하고, 선택된 페이지를 오래된 순으로 반환한다.

제안은 V1에 저장된 카드를 발송 순서대로 읽는다. V1에는 발송 당시 비교 기준액이 없어 `priceDifference`는 `null`이다. 제안당 카드 3장의 수량 보장은 API-013 발송 시점의 책임이다.

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

`GET http://127.0.0.1:18082/hello`가 응답하면 시연 예약에 활성 사건을 하나 만든다. 이어 같은 사건에 한 건을 전송하고, 응답의 ID를 API-011에서 조회한다.

```powershell
docker exec csro-local-chat-demo-db psql -X -v ON_ERROR_STOP=1 -U yb_chat_demo -d yeohaengbom_chat_demo -c "INSERT INTO cases (case_code, booking_id, status) SELECT 'C-001', id, 'OPEN' FROM bookings WHERE booking_code = 'YB-001' ON CONFLICT (case_code) DO NOTHING;"
$chatDemoHeaders = @{ Authorization = "Bearer $env:CSRO_CHAT_DEMO_TOKEN" }
$chatDemoUrl = 'http://127.0.0.1:18082/api/v1/cases/C-001/messages'
$chatDemoBody = @{ body = '상담 시연 메시지' } | ConvertTo-Json -Compress
$sent = Invoke-WebRequest -UseBasicParsing -Method Post -Uri $chatDemoUrl -Headers $chatDemoHeaders -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($chatDemoBody))
$messageId = ($sent.Content | ConvertFrom-Json).messageId
$history = Invoke-RestMethod -Uri $chatDemoUrl -Headers $chatDemoHeaders
$sent.StatusCode
$history.items | Where-Object messageId -EQ $messageId
```

같은 시연 DB를 브라우저에서 조회하려면, 토큰을 발급한 PowerShell의 `frontend/travel_bom`에서 빌드 후 프록시를 실행한다. `http://127.0.0.1:3100/cases`에서 C-001을 열면 상담 사건 상세와 대화 내역을 볼 수 있다. 프록시는 목록·상세·대화 GET을 같은 Spring으로 전달한다.

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

시연을 마치면 이 세션에서 `Stop-Process -Id $chatDemoSpring.Id`와 `docker stop csro-local-chat-demo-db`를 실행한다. 컨테이너의 메모리 볼륨에 저장한 시연 메시지는 컨테이너 종료 시 사라진다.
