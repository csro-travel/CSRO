# API-024 Spring 원문 분석 연결

[Notion API-024](https://app.notion.com/p/b592f2597c5782caa1c581d6b8c52e3a)의 기존 요청 8필드·응답 7필드와 현재 B팀 서버를 연결한다. A팀 Spring은 원문 선택·HTTP 호출·응답 검증·업무 DB 저장을, B팀은 주장 추출을 담당한다. Notion의 팀 전체 설계 승인 상태를 바꾸거나 새 공개 API를 만든 것은 아니다.

## 구현 경계

`HotelReplyAnalysisService.analyze(evidenceId)`가 내부 진입점이다. 저장된 호텔 자연어 원문 한 건을 읽고, 읽기 트랜잭션을 끝낸 뒤 B팀의 `POST /internal/v1/hotel-reply-extractions`를 호출한다. 원문 보존·출처·분석·검증된 사실을 구분한다.

현재 B팀 엔진은 정규식 기반 `hotel-reply-extractor-0.1`이다. LightGBM 위험 모델이나 학습된 LLM의 성능으로 설명하지 않는다. `requiresHumanVerification`은 실제 boolean true여야 한다. 응답이 유효해도 원문·출처 인증·숙박일 상태·기존 근거 주장·공급사 예약·사건 상태·담당자를 갱신하지 않는다.

활성 사건이 있으면 필수 `VERIFY_HOTEL` TODO/IN_PROGRESS를 재사용하거나 새 TODO를 남긴다. 분석 실패 때도 같은 수동 확인 경로를 보존한다. 사건이 없거나 닫힌 사건만 있으면 새 사건을 만들거나 다시 열지 않고, 결과에 업무 번호 없음과 수동 인계 필요를 표시한다. 견적·예약·입실·후처리·종료는 실행하지 않는다.

## 원문과 요청 매핑

첫 범위는 `HOTEL_API`·`HOTEL_EVENT`·`MANUAL_HOTEL` 중 `raw_payload IS NULL`이고 비공백 `raw_text`가 있는 저장 원문이다. 고객 신고·고객 채팅·공급사 JSON은 호텔 자연어 원문으로 사용하지 않는다. 출처 인증이 false인 호텔 원문도 분석할 수 있지만, 분석 성공이 출처 인증이나 객실 제공 사실을 확정하지 않는다.

| 요청 필드 | 원본 |
| --- | --- |
| evidenceId | 실제 `evidence_records.id`의 `EV-{id}` 표현 |
| bookingId / hotelId | 연결된 `bookings.booking_code` / `hotels.hotel_code` |
| stayDates | 해당 예약의 전체 `booking_nights.stay_date`, 체크아웃 제외 |
| originalMessage | 선택한 자연어 `raw_text` 그대로 |
| locale | 이번 한국어 호출자의 명시적 `ko-KR` |
| messageOccurredAt | `occurred_at`. 없으면 키를 생략하지 않고 null |
| hotelTimeZone | `hotels.time_zone` |

발생 시각을 수신 시각으로 대체하지 않는다. 숙박일은 빈 배열·중복·기간 누락을 거부한다. 현재 B팀 구현에 맞춰 원문 10,000자와 IANA 시간대를 검사한다. 이 값이 팀 전체의 새로운 운영 한도로 승인됐다는 뜻은 아니다. 실제 개인정보 처리·비식별화를 구현한 것은 아니며 시연 입력은 가상 원문을 사용한다.

기존 API-027 경로의 `raw_text`는 전체 응답 JSON이므로 자동 분석 대상으로 선택하지 않는다. `nights[].message`를 임의로 이어 붙이거나 원문을 고쳐 추출 결과를 맞추지 않는다. 정형 026/027의 기존 근거·숙박일 반영 경로는 유지한다.

## 응답 검증과 저장

- 응답 근거 ID는 요청과 같아야 한다. 주장 날짜와 미확인 날짜는 서로 겹치거나 중복되지 않으며 입력 숙박일 전체를 포괄해야 한다.
- 주장은 AVAILABLE/UNAVAILABLE만 허용한다. 인용은 실제 전송 원문의 비공백 부분문자열이어야 한다. EXTRACTED는 주장이 있고, AMBIGUOUS는 주장이 없는 정상 분석이다.
- 필수 JSON 필드·타입·실제 true·모델 버전의 비공백 및 DB 80자 한도를 검사한다. 중복 키·후행 JSON·잘못된 UTF-8을 거부한다. 미지 응답 필드는 스냅샷에 보존하되 업무 판단에 사용하지 않는다.
- HTTP는 connect 2초/read 5초, 리다이렉트 미추적, 응답 본문 최대 1MiB의 로컬 보호 한도를 사용한다. 원문·토큰·provider 오류 본문을 예외나 로그로 출력하지 않는다.

저장은 예약→활성 사건→원문 잠금 순서의 짧은 트랜잭션으로 수행한다. HTTP 전후 원문·예약·호텔·숙박일·시각의 문맥을 다시 대조하고 변경된 문맥에 결과를 붙이지 않는다. 실제 요청·응답 JSON과 모델 버전·서버 분석 시각을 기존 `agent_analyses`에 저장한다. 같은 입력·모델·결과는 재사용하며 다른 결과/새 모델의 재분석은 기존 행을 덮어쓰지 않는다. 분석 저장과 필요한 확인 업무는 함께 커밋한다. 적용된 V1과 DB 스키마는 변경하지 않는다.

401·422·503·타임아웃·변조 응답을 AMBIGUOUS로 위장하지 않는다. 성공 분석 행 없이 `MANUAL_REQUIRED`와 안전한 오류 코드·추적 번호를 반환하고 활성 사건의 필수 확인 업무를 남긴다. DB 저장 실패·문맥 변경은 성공으로 표시하지 않는다. 별도 자동 분석 큐·무한 재시도·실패 상태의 가짜 분석 행은 만들지 않는다.

## 명시적 로컬 시연

기존 [고객/상담사 전용 시연](OFFERS-DEMO.md)의 `local-chat-demo`를 사용한다. 전용 DB는 `127.0.0.1:25434/yeohaengbom_chat_demo`, 사용자 `yb_chat_demo`이며 시작 전에 실제 DB 신원을 확인하는 기존 경계를 유지한다. 기존 서버가 실행 중이면 정상 종료한 뒤 옵션을 추가해 재시작한다.

1. B팀 서버를 [기존 안내](../../../llm/agent/README.md)대로 실행하거나 이미 실행 중인 서버의 주소·서비스 토큰을 사용한다. 토큰을 새로 바꿨다면 Spring도 같은 값으로 맞춘다.
2. Spring용 환경변수를 설정한다. 예시는 같은 PowerShell에 B팀 토큰이 있을 때이며, 다른 터미널이면 비공개 방식으로 같은 값을 전달한다.

```powershell
$env:HOTEL_ANALYSIS_AGENT_BASE_URL = 'http://127.0.0.1:8000'
$env:HOTEL_ANALYSIS_AGENT_SERVICE_TOKEN = $env:AGENT_SERVICE_TOKEN
```

3. 분석할 **이미 저장된 자연어 근거의 실제 번호**를 확인한다. C-002가 없다면 기존의 명시적 `backend/tools/seed-chat-demo-case.sql` 시연 절차로 준비한다. 실행기는 원문·예약·사건을 새로 만들거나 시드를 자동 적용하지 않는다.

```sql
SELECT 'EV-' || e.id AS evidence_id, b.booking_code, h.hotel_code,
       e.source_type, e.source_authenticated, e.occurred_at
FROM evidence_records e
JOIN bookings b ON b.id=e.booking_id
JOIN hotels h ON h.id=b.hotel_id
WHERE b.booking_code='YB-002'
  AND e.provider_event_id='CHAT-DEMO-YB-002-HOTEL-REPLY'
  AND e.raw_payload IS NULL;
```

4. `backend`에서 빌드하고 기존 전용 시연 환경변수를 유지한 채 선택한 번호를 명시한다. `EV-실제번호`를 위 조회 결과로 바꾼다.

```powershell
.\gradlew.bat bootJar
if ($LASTEXITCODE -ne 0) { throw 'Spring 빌드 실패' }
$analysisJar = (Get-ChildItem build/libs/*.jar | Where-Object Name -NotLike '*-plain.jar' | Select-Object -First 1).FullName
java -jar $analysisJar --spring.profiles.active=local-chat-demo --hotel.analysis.demo.evidence-id=EV-실제번호
```

실행기는 기본 꺼짐이며 검증된 단독 프로필·loopback에서 한 번만 실행한다. 분석 뒤 기존 Spring 웹 서버는 유지한다. 정상 시작 로그만으로 분석 저장 성공을 판정하지 말고 `HOTEL_REPLY_ANALYSIS_RESULT`의 `status`, `analysisId`, `analysisStatus`, `manualTaskId`, `manualHandoffRequired`를 확인한다. 원문·인용·provider 자유 텍스트는 이 출력에 넣지 않는다.

| 결과 | 의미 |
| --- | --- |
| ANALYZED | 검증한 새 분석을 저장함. 사람 확인은 여전히 필요 |
| REUSED | 동일 입력·모델·결과의 기존 분석을 재사용함 |
| MANUAL_REQUIRED | 분석/저장 성공으로 간주하지 않음. 오류 코드·수동 확인 경로 점검 |
| manualHandoffRequired=true | 자동으로 연결·저장된 확인 업무가 없어 별도 수동 인계가 필요함. 자동 Case 생성 없음 |

수동 실행 옵션을 유지하고 재시작하면 같은 근거를 다시 호출할 수 있으며 저장·업무 재사용 정책을 적용한다. 상시 운영 스케줄러는 아니다. Spring 쪽 새 공개 HTTP API·상담 화면의 분석 결과 표시·실제 자연어 회신 수신 채널은 후속이다.

## C-002 시연 해석

C-002는 YB-002 / HOTEL-001 / 2026-11-22·23·24이며, 원문에 `11월 22일(첫날)에는…`이 있다. 현재 B팀 추출기는 원문 그대로 **200 / AMBIGUOUS**를 반환한다. 원문에서 괄호를 지워 EXTRACTED를 만들지 않는다.

C-002 첫날의 VERIFIED/ACCEPTED 및 source_authenticated=true는 기존 SQL 시드가 만든 값이다. 이 연동이 최초 검증·인증을 수행한 것이 아니다. 분석 결과가 미확인이어도 기존 검증 사실을 되돌리지 않는다. 명확한 부분 추출 검사는 별도 합성 원문과 UNKNOWN 숙박일로 분리한다.

## 검증 명령과 범위

단위 검사는 `backend`에서 다음과 같이 실행한다. 전용 DB 검사는 환경변수 없이는 건너뛴다.

```powershell
.\gradlew.bat test --tests 'com.csro.backend.hotelanalysis.*'
```

쓰기 통합 검사는 별도 `127.0.0.1:25447/yeohaengbom_hotel_analysis_test`, 사용자 `yb_hotel_analysis_test`를 사용한다. `HOTEL_ANALYSIS_TEST=true`, `HOTEL_ANALYSIS_TEST_DB_URL`, `HOTEL_ANALYSIS_TEST_DB_USER`, `HOTEL_ANALYSIS_TEST_DB_PASSWORD`를 프로세스 환경으로 전달한다. 설정과 실제 DB/사용자를 Flyway 전에 확인하며 기존 업무·시연 DB를 테스트 DB로 사용하지 않는다.

```powershell
.\gradlew.bat test --tests 'com.csro.backend.hotelanalysis.*IntegrationTest'
```

Linux에서 client·실행기·DB guard 단위 74개와 전용 PostgreSQL 통합 19개가 통과했다. 요청/응답 변조, 날짜·인용·타입·인증·시간 제한, nullable 발생 시각, 동시 분석 한 행 재사용, 다른 모델/결과 append, 실패의 필수 업무, 기존 VERIFIED 보존, 문맥 변경 거부와 분석/업무 원자 롤백을 검사했다.

별도의 임시 시연 DB와 실제 B팀 서버를 연결한 Spring JAR 실행도 확인했다. C-002 원문 그대로 AMBIGUOUS 분석을 저장하고 입력 8필드·응답 7필드 및 필수 VERIFY_HOTEL 1건을 확인했다. 원문·출처 인증·기존 숙박일 사실·OPEN 사건은 그대로였다. 같은 선택으로 프로세스를 재시작하면 REUSED와 같은 분석/업무 번호를 반환하고 분석·확인 업무·사건은 각각 1건이었다. B팀 서버를 중단한 뒤에는 MANUAL_REQUIRED/AGENT_CONNECTION_FAILED를 반환하며 기존 분석과 같은 필수 업무를 보존하고 안전한 실패 감사만 추가했다.

이 검증은 가상 원문의 분석 연결과 정합성 범위다. Windows 재현, 운영 인증·실호텔 성능, 자연어 분석의 자동 사실 채택·후처리·사건 종료 완료로 확대 해석하지 않는다. 원격 CI 결과는 해당 PR에서 별도로 확인한다.
