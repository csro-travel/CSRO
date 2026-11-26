# 상담사 안내 초안(다음 조치·고객 안내 초안·종료 요약) — 제안만 하는 최소 계약

**상태: 사용자가 2026-11-27 대화에서 LLM 호출 한 지점과 '추가만, 제안서를 먼저 쓰고 바로 구현'을 사전 승인.**

기준은 `1127cy-overnight`의 `a56ac4e`다. Notion 원본은 수정하지 않는다. 이 문서의 URI는 Notion API 목록에 없는 저장소 내부 계약이며 API 번호를 붙이지 않는다.

## 왜 필요한가

- 코치 기대는 "AI가 상담 요약과 권장 다음 조치를 준비하고 상담사가 판단한다"이다. 지금까지 저장소의 Agent는 규칙 기반 추출(API-024)과 비교(API-025)뿐이고 언어 모델 호출은 한 건도 없었다.
- 상담사는 사건 상세에서 숙박일 상태·남은 업무·대체 숙소 진행을 직접 읽어 다음 조치와 고객 안내 문장을 만든다. 저장된 사실만으로 초안을 준비해 주면 읽고 고쳐 쓰는 일만 남는다.
- 언어 모델 호출은 **이 한 지점**뿐이다. 상담사가 버튼을 눌렀을 때만 실행하고 자동으로는 호출하지 않는다.

## 변경 원칙(경계)

- **제안만 한다.** 사건 상태·업무·숙박일 사실·예약·제안·선택을 바꾸지 않는다. 메시지를 보내지 않는다. 결과는 상담사가 읽고 고쳐서 기존 API-012로 직접 전송한다.
- **검증된 사실만 사실로 말한다.** `factStatus=VERIFIED`인 숙박일만 사실로 쓴다. `UNCONFIRMED`는 "아직 확인 중", `CONFLICT`는 "근거가 엇갈려 확인 필요"로만 말하고 정상이라고 하지 않는다.
- **약속하지 않는다.** 환불·보상·결제·추가 부담 금액을 약속하거나 확정하지 않는다. 가상 예약 확정을 입실 완료로 말하지 않는다.
- **원문을 보내지 않는다.** Spring은 고객 신고 원문, 채팅 본문, 호텔 회신 원문을 읽지 않고 구조화된 사실만 Agent에 보낸다. 자유 문장은 숙소명과 상담사가 쓴 업무 완료 메모(500자까지)뿐이다.
- **실패해도 화면은 멈추지 않는다.** 언어 모델 키가 없거나 호출·검증에 실패하면 Agent가 같은 형식의 규칙 기반 결과를 돌려준다(`source: "RULES"`). Agent 자체에 닿지 못하면 Spring이 `503 ASSIST_UNAVAILABLE`을 반환하고 화면은 자체 기본 안내를 계속 보여 준다.
- **DB 변경 없음.** Flyway·테이블·제약·인덱스를 바꾸지 않는다. 기존 URI·필드·상태 코드·권한은 그대로이고 추가만 한다.
- 외부 HTTP는 DB 트랜잭션·행 잠금 밖에서 호출한다. 재시도하지 않는다.

## 공개 계약: `POST /api/v1/cases/{caseId}/assist-drafts`

상담사 전용이다. `Idempotency-Key`는 받지 않는다(업무 상태를 바꾸지 않으며 누를 때마다 새 초안을 만든다).

요청은 키 하나만 가진 JSON 객체다.

```json
{"kind":"GUIDANCE"}
```

`kind`는 `GUIDANCE`(다음 조치 + 고객 안내 초안) 또는 `CLOSURE_SUMMARY`(종료 요약)다.

성공은 `200`, `Cache-Control: private, no-store`다.

```json
{"caseId":"C-001","kind":"GUIDANCE","source":"RULES","model":null,
 "generatedAt":"2026-11-27T05:12:33.123456Z",
 "summary":"제주 오션뷰 호텔 예약 YB-001 건입니다. 11월 10일 숙박일은 객실 제공 불가로 확인됐습니다. 11월 11일, 11월 12일 숙박일은 아직 확인 중입니다.",
 "nextActions":["호텔에 직접 연락해 11월 11일, 11월 12일 숙박일의 객실 제공 여부를 확인하고 근거를 등록하세요.",
   "고객에게 확인된 사실만 안내하세요. 확인 중인 숙박일은 확인 중이라고 알리고, 금액이나 보상은 약속하지 마세요.",
   "대체 숙소를 검색해 조건이 맞는 후보를 고객에게 제안하세요."],
 "customerMessageDraft":"안녕하세요, 여행봄에서 제주 오션뷰 호텔 예약(YB-001) 관련 안내드립니다. …",
 "warnings":["확인되지 않은 숙박일이 있습니다. 정상 제공이라고 안내하지 마세요.",
   "제안 초안입니다. 상담사가 내용을 확인하고 고친 뒤 직접 전송하세요."]}
```

| 필드 | 뜻 |
| --- | --- |
| `source` | 언어 모델 결과를 실제로 채택했을 때만 `LLM`, 그 밖에는 `RULES` |
| `model` | `LLM`일 때의 모델명. `RULES`이면 `null` |
| `generatedAt` | Spring이 응답을 만든 UTC 시각. 감사 기록의 `acted_at`과 같다 |
| `summary` | `GUIDANCE`는 한두 문장 상황 요약, `CLOSURE_SUMMARY`는 3~5문장 처리 요약. 400자 이하 |
| `nextActions` | 상담사의 다음 조치. `GUIDANCE`는 1~4개(계약 상한 5개, 각 200자), `CLOSURE_SUMMARY`는 빈 배열 |
| `customerMessageDraft` | 고객 안내 초안(800자 이하). `CLOSURE_SUMMARY`는 `null` |
| `warnings` | 규칙 기반으로 대신한 이유(코드 수준 문장)와 상담사 주의 사항. 외부 응답 본문이나 예외 전문은 넣지 않는다 |

오류는 기존과 같은 `application/problem+json`(`code`, `traceId`)이다.

| 상태 | `code` | 조건 |
| --- | --- | --- |
| 400 | `INVALID_ASSIST_REQUEST` | 본문이 `kind` 하나만 가진 객체가 아니거나 값이 두 종류 밖 |
| 400 | `INVALID_CASE_ID` | 사건 번호 형식 오류 |
| 401 | `UNAUTHENTICATED` | 인증 주체 없음 |
| 403 | `FORBIDDEN_ROLE` | 활성 상담사가 아님 |
| 404 | `CASE_NOT_FOUND` | 사건 없음 |
| 503 | `ASSIST_UNAVAILABLE` | Agent에 연결할 수 없거나 응답이 계약과 다름. 아무것도 저장하지 않는다 |

### Spring이 만드는 사실

단일 SELECT 한 번으로 읽고, 읽기가 끝난 뒤에 Agent를 호출한다.

| 사실 | 읽는 곳 |
| --- | --- |
| `hotelName`·`bookingId`·`checkInDate`·`checkOutDate`·`supplierStatus` | `hotels`, `bookings` |
| `caseStatus`·`closeReason` | `cases` |
| `origin` | [사건 표시 제안서](CASE-INSIGHT-PROPOSAL.md)의 판정 SQL(`CaseQueries.ORIGIN_TYPE`)을 그대로 쓴다 |
| `nights` | `booking_nights`의 `stay_date`·`fulfillment_status`·`fact_status` |
| `openTasks` | `case_tasks` 중 `TODO`·`IN_PROGRESS`의 `task_type` |
| `completedTasks` | `case_tasks` 중 `DONE`의 `task_type`과 `completion_note`(500자까지) |
| `customerReportCount` | 이 사건의 `incident_reports` 건수. 신고 본문은 읽지 않는다 |
| `replacement` | 이 사건의 최신 `replacement_requests`의 `status`와 선택된 견적의 `property_name`. 없으면 `null` |
| `checkinConfirmed` | 이 사건에 `PENDING`이 아닌 `checkin_confirmations`가 있는지 |

Agent 설정은 견적 비교와 같은 서버를 쓰므로 기존 `quote.ranking.agent.base-url`·`service-token`을 그대로 읽는다. 새 필수 설정은 없다. 연결 제한 2초, 읽기 제한 25초다.

## 내부 계약: `POST /internal/v1/counselor-assist`

Python Agent(`llm/agent`)의 엔드포인트다. 인증과 오류 형식은 API-024·025와 같다(`AGENT_SERVICE_TOKEN` Bearer, problem+json).

```json
{"caseId":"C-001","kind":"GUIDANCE","facts":{
  "hotelName":"제주 오션뷰 호텔","bookingId":"YB-001","checkInDate":"2026-11-10","checkOutDate":"2026-11-13",
  "supplierStatus":"CONFIRMED","caseStatus":"OPEN","origin":"RISK_RECHECK",
  "nights":[{"stayDate":"2026-11-10","fulfillmentStatus":"UNAVAILABLE","factStatus":"VERIFIED"}],
  "openTasks":["CONTACT_CUSTOMER"],"completedTasks":[{"type":"VERIFY_HOTEL","note":"전화로 확인"}],
  "customerReportCount":0,"replacement":{"status":"CONFIRMED","propertyName":"A호텔"},
  "checkinConfirmed":false,"closeReason":null}}
```

- 모든 키가 필수이고 알 수 없는 키는 최상위·`facts`·중첩 객체 모두에서 거부한다(`400 INVALID_ASSIST_REQUEST`). 원문을 실수로 실어 보내면 요청 자체가 실패한다.
- 응답은 공개 응답에서 `generatedAt`만 뺀 여덟 필드다.
- 규칙 기반 생성기는 결정적이다. 같은 입력이면 같은 문장을 만든다.
- `OPENAI_API_KEY`가 있을 때만 `https://api.openai.com/v1/chat/completions`를 한 번 호출한다. 모델은 `ASSIST_LLM_MODEL`(기본 `gpt-4o-mini`), `temperature` 0.2, `max_tokens` 600, JSON 응답 형식, 제한 20초다. 시스템 지시문은 위 경계를 그대로 담고 사용자 메시지에는 `facts` JSON만 넣는다.
- 모델 응답은 세 키(`summary`·`nextActions`·`customerMessageDraft`)의 타입과 길이를 검증한다. 키가 없거나, 호출이 실패하거나, 검증에 실패하면 규칙 기반 결과를 반환하고 `warnings` 첫 줄에 이유를 적는다. 키·외부 응답 본문·예외 전문은 응답과 로그에 남기지 않는다.
- Spring은 Agent 응답을 다시 검증한다(식별자·`kind` 일치, `source`·`model` 조합, 타입, 길이). 맞지 않으면 `503 ASSIST_UNAVAILABLE`이다.

## 감사 기록

성공했을 때만 `case_actions`에 한 건을 남긴다. 이 기능의 유일한 쓰기다.

| 컬럼 | 값 |
| --- | --- |
| `action_type` | `ASSIST_DRAFT_GENERATED` |
| `actor_id` | 요청한 상담사 |
| `details` | `{"kind","source","model"}`만. 생성된 문장은 저장하지 않는다 |
| `acted_at` | 응답의 `generatedAt` |

`case_actions.action_type`에는 CHECK 제약이 없고(varchar 48), 유일 인덱스는 `CASE_CLOSED`에만 걸려 있어 기존 제약과 충돌하지 않는다. 기존 조회는 모두 `action_type`으로 걸러 읽으므로 이 행의 영향을 받지 않는다.

API-004 `analysisRuns`에 이 기록을 한 종류로 더한다(추가만). 기존 세 종류와 같이 `at` 오름차순으로 섞인다.

```json
{"kind":"COUNSELOR_ASSIST","status":"RULES","version":null,"at":"2026-11-27T05:12:33.123456+00:00"}
```

`status`는 `details.source`, `version`은 `details.model`이다.

## 격리 시연 경로 추가

- `local-chat-demo` 인증 필터는 `POST /api/v1/cases/{caseId}/assist-drafts`를 **상담사 토큰**으로만 `demo-counselor-001` 주체에 연결한다. 고객 토큰은 주체가 되지 않아 401이다. 기존 경로의 판정은 바꾸지 않는다.
- 상담사 프록시(`3100 → 18082`)는 `CSRO_ASSIST_DEMO_PROXY=true`일 때만 `POST /demo/cases/C-…/assist-drafts`를 전달한다. 기존 쓰기와 같이 `Origin`·`Sec-Fetch-Site: same-origin`·`Content-Type: application/json`을 검사하고 `Idempotency-Key`는 요구하지 않는다. 이 경로의 업스트림 제한만 30초이고 나머지는 5초 그대로다. 고객 프록시는 전달하지 않는다.
- 언어 모델을 쓰려면 Agent 프로세스 환경에 `OPENAI_API_KEY`를 설정한다. 설정하지 않아도 같은 화면이 규칙 기반으로 동작한다. 키는 저장소·로그·문서에 적지 않는다.

## 넣지 않는 것

- **자동 전송.** 초안을 채팅으로 보내거나 업무를 완료 처리하지 않는다. 전송은 상담사가 API-012로 한다.
- **생성 본문 저장.** 요약·조치·초안 문장은 DB에 남기지 않는다. 다시 보려면 다시 생성한다.
- **스트리밍.** 응답은 한 번에 반환한다.
- 자동 호출, 재시도, 결과 캐시, 사건 상태에 따른 호출 제한, 모델 응답의 의미 검증(금지 표현 탐지 등). 내용 경계는 지시문과 상담사 검토에 맡긴다.
- 고객 신고·채팅·호텔 회신 원문을 입력으로 쓰는 요약.
