# API-015/016·Mock 예약 저장소 — 사용자 승인 최소 구현 계약

기준은 사용자 브랜치 `1124cy-core-flow`의 `c1a76d8`이다. [Notion E2E](https://app.notion.com/p/cfb2f2597c57821bb16e014bdc22c147), [API-015](https://app.notion.com/p/65c2f2597c5783ed840781084c7161e3), [API-016](https://app.notion.com/p/0d22f2597c57833587170114a992fb2c), [API-029](https://app.notion.com/p/a862f2597c5783e1b7af81b43a757fb0), [API-030](https://app.notion.com/p/1312f2597c5783f89deb811742816432), [업무 예약 ERD](https://app.notion.com/p/fd42f2597c578371b09301096d923d57), [별도 Mock ERD](https://app.notion.com/p/99b2f2597c578398a54e816c1e8975d2)를 대조했다. 015/016의 상세 JSON 공백과 029/030 v0.1 검토안을 보충하는 최소 계약을 사용자가 이 대화에서 승인했다. Notion의 팀 전체 승인·진행 상태를 바꾸지 않는다. [실행·검증 범위](REPLACEMENT-DEMO.md)

## 목표와 영향

현재 고객 선택을 상담사가 가상 공급사 예약으로 실행하고, 응답 유실 후에도 같은 요청번호로 결과를 확인한다. 예약 성공은 입실·원예약 취소·사건 종료를 실행하지 않는다. 기존 Spring V1·일반 인증·ML·업무 DB 소유권을 유지한다.

승인한 변경 영역은 Spring `replacements` 명령/읽기/Worker, 기존 메시지 조회·격리 인증, 상담사/고객 UI와 프록시, Mock의 선택적 PostgreSQL 모드, 별도 Compose·설치·CI·검증 안내다. A팀 Mock과 양 팀 Spring/UI 실행에 미치는 영향을 이 범위로 제한한다.

## 1. API-015 — 상담사 예약 접수

`POST /api/v1/cases/{caseId}/replacement-bookings`

활성 COUNSELOR만 사용한다. JSON과 필수 `Idempotency-Key`(기존 `[A-Za-z0-9._:-]{1,100}`)를 받는다. 가격·고객·기간·견적·판단 시각은 클라이언트 입력으로 받지 않는다.

```json
{"selectionId":"SEL-example"}
```

서버는 사건+클라이언트 키의 SHA256으로 `BRQ-`+64자리 해시를 유도한다. 이 68자 값을 기존 `replacement_requests.request_code`와 `idempotency_key`에 함께 저장해 varchar(80) 및 두 값의 동일성 제약을 보존한다. 공개 요청 해시는 정규화한 selectionId 기준이며, 최초 검증 때 공급사 본문을 별도 불변 스냅샷으로 동결한다.

현재 고객 선택·ACTIVE 발송 제안·선택 견적의 상품/기간/성인/객실/KRW/유효 시각을 확인하고 PROCESSING 원장을 먼저 저장한다. 인증→booking→case→replacement 잠금→키 재생→신규 요청 검증 순서다. 외부 HTTP는 접수 트랜잭션에서 호출하지 않는다.

최초와 같은 키·본문 재생은 불변 `202` 및 `Location: /api/v1/replacement-bookings/{BRQ}`:

```json
{"caseId":"C-001","selectionId":"SEL-example","replacementBookingId":"BRQ-example","acceptedAt":"2026-11-24T12:00:00Z"}
```

`replacementBookingId`는 ALT 공급사 예약번호가 아니라 결과 미확정 때부터 존재하는 BRQ 접수번호다. 가변 status는 접수 응답에 넣지 않고 API-016에서 읽는다. 만료·선택 교체·사건 종료 후에도 같은 키는 최초 결과만 재생한다.

같은 키·다른 selection은 `409 IDEMPOTENCY_KEY_REUSED`. 같은 selection의 다른 키는 새 예약을 만들지 않고 `409 SELECTION_ALREADY_REQUESTED`와 기존 replacementBookingId를 반환한다. 그 키를 기록하지 않고 최초 성공인 것처럼 재생하지 않는다. 이전 PROCESSING/RESULT_UNKNOWN/CONFIRMED 요청은 새 실행을 막는다. 확정 FAILED 이후에는 기존 실패를 보존하고 새 검색→제안→고객 선택으로만 재접수한다.

기본 오류는 400 INVALID_REPLACEMENT_REQUEST/INVALID_CASE_ID, 401 UNAUTHENTICATED, 403 FORBIDDEN_ROLE, 404 CASE_NOT_FOUND, 409 CASE_CLOSED/CURRENT_SELECTION_CHANGED/QUOTE_INVALID/QUOTE_EXPIRED/REPLACEMENT_LOCKED 및 위 멱등 충돌, 415 UNSUPPORTED_MEDIA_TYPE, 500 INTERNAL_ERROR다. 기존 problem+json/code/traceId를 따른다.

## 2. API-016 — 저장된 처리 상태만 조회

원래 경로 `GET /api/v1/replacement-bookings/{replacementBookingId}`를 사용한다. 활성 상담사 또는 원예약 소유 고객만 조회한다. 타인 요청과 없는 요청은 같은 404다. 종료 사건의 기록도 읽을 수 있다. 잘못된 BRQ 형식은 400 INVALID_REPLACEMENT_ID, 없는 기록은 404 REPLACEMENT_BOOKING_NOT_FOUND다.

```json
{
  "replacementBookingId":"BRQ-example","caseId":"C-001","selectionId":"SEL-example",
  "offerId":"OFFER-example","quoteId":"QUOTE-example","status":"RESULT_UNKNOWN",
  "stay":{"checkInDate":"2026-11-10","checkOutDate":"2026-11-13","adults":2,"rooms":1},
  "expectedPrice":{"totalAmount":480000,"currency":"KRW"},
  "supplierBookingId":null,"confirmedPrice":null,"confirmedAt":null,
  "failureCode":null,"currentPrice":null,"manualRequired":false,"nextCheckAt":null,
  "updatedAt":"2026-11-24T12:00:05Z","isSynthetic":true,
  "effectiveAt":"2026-11-09T10:20:00+09:00","clockMode":"SYNTHETIC_DEMO"
}
```

status는 기존 PROCESSING/RESULT_UNKNOWN/CONFIRMED/FAILED다. 확정 때만 예약번호·확정금액·confirmedAt을 채운다. 확정 실패의 failureCode와 PRICE_CHANGED의 검증된 currentPrice를 제공한다. manualRequired/nextCheckAt은 Worker 처리 기록에서 읽는다. 내부 PK·토큰·고객 개인정보·공급사 원문은 응답에 넣지 않는다.

GET은 업무 DB의 일관된 읽기만 수행하며 공급사 호출·선점·결과 저장·예약 실행을 하지 않는다. 클라이언트는 3초 간격 최대 30회 순차 조회 후 수동 새로고침으로 전환한다. 읽기 404/5xx를 예약 FAILED로 해석하지 않는다.

API-011에 `latestReplacementBooking: null|{replacementBookingId,selectionId}` 포인터만 추가한다. 사건의 최신 접수를 현재 선택 교체 뒤에도 찾을 수 있어 다른 브라우저·새로고침이 016을 재개할 수 있다. 결과 상세는 중복하지 않는다. 새 case 조회 URI를 만들지 않는다.

## 3. Spring 처리·복구 — V1 유지

현재 jobs.kind에는 RECHECK/QUOTE_SEARCH만 있어 예약 작업으로 위장하지 않는다. 기존 replacement_requests를 원장 겸 처리 대상으로 사용한다. request_payload에는 최초 API-029 본문을 그대로 보존하고, 검증된 공급사 결과는 response_payload에 저장한다. case_actions.details의 버전된 `replacement-booking-v1` 기록에 최초 맥락·실제 처리 시계·phase/attempt/claimToken/leaseUntil/nextAttemptAt/deadlineAt/manualRequired를 남긴다. 공급사 관측 원문은 감사 기록에도 append-only로 보존한다.

동일 booking→case→replacement 잠금에서 최신 claim을 확인하고 기록한 뒤 커밋한다. HTTP 결과 반영 때 다시 같은 순서로 잠그고 최신 토큰만 반영한다. 오래된 실행자는 상태·채팅·필수 업무를 바꾸지 못한다. 확정 결과는 후속 조회 실패로 되돌리지 않는다.

격리 local-chat-demo에서 명시적으로 켠 단일 Worker만 자동 실행한다. 기본 2초 간격, 회당 최대 10건, HTTP connect 2초/read 5초, claim lease 45초, 실제 처리 기한 300초다. 최초 POST는 intent를 먼저 영속화한다. 응답 유실·503·타임아웃·검증 불가 응답은 RESULT_UNKNOWN으로 보존하고 동일 BRQ의 GET-030을 수행한다.

GET은 최대 8회, 간격 2/4/8/16/30초 상한으로 제한한다. 인증된 030이 BOOKING_REQUEST_NOT_FOUND를 반환했을 때에만 **동결한 동일 본문·동일 BRQ로 POST를 최대 2회 추가 재시도**할 수 있다. 이는 최초 전송 전 프로세스 중단도 복구하기 위한 정책이다. 새 요청번호·새 가격·새 고객 선택으로 대체하지 않는다. Mock의 동일 키 원자 처리로 진행 중 요청과 경합해도 한 번만 차감한다. 그 밖의 조회 실패에는 POST하지 않는다.

한도·기한 소진 또는 데이터 불일치 때는 RESULT_UNKNOWN/manualRequired를 유지하고 새 예약을 잠근다. 명시적 내부 수동 복구 진입점은 같은 BRQ의 조회만 재개할 수 있다. GET-016이나 재접수 버튼이 한도를 초기화하지 않는다. 기존 견적이 나중에 만료되거나 사건이 종료되어도 이미 실행된 요청의 결과 확인은 계속 가능하다.

CONFIRMED 반영·SYSTEM 예약번호/기간 안내·감사·Worker 종결 및 필요한 필수 TODO `CHECKIN_CONFIRM`/`ORIGINAL_FOLLOWUP` 생성·재사용을 한 트랜잭션으로 처리한다. 이 task_type 명칭은 기존 ERD-03 예시다. 다른 필수 업무나 CONTACT_CUSTOMER를 자동 DONE으로 만들지 않는다. 원호텔 booking_nights·공급사 CONFIRMED 상태도 대체 예약 성공으로 덮어쓰지 않는다.

## 4. 별도 Mock PostgreSQL과 기존 모드 보존

기존 compose.yaml과 Spring 업무 DB는 유지한다. 별도 `compose.mock.yaml`에 loopback `127.0.0.1:25442/yeohaengbom_mock`, 사용자 `yb_mock`, 별도 비밀번호·볼륨을 명시적으로 실행한다. Mock Python만 접속하고 Spring은 기존 Mock HTTP로만 호출한다. 새로운 Mock 전용 마이그레이션 경로와 선택적 psycopg 의존성을 둔다.

ERD-06의 4개 테이블 mock_products/mock_night_inventory/mock_quotes/mock_booking_requests와 원래 FK·CHECK·UNIQUE를 사용한다. 별도 bookings 테이블을 invent하지 않는다. 설정 및 실제 DB/사용자 신원을 DDL 전에 검사한다. 초기 시드는 빠진 상품·숙박일만 준비하며 재시작으로 재고·가격·예약을 초기화하지 않는다. 스키마 불일치 자동 수선·기존 데이터 삭제는 하지 않는다. 상품 메타데이터는 이 시연에서 불변으로 둔다.

기본 Mock 실행은 지금처럼 DB 없이 026/027/028을 제공한다. 상태 저장 모드만 명시적으로 켜면 028이 최신 전일 재고·숙박일별 단가를 읽고 발급 견적을 Mock DB에 불변 저장한다. 기존 응답 JSON·검색별 스냅샷 ID 규칙을 유지하며 정확한 초기 SEARCH-001만 QUOTE-A/B/C다. 과거 stateless 견적을 Spring DB에서 몰래 복사하지 않는다. 전환 후 새 검색→제안→선택을 실행해야 한다.

029는 요청 키 잠금→견적→숙박일 오름차순 재고 잠금을 사용한다. 기존 키·본문은 만료·재고 재검사 전에 최초 HTTP 결과를 재생한다. 신규 성공은 전일 차감+ALT 번호+CONFIRMED 요청/201 원문을 같은 Mock DB 트랜잭션으로 저장한다. 알려진 견적의 가격 변경·만료·품절은 재고를 바꾸지 않고 FAILED/최초 오류를 커밋한다. HTTP 오류는 커밋 이후 생성한다.

모든 Mock 재고·예약 원자성은 Mock DB 안의 원자성이며 Spring과 분산 트랜잭션을 만들지 않는다. 선택한 견적의 기간·인원·상품·KRW 기대 총액을 그대로 029에 보내고 서비스 Bearer를 사용한다. guestReference는 개인정보 없는 안정된 가상 식별자다.

## 5. 원본의 공백과 보충

- ERD는 Mock 요청에 CONFIRMED/FAILED만 허용한다. 따라서 최소 029 구현은 최종 결과를 동기 커밋하고 030은 최종 행을 읽는다. 진행 중 미커밋 요청은 404일 수 있다. PROCESSING 응답은 030 클라이언트가 지원하되 Mock에 새 영속 상태를 추가하지 않는다.
- quote_id가 필수 FK이므로 모르는 견적의 404 QUOTE_NOT_FOUND는 접수 전 거부이며 030 행이 없다. 처음 받은 인증된 029 거부와 응답 유실 후의 030 404는 구분한다. 후자는 신규 키 생성 근거가 아니다.
- 하나의 발급 견적은 부분 UNIQUE상 한 번만 확정할 수 있다. 다른 키로 이미 예약된 같은 견적을 요청하면 `409 QUOTE_ALREADY_BOOKED`를 추가한다. 다른 요청의 예약번호를 채택하거나 실제 남은 재고가 없다고 오안내하지 않는다.
- 030 FAILED JSON에는 기존 requestId/status/quoteId/propertyId/nullable 성공 필드 외에 `failureCode`, `failedAt`를 보충한다. PRICE_CHANGED만 currentTotalAmount/currency를 추가할 수 있다. 실패 시각과 최초 결과는 저장된 값을 읽으며 GET 때 만들지 않는다.
- 가상 시각은 상태 저장 DB와 loopback 실행을 확인한 서버의 명시적 설정만 사용한다. Spring의 기존 quote.search.demo-as-of와 같은 시각으로 설정한다. 클라이언트 본문·헤더로 시계를 바꾸지 않는다. claim·기한·감사는 실제 시계를 사용한다.
- 커밋 후 응답 유실은 서버 전용 시연 옵션으로 최초 성공 커밋 뒤 응답만 지연해 재현한다. 저장 결과 재생에는 지연하지 않는다. 가격·재고 변경은 격리된 검사/내부 시연 도구에서만 Mock DB에 적용하며 공개 관리자 API를 만들지 않는다.

## 6. 화면·시연 인증과 검증

API-011 포인터→016으로 현재 상태를 읽고 상담사만 [대체 예약 진행]을 사용한다. 결과 불명은 '예약 결과 확인 중', 성공은 '가상 예약 확정·입실/원예약 후속 별도', 확정 실패는 '예약 미생성 확인·새 견적 필요'로 표시한다. 현재 '선택 완료·예약 진행 전' 고정 문구도 실제 처리 상태에 맞춘다. 결과가 불명·처리 중·확정이면 선택 교체를 잠그며, 같은 실패 제안의 다른 항목 선택으로 새 예약을 우회하지 않는다.

기존 상담사/고객 토큰과 3100/3101→18082를 유지한다. `CSRO_REPLACEMENT_DEMO_PROXY=true`에서 상담사에게만 정확한 POST-015, 양 역할에게 정확한 GET-016을 추가 허용한다. LocalChatDemo는 016의 별도 경로 등록 및 역할 주체 연결을 추가하며 최종 소유권은 서비스가 검사한다. 기존 Host/Origin/Sec-Fetch/멱등키 검사·서버 토큰 보관·정상 인증 경계를 유지한다.

검증은 전용 테스트 DB에서 신원 확인 후 수행한다. 같은 키 동시 요청·서로 다른 검색의 마지막 객실 경쟁·중간 숙박일 품절의 전일 미차감·가격/만료 실패 재생·동일 quote 다른 키·서버 재시작 후 결과 보존·성공 커밋 후 HTTP 유실→동일 BRQ 재조회·dispatch 전 중단→동일 BRQ 재시도·stale claim 차단·고객 대리 실행/타인 조회 차단·016 읽기 무변경·필수 업무/채팅 원자 저장과 실제 두 브라우저 흐름을 확인한다. 기존 stateless Mock도 영향 범위에서 회귀 검사한다.

구조를 유지하는 대안은 현행 읽기 전용 Mock에서 예약 이전 시연까지만 제공하는 것이다. 메모리/SQLite/업무 DB 재사용은 원본의 PostgreSQL 행 잠금·재시작 복구·소유권·원자 재고 조건을 충족하는 대안으로 사용하지 않는다. 이번 승인 범위에는 실제 결제·환불·원예약 자동 취소·입실/사건 종료 API 구현·운영 배포가 포함되지 않는다.
