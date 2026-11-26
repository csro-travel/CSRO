# 상담사 콘솔 조회 화면(요약·자동 확인·위험 평가·근거·회신 분석·예약) — 읽기 전용 최소 계약

**상태: 사용자가 2026-11-27 대화에서 '추가만, 제안서를 먼저 쓰고 바로 구현'을 사전 승인.**

기준은 `1127cy-overnight`의 `a56ac4e`다. Notion 원본은 수정하지 않는다. 이 문서의 URI는 Notion API 목록에 없는 저장소 내부 조회이며 API 번호를 붙이지 않는다.

## 왜 필요한가

- 상담사 화면 사이드바에는 사건 목록 외의 메뉴가 비어 있다. 자동 확인 작업, 위험 평가, 확인 근거, 호텔 회신 분석, 예약은 이미 업무 DB에 저장돼 있지만 사건 상세를 거치지 않고는 볼 방법이 없다.
- 시연 1의 결과가 "자동 확인 완료·사건 없음"이면 사건이 만들어지지 않는다. 사건 목록(API-003)과 상세(API-004)만으로는 상담사가 이 결과를 확인할 화면이 없다. [사건 표시 제안서](CASE-INSIGHT-PROPOSAL.md)도 이 화면을 범위 밖으로 남겼다.

## 변경 원칙

- **읽기 전용.** 모두 `GET`이고 SELECT만 실행한다. 업무 상태·작업·근거를 바꾸지 않고 외부 HTTP도 호출하지 않는다.
- **상담사 전용.** 활성 사용자이면서 `COUNSELOR`일 때만 응답한다. 미인증은 `401 UNAUTHENTICATED`, 다른 역할은 `403 FORBIDDEN_ROLE`이다. 오류는 기존과 같은 `application/problem+json`(`code`, `traceId`)이다.
- **DB 변경 없음.** Flyway·테이블·제약·인덱스를 바꾸지 않는다.
- **기존 계약 불변.** 기존 URI·응답 필드·상태 코드·권한·정렬·필터는 그대로다. 새 URI 6개만 추가한다.
- 모든 값은 저장된 행에서 읽는다. 없으면 `null` 또는 빈 배열이다. 응답은 `Cache-Control: private, no-store`다.
- 목록은 최신순, 최대 200건이다.

## 엔드포인트

### `GET /api/v1/console/overview`

```json
{"cases":{"open":1,"inProgress":0,"waiting":0,"closed":0,"urgentActive":1},
 "autoChecks":{"recheckJobs":1,"completed":1,"failed":0,"pending":0,"bookingsChecked":1,
   "bookingsAllAvailable":0,"bookingsWithUnavailable":1,"casesOpenedByRecheck":1},
 "risk":{"assessments":1,"selectedHotelDates":1},
 "analyses":{"hotelReply":0,"quoteRanking":0},
 "bookings":{"total":1}}
```

| 필드 | 읽는 곳 |
| --- | --- |
| `cases.*` | `cases.status`별 건수. `urgentActive`는 종료되지 않은 `URGENT` 사건 |
| `autoChecks.recheckJobs`·`completed`·`failed`·`pending` | `jobs`(`kind='RECHECK'`). `pending`은 `PENDING`·`RUNNING`·`WAITING` |
| `autoChecks.bookingsChecked` | 재확인 작업이 하나라도 있는 예약 수 |
| `autoChecks.bookingsAllAvailable` | 그중 모든 숙박일이 `VERIFIED`+`AVAILABLE`인 예약 수. 시연 1의 "자동 확인 완료·사건 없음"이 여기에 집계된다 |
| `autoChecks.bookingsWithUnavailable` | 그중 `VERIFIED`+`UNAVAILABLE` 숙박일이 있는 예약 수 |
| `autoChecks.casesOpenedByRecheck` | [사건 표시 제안서](CASE-INSIGHT-PROPOSAL.md)의 `origin` 규칙으로 `RISK_RECHECK`인 사건 수. 같은 SQL 조각을 쓴다 |
| `risk.assessments` | `risk_assessments` 행 수 |
| `risk.selectedHotelDates` | `jobs.risk_assessment_id`가 가리키는 서로 다른 평가 수 |
| `analyses.*` | `agent_analyses`, `quote_rankings` 행 수 |
| `bookings.total` | `bookings` 행 수 |

### `GET /api/v1/console/recheck-jobs`

`jobs` 중 `kind='RECHECK'`만 `created_at` 최신순으로 반환한다.

```json
{"items":[{"jobCode":"RISK-1-1","trigger":"SCHEDULED","status":"COMPLETED","bookingId":"YB-001",
  "hotelName":"제주 오션뷰 호텔","checkInDate":"2026-11-10","checkOutDate":"2026-11-13","caseId":"C-001",
  "riskScore":0.020195162905928096,"threshold":0.008907415636863826,"policy":"MAX_OBSERVED_RECALL",
  "failedAttempts":0,"errorCode":null,"manualRequired":false,
  "createdAt":"…","finishedAt":"…",
  "nights":[{"stayDate":"2026-11-10","fulfillmentStatus":"UNAVAILABLE","factStatus":"VERIFIED"}]}]}
```

- `caseId`는 `jobs.case_id`의 사건 번호이고 연결된 사건이 없으면 `null`이다.
- `riskScore`·`threshold`·`policy`는 `parameters.selection`의 `rawScore`·`threshold`·`policy`다. 고객 신고로 만든 작업에는 선별 정보가 없어 `null`이다.
- `failedAttempts`는 `result_payload.history`의 길이(저장 코드가 최근 10건까지 보존), `errorCode`는 `result_payload.last.errorCode`, `manualRequired`는 `result_payload.manualRequired`다. 결과가 아직 없으면 각각 `0`, `null`, `null`이다.
- `nights`는 그 예약의 **현재** 숙박일 상태다. 작업 완료 시점의 스냅샷이 아니다.

### `GET /api/v1/console/risk-assessments`

`risk_assessments`를 `created_at` 최신순으로 반환한다.

```json
{"items":[{"assessmentId":1,"hotelId":"HOTEL-001","hotelName":"제주 오션뷰 호텔","targetCheckInDate":"2026-11-10",
  "asOf":"…","score":0.0202,"modelVersion":"process-synthetic-lightgbm-0.2-84d386281d22",
  "featureSetVersion":"risk-features-0.2","features":{"pastFailureRate90d":0.05,"isPeakDay":false},
  "assessedAt":"…","selected":true,
  "selections":[{"policy":"MAX_OBSERVED_RECALL","threshold":0.008907415636863826,"rawScore":0.020195162905928096}],
  "recheckJobs":1,"completedRechecks":1,"casesLinked":1}]}
```

- `score`는 `risk_assessments.risk_score`(소수 넷째 자리 반올림)다. 임계값과 비교한 원값은 `selections[].rawScore`에 있다.
- `selected`는 이 평가를 가리키는 작업이 있는지다. `selections`는 그 작업들의 `parameters.selection`에서 `policy`·`threshold`·`rawScore`를 중복 없이 모은 것이다.
- `recheckJobs`·`completedRechecks`는 이 평가를 가리키는 재확인 작업 수와 그중 `COMPLETED` 수, `casesLinked`는 그 작업들에 연결된 서로 다른 사건 수다.
- **호텔×체크인 날짜의 위험 신호이며 객실 미제공 확정이 아니다.** 화면도 이 구분과 함께 표시한다.

### `GET /api/v1/console/evidence`

`evidence_records`를 `received_at` 최신순으로 반환한다.

```json
{"items":[{"evidenceId":"EV-2","bookingId":"YB-001","hotelName":"제주 오션뷰 호텔","sourceType":"HOTEL_API",
  "sourceAuthenticated":false,"contentFormat":"STRUCTURED","originalContent":"{…}",
  "occurredAt":"…","receivedAt":"…","jobCode":"RISK-1-1","trigger":"SCHEDULED",
  "assertions":[{"stayDate":"2026-11-10","reportedProvisionStatus":"UNAVAILABLE","reviewStatus":"ACCEPTED"}]}]}
```

- `contentFormat`·`originalContent`·`assertions`는 API-004 상세의 `evidence`와 같은 규칙이다.
- `jobCode`·`trigger`는 `evidence_records.job_id`가 가리키는 작업이고, 작업 없이 저장된 근거(수동 확인 등)는 `null`이다.

### `GET /api/v1/console/hotel-reply-analyses`

`agent_analyses`를 `analyzed_at` 최신순으로 반환한다.

```json
{"items":[{"analysisId":1,"evidenceId":"EV-3","bookingId":"YB-002","hotelName":"…","status":"AMBIGUOUS",
  "modelVersion":"hotel-reply-extractor-0.1","analyzedAt":"…","originalMessage":"…",
  "claims":[{"stayDate":"2026-11-10","reportedProvisionStatus":"UNAVAILABLE","supportingText":"…"}],
  "unresolvedStayDates":["2026-11-11"],"warnings":[]}]}
```

- `status`는 `analysis_status`(`EXTRACTED`, `AMBIGUOUS`), `originalMessage`는 분석 대상 근거의 `raw_text`다.
- `claims`·`unresolvedStayDates`·`warnings`는 `result_payload`의 같은 이름 키다. `result_payload`는 API-024 응답 본문 그대로이며 키가 없으면 빈 배열이다.
- 호텔 주장을 추출한 결과이며 검증된 사실이 아니다. 화면에서 숙박일 확정 상태처럼 표시하지 않는다.
- 위 예시는 계약 형태를 보이기 위한 것이다. 시연 DB에 저장된 분석 행이 없어 실제 응답은 `{"items":[]}`만 확인했다.

### `GET /api/v1/console/bookings`

`bookings`를 `created_at` 최신순으로 반환한다.

```json
{"items":[{"bookingId":"YB-001","hotelName":"제주 오션뷰 호텔","customerName":"가상 고객 1",
  "checkInDate":"2026-11-10","checkOutDate":"2026-11-13","adults":2,"rooms":1,"supplierStatus":"CONFIRMED",
  "originalPrice":{"amount":450000,"currency":"KRW"},
  "nights":[{"stayDate":"2026-11-10","fulfillmentStatus":"UNAVAILABLE","factStatus":"VERIFIED"}],
  "activeCaseId":"C-001","latestCaseId":"C-001","recheckJobs":1}]}
```

- `customerName`은 `users.display_name`이다. 상담사 전용 응답이므로 반환한다.
- `activeCaseId`는 `OPEN`·`IN_PROGRESS`·`WAITING` 사건(예약당 최대 1건), `latestCaseId`는 종료 건을 포함해 가장 최근에 만든 사건이다. 없으면 `null`이다.
- `recheckJobs`는 이 예약의 재확인 작업 수다.

## 격리 시연 경로 추가

- `local-chat-demo` 인증 필터의 적용 범위에 `/api/v1/console/*`를 더하고, 위 6개 경로의 `GET`만 **상담사 토큰**으로 `demo-counselor-001` 주체에 연결한다. 고객 토큰은 이 경로에서 주체가 되지 않아 401이다. 기존 경로의 판정은 바꾸지 않는다.
- 상담사 프록시(`3100 → 18082`)는 `GET /demo/console/{overview|recheck-jobs|risk-assessments|evidence|hotel-reply-analyses|bookings}`만 추가로 전달한다. 쿼리 문자열은 허용하지 않고, 기존 읽기와 같이 `X-Demo-Read: 1`과 `Sec-Fetch-Site: same-origin`을 요구한다. 고객 프록시(3101)는 403이다.

## 넣지 않는 것

- **페이지네이션.** 시연 규모에서는 최신 200건으로 충분하다. 운영 규모에서는 커서가 필요하다.
- **필터·검색·정렬 옵션.** 쿼리 파라미터를 받지 않는다. 화면에서 받은 목록을 걸러 본다.
- **쓰기.** 작업 재실행, 근거 검토, 분석 실행, 예약 수정은 이 경로에 없다. 기존 API-006·API-024 등 각자의 계약을 따른다.
- 견적 순위 상세와 고객 신고 본문. 각각 API-009와 API-011에서 읽는다.

## 영향 범위

| 영역 | 변경 |
| --- | --- |
| Spring | 신규 `console/ConsoleController.java`·`ConsoleQueries.java`, `cases/LocalChatDemo.java`의 시연 인증 경로, `cases/CaseQueries.java`의 `origin` 판정 조각 공유 |
| 프런트 | `demo-server.mjs`의 상담사 읽기 허용 경로. 화면은 별도 작업 |
| 문서 | `docs/api.md`, `backend/docs/api/README.md` |
| DB·Flyway·ML·Agent·Mock | 변경 없음 |

## 검증

2026-11-27 Windows 실행 환경에서 다음을 확인했다.

- **SQL:** 격리 시연 DB(C-001, YB-001, 재확인 작업 1건, 평가 1건)에 6개 조회 SQL을 읽기 전용 트랜잭션으로 실행해 위 예시의 값을 얻었다.
- **자동 검사:** `ciUnitTest`(시연 인증 필터의 상담사·고객 경계 포함), `demo-closure-proxy.cjs`(상담사 전달·고객 차단·쿼리 차단).
- **미검증:** Spring을 띄운 HTTP 호출과 브라우저 화면. 호텔 회신 분석은 저장된 행이 없어 빈 목록만 확인했다.
