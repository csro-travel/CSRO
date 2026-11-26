# 가상 대체 예약 접수·확정·결과 복구

상담사가 고객의 현재 선택을 API-015로 접수하면 Spring Worker가 API-029/030으로 가상 공급사 예약 결과를 확인한다. API-016과 상담사·고객 화면은 저장된 상태를 읽는다. [사용자 승인 최소 계약](API-015-016-PROPOSAL.md)을 구현한 범위이며 Notion 팀 전체 설계 상태를 변경하지 않는다. **가상 예약 확정은 입실·원예약 처리·사건 종료가 아니다.**

## 두 DB와 기존 모드

| 구분 | 소유자·실행 | 주소·데이터 |
| --- | --- | --- |
| Spring 업무 DB | 기존 Spring/Flyway, `local-chat-demo` | `127.0.0.1:25434/yeohaengbom_chat_demo`, `yb_chat_demo`; 사건·견적·제안·선택·예약 원장·필수 업무 |
| 별도 Mock DB | Mock Python, 선택적 `compose.mock.yaml` | `127.0.0.1:25442/yeohaengbom_mock`, `yb_mock`; 상품·숙박일별 재고·발급 견적·공급사 요청 결과 |

일반 `compose.yaml` 업무 DB와 Spring V1은 변경하지 않는다. Mock DB는 Spring Flyway가 아닌 [별도 마이그레이션](../../../mock-server/db/migration/V1__mock_supplier_schema.sql)을 사용한다. Mock만 DB에 접속하고 Spring은 기존 서비스 Bearer HTTP로만 호출한다. 서로 다른 DB 사이에 분산 트랜잭션을 만들지 않는다.

기본 `uvicorn app.main:app`는 DB 없이 기존 026/027/028을 유지한다. 029/030 예약은 명시적으로 준비한 상태 저장 모드에서 사용한다. 기본 모드에서 예약 요청에 반환하는 503은 예약 실패 확정 근거가 아니다.

## 실행 순서

기존 [고객 제안 시연](OFFERS-DEMO.md)의 전용 업무 DB·YB-001 사건·ML·Agent·상담사/고객 토큰을 준비한다. 대상 숙박은 `2026-11-10~13`이며 `seed-chat-demo-case.sql`의 11/22 YB-002를 쓰지 않는다. 기존 8001 Mock과 Spring·3100/3101 프록시는 해당 프로세스를 정상 종료한 뒤 아래 설정으로 다시 시작한다.

1. [Mock 예약 서버 실행 안내](../../../mock-server/docs/API-029-030.md#실행--windows-powershell)에 따라 `.env.mock.example`을 **처음 한 번만** Git 제외 파일 `.env.mock`으로 복사하고 임의 비밀번호를 설정한다. 기존 파일·DB 비밀번호를 덮어쓰지 않는다.
2. 저장소 루트에서 별도 DB를 시작한다.

```powershell
docker compose --env-file .env.mock -f compose.mock.yaml up -d --wait
if ($LASTEXITCODE -ne 0) { throw '별도 Mock DB 시작 실패' }
```

3. 위 Mock 안내대로 비공개 파일의 `MOCK_DB_PASSWORD`를 Python 프로세스 환경에도 전달하고 `mock-server`에서 선택적 의존성과 서버를 실행한다. Compose의 `--env-file`은 Python 터미널 환경을 자동 설정하지 않는다. `MOCK_SERVICE_TOKEN`은 Spring의 기존 Mock 서비스 토큰과 같아야 한다.

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements-stateful.txt
if ($LASTEXITCODE -ne 0) { throw 'Mock PostgreSQL 의존성 설치 실패' }
$env:MOCK_STATEFUL_ENABLED = 'true'
$env:MOCK_BOOKING_AS_OF = '2026-11-09T10:20:00+09:00'
.\.venv\Scripts\python.exe -m app.stateful_server
```

이 launcher는 `127.0.0.1:8001`에 고정하며 proxy header를 신뢰하지 않는다. Mock은 DDL 전과 접속 때마다 실제 DB·사용자 신원을 확인한다. 상품·숙박일 시드는 빠진 행만 준비하고 재시작으로 단가·재고·견적·예약을 초기화하지 않는다. 스키마 marker/catalog 해시가 다르면 자동 수선 없이 중단한다. `down -v`·테이블 삭제·Spring DB 재사용으로 우회하지 않는다.

4. 기존 Spring 실행 명령에 `--replacement.worker.enabled=true`를 추가한다. 고객 토큰은 Spring 시작 전 설정되어 있어야 한다. 기존 `booking.reconfirmation.mock.base-url/service-token`을 재사용하며 별도의 예약용 공급사 주소·자격 증명을 만들지 않는다.

```powershell
# 기존 OFFERS-DEMO에서 준비한 JAR·DB·토큰 환경을 유지한다.
java -jar $offerJar --spring.profiles.active=local-chat-demo --quote.search.worker.enabled=true --quote.search.demo-as-of=2026-11-09T10:20:00+09:00 --replacement.worker.enabled=true
```

Worker는 기본 꺼짐이며 전용 DB 신원 검증을 마친 `local-chat-demo` 단독 프로필·loopback에서만 켤 수 있다. Mock의 `MOCK_BOOKING_AS_OF`와 Spring의 `quote.search.demo-as-of`는 같아야 한다. 이 시각은 합성 견적 유효성과 공급사 confirmedAt용이다. claim·재시도 기한·감사는 실제 시계, Mock failedAt은 저장된 실제 created_at을 사용한다.

5. 기존 상담사 3100·고객 3101 프록시를 시작하기 **전에 두 프로세스 모두** `CSRO_REPLACEMENT_DEMO_PROXY=true`를 설정한다. 기존 상담사·고객 토큰 분리와 `CSRO_OFFER_DEMO_PROXY`, `CSRO_CUSTOMER_DEMO_PROXY` 설정은 유지한다. 상담사만 POST-015, 양 역할은 소유권 검사 후 GET-016을 사용한다. 브라우저에는 서비스 토큰을 전달하지 않는다.
6. 상태 저장 모드로 전환한 뒤 **새 검색→새 제안→고객 선택**을 진행한다. 과거 stateless 견적을 Spring DB에서 Mock DB로 복사하지 않는다. 이전 견적 ID는 새 Mock 원장에 존재하지 않을 수 있다. 028이 새로 저장한 견적의 상품·기간·금액으로만 예약한다.
7. 상담사 채팅의 **대체 예약 진행**을 누른다. 접수번호 BRQ와 처리 상태를 확인하고 고객 화면을 새로 조회한다. 확정 후에는 ALT 예약번호·숙박기간·확정금액과 별도 입실/원예약 후속 안내를 확인한다.

## 접수·조회·화면

`POST /api/v1/cases/{caseId}/replacement-bookings`는 상담사만 `{"selectionId":"SEL-…"}`와 필수 Idempotency-Key로 호출한다. 가격·상품·고객·기간은 서버의 현재 선택에서 동결한다. 응답은 불변 202 접수 결과와 API-016 Location이며 **확정 결과가 아니다.** Spring은 사건+키로 만든 `BRQ-`+64자리 해시를 공급사에도 같은 요청번호·멱등키로 사용한다.

같은 키·본문은 최초 접수를 재생하고 다른 선택은 409다. 같은 선택에 다른 키를 쓰면 `SELECTION_ALREADY_REQUESTED`와 기존 BRQ를 반환하며 새 예약을 만들지 않는다. 응답을 못 받았을 때는 같은 본문·키를 유지한다. 브라우저는 미확정 접수를 보관하고 API-011의 `latestReplacementBooking` 포인터로 다른 브라우저·새로고침에서도 기존 API-016 조회를 재개한다.

`GET /api/v1/replacement-bookings/{BRQ}`는 저장된 상태만 읽는다. 공급사 호출·선점·재시도·예산 초기화를 하지 않는다. 상담사 또는 원예약 소유 고객만 조회하며 타인과 미존재 요청은 같은 404다. 클라이언트는 3초 간격 최대 30회 순차 조회 후 수동 새로고침으로 전환한다. 읽기 실패를 예약 실패로 표시하지 않는다.

| 상태 | 의미·다음 행동 |
| --- | --- |
| PROCESSING | 접수 원장이 저장되어 공급사 처리 중. 새 예약·선택 교체 차단 |
| RESULT_UNKNOWN | 예약 결과 확인 중. 동일 BRQ로 확인하며 새 키·새 가격으로 재예약 금지 |
| CONFIRMED | 가상 예약 확정. 입실·원예약 후속은 별도이며 사건 종료 안 함 |
| FAILED | 예약 미생성 확인. 실패 원장을 남기고 새 검색→제안→선택으로 진행 |

한도 소진은 RESULT_UNKNOWN/manualRequired다. 화면의 새로고침은 결과 읽기만 한다. 현재 실패 제안의 다른 항목을 고르는 방식으로 새 검색을 우회하지 않는다.

## 원자성·복구·필수 업무

Mock은 요청 키→견적→숙박일 오름차순 재고 행을 잠그고 전일 재고 차감·ALT 번호·최초 요청 결과를 한 트랜잭션으로 저장한다. 하루라도 품절·가격 변경이면 전일 차감 없이 알려진 FAILED와 최초 오류를 커밋한다. 동일 키 재생은 만료·재고 재검사 전에 수행한다. 하나의 견적은 한 번만 확정할 수 있고 다른 키 요청은 QUOTE_ALREADY_BOOKED다. 상품 메타데이터와 발급 견적은 불변이다.

Spring은 booking→case→replacement 잠금에서 claim을 저장하고 HTTP는 커밋 후 잠금 밖에서 실행한다. 결과를 반영할 때 최신 claimToken을 검사한다. 기존 jobs.kind에 예약 작업을 추가하거나 다른 종류로 위장하지 않는다. 최초 공급사 본문은 replacement_requests.request_payload에, 처리 이력은 case_actions의 `replacement-booking-v1` 기록에 보존한다.

Worker는 2초 간격·회당 최대 10건, HTTP connect 2초/read 5초, lease 45초·실제 기한 300초다. 응답 유실·503·불완전 응답은 RESULT_UNKNOWN이며 같은 BRQ의 GET-030을 최대 8회 확인한다. 인증된 `BOOKING_REQUEST_NOT_FOUND`만 동결한 **동일 BRQ·동일 본문**의 POST를 최대 2회 추가 허용한다. 다른 조회 실패는 재POST하지 않는다. 확정 결과는 뒤의 조회 장애로 되돌리지 않는다.

수동 확인이 필요하면 기존 Worker를 중단하고 같은 검증 프로필에서 `--replacement.worker.enabled=false --replacement.recovery.request-id=BRQ-…`를 명시할 수 있다. 자동 Worker를 끈 상태로 같은 BRQ의 조회만 새 한도 안에서 재개한다. 이 내부 복구도 새 가격·새 요청번호로 예약하지 않는다. 가상 시각·실제 감사 시계와 기존 서버 토큰을 유지한다.

성공 결과·SYSTEM 예약 안내·감사·필수 TODO `CHECKIN_CONFIRM`과 `ORIGINAL_FOLLOWUP` 생성/재사용은 같은 업무 트랜잭션에 저장한다. CONTACT_CUSTOMER 등 기존 업무를 자동 완료하지 않는다. 원호텔 booking_nights나 공급사 계약 상태도 대체 예약 성공으로 덮어쓰지 않는다.

## 검증과 남은 범위

Linux에서 Spring 단위 20개·전용 DB 검사 15개, Mock 기본 검사 45개와 별도 전용 DB 검사 10개를 통과했다. 기본 Mock 실행에서 DB 검사 10개는 건너뛰며, 이 결과와 별도 DB 통과를 구분한다. Mock HTTP 단위 2개는 기본 45개에 포함된다. 프런트 최종 빌드·TypeScript·프록시 17개와 응답 제어 `case-replacements-browser.py` 검사도 통과했다.

별도의 Spring/Mock DB와 실제 ML·Mock·Agent·Spring·상담사/고객 브라우저를 연결해 데스크톱·모바일 흐름을 확인했다. Mock의 성공 커밋 뒤 응답을 8초 지연시켜 Spring의 5초 읽기 제한을 넘긴 뒤, 같은 BRQ의 POST 1회·GET 1회로 CONFIRMED를 복구했다. 업무 예약 요청 1건·공급사 확정 1건, 선택한 A 숙소의 3박 재고는 `[0,0,0]`이었다. 같은 키 재생과 Mock 서버 재시작 뒤 동일 BRQ의 GET·POST 재생에서도 예약 수·재고는 바뀌지 않았다.

고객의 예약 접수는 차단하고 본인 결과 조회·새로고침 후 포인터 복구를 확인했다. 성공 뒤에도 사건은 OPEN, 입실 기록은 0건, 필수 업무는 기존 고객 연락과 새 입실 확인·원예약 후속의 3건으로 유지했다. 이는 합성 시연 내부의 전체 연결·정합성 검사이며 실공급사 성능·실제 입실·전체 프로젝트 완료를 증명하지 않는다. Windows에서도 Spring 단위 20개·전용 DB 15개, Mock 기본 45개·전용 DB 10개, 실행 JAR·프런트 빌드·별도 TypeScript·프록시 17개 검사가 통과했다. Windows 실제 브라우저 시연과 GitHub Actions 원격 실행은 아직 확인하지 않았다.

Spring 단위 검사는 `backend`에서 `.\gradlew.bat test --tests 'com.csro.backend.replacements.*' --tests '*LocalChatDemoAuthenticationTest'`로 실행한다. 쓰기 검사는 `REPLACEMENT_TEST=true`와 정확한 `REPLACEMENT_TEST_DB_URL=jdbc:postgresql://127.0.0.1:25444/yeohaengbom_replacements_test`, `REPLACEMENT_TEST_DB_USER=yb_replacement_test`, `REPLACEMENT_TEST_DB_PASSWORD`가 필요하다. Spring/Flyway 전에 설정과 실제 DB 신원을 확인한다. Mock 쓰기 검사는 별도 25443/yeohaengbom_mock_test·yb_mock_test만 사용한다. 시연 DB·일반 업무 DB를 테스트 DB로 쓰지 않는다.

프런트는 빌드·TypeScript 및 `node --test tests/demo-replacements-proxy.cjs tests/demo-offers-proxy.cjs tests/demo-quotes-proxy.cjs tests/demo-messages-proxy.cjs`로 확인한다. 실제 브라우저에서는 정상 확정, 응답 유실 후 동일 BRQ 복구, 새로고침 후 상태 재조회와 권한 분리를 확인한다. 응답 유실 시연 옵션·Mock 독립 검사는 [API-029/030 안내](../../../mock-server/docs/API-029-030.md)를 따른다.

입실·원예약 처리 완료·사건 종료와 종료 차단 조건은 [다음 단계 안내](CLOSURE-DEMO.md)에 연결했다. 실제 결제·환불·원예약 자동 취소·실공급사 운영 연동·운영 로그인·배포는 이번 범위에 포함하지 않는다.
