# 상담 대응 건의 발생 이유·위험 신호·자동 처리 이력·종료 결과 표시 — 최소 계약 제안

**상태: 사용자가 2026-11-26 대화에서 아래 "승인받을 항목" 4가지를 승인했다. 구현은 PR #21·#22 병합 뒤에 시작한다.**

기준은 `main`의 `030a35b`이고, PR #21(API-006)과 #22(목록·상세 보강)가 병합된 뒤 그 위에 구현한다. Notion 원본은 수정하지 않는다.

## 대조한 원본

2026-11-26에 아래를 직접 읽고 이 제안의 모든 필드를 대조했다.

- Notion [API-003 상담사 사건 목록 조회](https://app.notion.com/p/417e04d22bed814cae30c2e4b8c3af99), [API-004 상담사 사건 상세 조회](https://app.notion.com/p/417e04d22bed81aa9d5afcd7a4f9ff85). 둘 다 설계 상태 "진행 중"인 팀 간 DTO 검토안이다.
- Flyway `V1__initial_business_schema.sql`의 `cases`, `case_tasks`, `case_actions`, `jobs`, `risk_assessments`, `agent_analyses`, `quote_rankings`, `evidence_records`.
- 값을 저장하는 코드: `IncidentReportService`, `IncidentRecheckJobs`, `RiskRecheckJobs`, `BookingReconfirmationEvidenceService`, `HotelReplyAnalysisStore`, `QuoteSearchService`, `ClosureService`.
- 격리 시연 DB에 저장된 실제 행(C-001 선제 탐지 후 종료, C-002 고객 신고 후 재확인 실패).

원본에서 확인해 제안에 반영한 제약은 다음과 같다.

| 원본 문장 | 반영 |
| --- | --- |
| API-003: "`summary`는 검증된 사실을 요약한 문구이지 ML 점수나 AI의 미검증 주장이 아니다", "목록은 … 검증된 문제 요약만 제공" | 목록에 위험 점수를 넣지 않는다. `summary`와 정렬은 그대로 둔다 |
| API-004: "고객 메시지 원문은 API-011에서 다룬다" | `customerReports`에 신고 본문을 추가하지 않는다 |
| API-004: "사건 상세에 둘(후보·채팅)을 중복 포함하지 않는다" | 자동 처리 이력은 실행 요약만 싣고 견적 후보·원문은 싣지 않는다 |
| API-003/004: "DB 변경 없음", "쓰기 없음" | 조회 SQL만 바꾼다 |

## 왜 필요한가

[6주차 멘토링 주간보고](https://app.notion.com/p/41de04d22bed8135996ecfcc93f52499)의 다음 주 계획은 상담사가 사건을 열면 문제 날짜·호텔 회신·연락할 일·요약을 실제 저장된 결과로 확인하는 것이다. 아래 값은 DB에 저장돼 있지만 API-003/004가 내려주지 않아 화면에 표시할 수 없다.

| 화면에 필요한 것 | 이미 저장된 곳 | 현재 응답 |
| --- | --- | --- |
| 사건이 올라온 이유 | `case_actions`, `evidence_records.job_id`, `jobs.trigger_type` | 없음. 프런트가 문장을 추정해 만든다 |
| 위험 점수와 판단 근거 | `jobs.parameters.selection`, `risk_assessments` | 없음 |
| 자동 처리 이력(재확인, 호텔 회신 분석, 견적 순위) | `jobs`, `agent_analyses`, `quote_rankings` | 없음 |
| 종료 사유와 완료한 업무 | `cases.close_reason`·`closed_at`, `case_tasks`의 DONE 행 | 없음. `tasks`는 미완료만 반환한다 |

## 변경 원칙

- 기존 필드의 이름·타입·의미는 바꾸지 않고 **추가만** 한다. 추가 필드를 모르는 클라이언트는 그대로 동작한다.
- Flyway·테이블·제약 변경이 없다. 새 URI·새 쓰기 경로·외부 HTTP 호출도 없다. GET은 계속 읽기만 한다.
- 상담사 전용인 API-003/004에만 추가한다. 고객이 읽는 API-001·API-011은 바꾸지 않는다.
- 모든 값은 저장된 행에서 읽는다. 값이 없으면 `null` 또는 빈 배열이며, 화면용 값을 지어내지 않는다.

## API-004 사건 상세에 추가하는 필드

### `origin` — 사건이 올라온 이유

```json
"origin": {"type": "RISK_RECHECK"}
```

`cases`에는 발생 경로 컬럼이 없다. 사건을 만드는 코드는 두 곳뿐이고(`IncidentReportService`, `BookingReconfirmationEvidenceService`), 두 곳이 남기는 기록으로 아래 순서로 판정한다.

| 순서 | 조건(저장된 기록) | `type` |
| --- | --- | --- |
| 1 | 이 사건의 `case_actions`에 `action_type='CUSTOMER_REPORT_RECEIVED'`이고 `details.caseCreated=true`인 행이 있다 | `CUSTOMER_REPORT` |
| 2 | 이 예약의 `evidence_records` 중 `created_at`이 `cases.created_at`과 같은 행이 있고, 그 행의 `job_id`가 가리키는 `jobs.trigger_type`이 `SCHEDULED`다 | `RISK_RECHECK` |
| 3 | 2와 같되 `trigger_type`이 `CUSTOMER_REPORT`다 | `CUSTOMER_REPORT` |
| 4 | 그 밖(시드로 넣은 사건, 작업 없이 실행한 수동 재확인 등) | `UNKNOWN` |

- **1번의 근거:** 고객 신고가 사건을 새로 만들면 같은 트랜잭션에서 `caseCreated=true`를 감사 기록에 남긴다. 기존 사건에 붙은 추가 신고는 `false`다.
- **2번의 근거:** 재확인 근거 저장과 사건 생성은 한 트랜잭션이고 두 테이블의 `created_at`은 모두 DB 기본값 `now()`라서 같은 값이 된다. 시연 DB의 C-001에서 사건과 근거 2건의 시각이 같고 작업이 `SCHEDULED`인 것을 확인했다.
- **초안에서 바꾼 점:** 초안은 "사건에 연결된 신호 중 가장 이른 것"으로 판정했다. 이 규칙은 고객 신고로 생긴 사건에 먼저 시작된 위험 재확인 작업이 나중에 연결되면 `RISK_RECHECK`로 잘못 답한다(작업은 완료 시점의 활성 사건에 연결된다). 그래서 생성 시점에 남는 기록으로 바꿨다.
- **한계:** 2·3번은 근거 저장과 사건 생성이 한 트랜잭션이라는 구현에 기대고 있다. 이 구조가 바뀌면 틀린 값이 아니라 `UNKNOWN`으로 떨어진다. 발생 경로를 컬럼으로 저장하는 것이 더 단단하지만 스키마 변경이라 이번 범위에서 제외했다.
- 사건이 생긴 뒤 들어온 추가 신고는 `origin`을 바꾸지 않고 기존 `customerReports`에 쌓인다.

### `createdAt` — 사건 생성 시각

`cases.created_at`을 반환한다. 목록에는 #22에서 이미 추가했고 상세에는 없다.

### `risk` — 위험 신호

```json
"risk": {
  "score": 0.020195162905928096, "threshold": 0.008907415636863826, "policy": "MAX_OBSERVED_RECALL",
  "modelVersion": "process-synthetic-lightgbm-0.2-84d386281d22", "featureSetVersion": "risk-features-0.2",
  "targetCheckInDate": "2026-11-10", "asOf": "2026-11-09T00:00:00Z", "assessedAt": "2026-11-26T12:30:15.879083Z",
  "features": {"pastFailureRate90d": 0.05, "targetConfirmedBookingCount": 1, "bookingVolumeGrowth7d": 0.0,
    "reconfirmationOverdueRate7d": 0.0, "inventoryUnavailableRate7d": 0.0, "isPeakDay": false}
}
```

- 이 사건에 연결된(`jobs.case_id`) 작업 중 `risk_assessment_id`가 있는 `RECHECK` 작업을 읽는다. 여러 건이면 가장 최근에 만든 작업을 쓴다. 없으면 `null`이다.
- `score`·`threshold`·`policy`·`modelVersion`과 `featureSetVersion`·`targetCheckInDate`·`asOf`·`features`는 그 작업의 `parameters.selection`에서 읽는다. 선별할 때 실제로 비교한 값이 여기에 고정 저장된다(`RiskRecheckJobs`의 `Selection` 레코드). `assessedAt`은 `risk_assessments.created_at`이다.
- `risk_assessments.risk_score`는 소수 넷째 자리로 반올림된 값이라 임계값과 비교하면 어긋날 수 있어 쓰지 않는다.
- 작업은 선별된 예약에만 만들어지므로 `risk`가 있으면 `score ≥ threshold`다. 선별되지 않은 평가는 사건과 연결되지 않아 여기에 나타나지 않는다.
- 고객 신고로 생긴 사건은 ML 평가 없이 접수되므로 `null`이 정상이다.
- **호텔×체크인 날짜의 위험 신호이며 객실 미제공 확정이나 고객별 실패 확률이 아니다.** 화면도 이 문구와 함께 표시하고, 점수로 사건 상태·긴급도·숙박일 사실을 바꾸지 않는다.

### `analysisRuns` — 자동 처리 이력

```json
"analysisRuns": [
  {"kind": "RECHECK", "status": "FAILED", "trigger": "CUSTOMER_REPORT", "failedAttempts": 3,
   "errorCode": "ATTEMPTS_EXHAUSTED", "manualRequired": true, "at": "2026-11-26T13:25:16.217104Z"},
  {"kind": "HOTEL_REPLY_ANALYSIS", "status": "AMBIGUOUS", "version": "hotel-reply-extractor-0.1", "evidenceId": "EV-3", "at": "…"},
  {"kind": "QUOTE_RANKING", "status": "VALID", "version": "alternative-ranker-0.1", "at": "2026-11-26T13:29:06.622346Z"}
]
```

| `kind` | 읽는 곳 | `status` 값 | 범위 |
| --- | --- | --- | --- |
| `RECHECK` | `jobs`(`kind='RECHECK'`) | `PENDING`, `RUNNING`, `WAITING`, `COMPLETED`, `FAILED` | `jobs.case_id`가 이 사건 |
| `HOTEL_REPLY_ANALYSIS` | `agent_analyses` | `EXTRACTED`, `AMBIGUOUS` | 기존 `evidence`와 같은 예약 범위 |
| `QUOTE_RANKING` | `quote_rankings` | `VALID`, `REJECTED` | `quote_rankings.case_id`가 이 사건 |

- `status` 값은 각 테이블의 CHECK 제약 그대로다. 종류마다 값 집합이 다르므로 화면은 `kind`별로 해석한다.
- `RECHECK`의 추가 필드:
  - `trigger`: `jobs.trigger_type`(`SCHEDULED`, `CUSTOMER_REPORT`)
  - `failedAttempts`: `result_payload.history`의 길이. 저장 코드가 최근 10건까지만 보존한다
  - `errorCode`: `result_payload.last.errorCode` 또는 `null`
  - `manualRequired`: `result_payload.manualRequired`
  - `at`: `finished_at`, 진행 중이면 `updated_at`
- `version`은 `agent_analyses.model_version`, `quote_rankings.ranking_version`이다. `at`은 두 테이블의 `analyzed_at`이다.
- `at` 오름차순으로 정렬한다. 없으면 빈 배열이다.
- 위험 평가는 위 `risk`에 있으므로 여기에 중복해 싣지 않는다.
- 오류는 저장된 오류 코드만 반환한다. 외부 응답 본문·토큰·예외 전문은 싣지 않는다.
- 현재 `version` 값들은 정규식 추출기와 고정 순위 규칙을 가리킨다. 화면에서 이를 LLM이나 "AI 판단 확정"으로 표시하지 않는다.
- 호텔 회신 분석은 시연 DB에 저장된 행이 없어 실제 응답 예시를 확인하지 못했다. 필드 대응은 테이블 정의와 `HotelReplyAnalysisStore`의 저장 코드로 확인했다.

### `closure`와 `completedTasks` — 종료 결과

```json
"closure": {"reason": "REPLACEMENT_CHECKED_IN", "closedAt": "2026-11-26T12:33:26.187878Z"},
"completedTasks": [
  {"taskId": "TASK-1", "type": "CONTACT_CUSTOMER", "required": true, "completedAt": "2026-11-26T12:30:53.23405Z",
   "completionNote": "고객에게 11/10 객실 제공 불가와 대체 숙소 안내 예정임을 채팅으로 안내함", "completionEvidenceId": null}
]
```

- `closure`는 `cases.close_reason`·`closed_at`이며 종료 전에는 `null`이다. 현재 종료 코드가 넣는 사유는 `REPLACEMENT_CHECKED_IN` 하나다.
- (2026-11-28 추가) 미완료 업무 `tasks[]`의 각 항목에 `required`(`case_tasks.is_required`)를 더한다. 종료를 막는 업무를 종료 시도 전에 화면이 표시하기 위해서다. 기존 필드는 그대로다.
- `completedTasks`는 이 사건의 `case_tasks` 중 `DONE` 행을 `completed_at` 순으로 반환한다. 기존 `tasks`(미완료만)와 `nextAction`은 그대로 둔다.
- `completionEvidenceId`는 `completion_evidence_id`를 `EV-` 형식으로 반환하고 없으면 `null`이다. DB 제약상 완료 업무는 메모와 근거 중 하나 이상을 가진다.
- `completionNote`는 상담사가 적은 처리 내용이다. 상담사 전용 응답이므로 그대로 반환한다.

## API-003 사건 목록에 추가하는 필드

각 항목에 `origin`(상세와 같은 객체)만 추가한다. 정렬·필터·페이지 계약과 `summary` 문구는 바꾸지 않는다. 초안에 있던 목록의 `riskScore`는 뺐다. API-003이 목록을 "검증된 문제 요약"으로 한정하고 있고, 목록에서 점수가 긴급도처럼 읽힐 수 있기 때문이다. 점수는 상세에서만 본다.

## 이번 제안에 넣지 않는 것

- **고객 신고 본문.** 위 대조 표 참고. 화면은 이미 채팅 첫 메시지로 본문을 보여 준다.
- **확인 근거의 문장 표시와 "출처 미인증" 표기 위치.** 기존 `originalContent`·`assertions`·`sourceAuthenticated`로 프런트에서 처리한다. `sourceAuthenticated`를 저장하는 규칙은 바꾸지 않는다.
- **다음 조치·안내 초안·종료 요약 문장.** 11-28 범위는 위 필드로 프런트가 규칙 기반 문장을 만든다. LLM 호출은 내부 HTTP 계약이 새로 필요하므로 별도 제안서로 올린다.
- **사건이 생기지 않은 자동 확인 완료 내역 화면**(시연 1의 결과)과 새 사건 알림 채널. 목록 자동 갱신은 기존 API-003을 주기적으로 다시 읽는 프런트 변경이라 계약 영향이 없다. API-003도 "새 사건 수신 시 첫 페이지를 다시 조회한다"고 적고 있다.
- **후속 기한 설정**(시연 3의 `dueAt`). 업무 규칙 변경이라 별도로 다룬다.

## 영향 범위

| 영역 | 변경 |
| --- | --- |
| Spring | `cases/CaseQueries.java`의 목록·상세 조회 SQL(읽기 전용 하위 조회 추가) |
| 프런트 | `app/cases/_types/case.ts`, `_components/case-details.tsx`, `case-list.tsx`, `case-closure-panel.tsx` — B팀 상담사 화면 영역 |
| 문서 | `docs/api.md`, `backend/docs/api/README.md` |
| DB·Flyway·ML·Agent·Mock·프록시 허용 경로 | 변경 없음 |

**대안:** 사건별 이력을 읽는 새 URI를 만드는 방법은 URI·프록시 허용 경로·격리 인증 필터가 함께 늘어나므로 택하지 않았다. 프런트가 문장을 계속 추정하는 방법은 위험 점수와 자동 처리 이력을 보여 줄 수 없다.

## 검증 계획

모듈 테스트를 늘리지 않고 전체 흐름으로 확인한다. 오류가 나면 그 지점부터 좁힌다.

- **선제 탐지 사건:** 위험 점검 → 재확인 → 사건 생성 뒤 상세에서 `origin.type=RISK_RECHECK`, `risk` 값, `analysisRuns`의 재확인 항목(`COMPLETED`)을 확인한다.
- **고객 신고 사건:** 신고 → 재확인 실패 뒤 `origin.type=CUSTOMER_REPORT`, `risk=null`, 재확인 항목의 실패 횟수와 `manualRequired=true`를 확인한다.
- **`origin`이 뒤바뀌지 않는지:** 위험 재확인으로 생긴 사건에 고객 신고를 추가로 접수한 뒤에도 `RISK_RECHECK`가 유지되는지 확인한다.
- **호텔 회신 분석:** 저장된 호텔 원문 한 건에 분석을 실행한 뒤 `HOTEL_REPLY_ANALYSIS` 항목이 나타나는지 확인한다.
- **종료 사건:** 시연 2를 끝까지 진행한 뒤 `closure`와 `completedTasks` 3건을 확인한다.
- 추가 필드를 읽지 않는 기존 화면(목록·상세·채팅·대체 숙소 탭)이 그대로 동작하는지 브라우저로 확인하고 전후 캡처를 남긴다.
- 실행한 명령과 미검증 범위는 PR에 요약한다.

## 승인받을 항목

1. API-004에 `origin`, `createdAt`, `risk`, `analysisRuns`, `closure`, `completedTasks`를 추가한다.
2. API-003 항목에 `origin`을 추가한다. 위험 점수는 목록에 넣지 않는다.
3. `origin`은 저장하지 않고 위 네 단계 규칙으로 조회 때 판정한다. 판정할 수 없으면 `UNKNOWN`이다.
4. `analysisRuns`의 `kind`는 `RECHECK`, `HOTEL_REPLY_ANALYSIS`, `QUOTE_RANKING` 세 가지이고 `status`는 각 테이블의 저장 값을 그대로 쓴다.
