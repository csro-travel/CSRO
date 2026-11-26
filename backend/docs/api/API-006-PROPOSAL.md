# API-006 — 호텔 수동 확인 근거 등록 사용자 승인 최소 계약

기준은 `main`의 `030a35b`다. Notion [API-006](https://app.notion.com/p/417e04d22bed81a59835c4c53465fb98)은 URI·상태 코드 후보만 있고 상세 계약이 "미확정"이다. [업무 흐름과 비즈니스 규칙](https://app.notion.com/p/414e04d22bed81d0b4fffacbfcddc64f)의 시연 3은 다음 순서다.

현장 신고 → 호텔 무응답·재시도 → 기한 초과 → '호텔 직접 확인' 작업 → 상담사 기록 → 근거가 부족하면 '확인 대기'

사용자가 2026-11-26 대화에서 아래 두 결정을 승인했다.
1. 수동 확인 결과를 기존 자동 재확인과 같은 규칙으로 숙박일 사실에 반영한다.
2. 확정되지 않은 숙박일이 남으면 사건을 자동으로 '확인 대기'로 전환한다.

새 Flyway·테이블·외부 HTTP는 없다. 기존 V1의 `evidence_records`(`MANUAL_HOTEL`)와 `evidence_nights`를 사용한다. [실행·검증](CLOSURE-DEMO.md#시연-3--호텔-직접-확인-api-006)

## 요청

`POST /api/v1/cases/{caseId}/evidence`

- 활성 COUNSELOR만 호출한다. 필수 `Idempotency-Key`(`[A-Za-z0-9._:-]{1,100}`)를 사용한다.
- 본문은 아래 두 필드만 허용한다.
  - `callNote`: 공백이 아닌 1~2000자
  - `nights`: 1개 이상의 원예약 숙박일. 날짜는 중복될 수 없다.

```json
{"callNote":"22:30 프런트 통화: 11/11 객실은 내일 회신","nights":[{"stayDate":"2026-11-11","reportedProvisionStatus":"UNKNOWN"}]}
```

## 처리 규칙

잠금 순서는 booking → case → booking_nights다. 근거 1건(`MANUAL_HOTEL`, 원문 `raw_text`, 기록 상담사)을 저장하고, 숙박일마다 주장 1행을 만든다. 각 주장은 자동 재확인과 같은 규칙을 따른다.

| 기존 숙박일 사실 | 이번 기록 | 결과 |
| --- | --- | --- |
| UNCONFIRMED | AVAILABLE·UNAVAILABLE | 주장 ACCEPTED, 숙박일 VERIFIED |
| VERIFIED | 같은 값 | 주장 PENDING_REVIEW, 변화 없음 |
| VERIFIED | 다른 값 | 주장 PENDING_REVIEW, 숙박일 CONFLICT |
| 모든 상태 | UNKNOWN | 주장 PENDING_REVIEW, 변화 없음 |

기록 후 원예약의 모든 숙박일이 VERIFIED인지 확인한다.

- **모두 VERIFIED:** 열린 필수 `VERIFY_HOTEL` 업무를 이 근거(`completion_evidence_id`)로 완료한다. 사건이 WAITING이면 IN_PROGRESS(처리 중)로 되돌린다.
- **하나라도 아님:** 사건을 WAITING(확인 대기)으로 바꾸고 `VERIFY_HOTEL`은 열어 둔다.

상태가 바뀌면 `cases.version`을 올린다. 같은 트랜잭션에서 `case_actions`에 `MANUAL_EVIDENCE_RECORDED`(키, 요청 해시, 이전 상태, 응답)를 남긴다.

## 응답

`201`:

```json
{"caseId":"C-002","evidenceId":"EV-4","caseStatus":"IN_PROGRESS","verifyHotelTaskCompleted":true,
 "nights":[{"stayDate":"2026-11-11","reportedProvisionStatus":"AVAILABLE","reviewStatus":"ACCEPTED","factStatus":"VERIFIED"}]}
```

## 멱등성과 오류

같은 키에 같은 본문(숙박일 순서 무관)을 보내면 최초 응답을 재생한다. 같은 키에 다른 본문을 보내면 409 IDEMPOTENCY_KEY_REUSED다.

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| 400 | INVALID_EVIDENCE_REQUEST | 본문 형식 오류 |
| 401 | UNAUTHENTICATED | 미인증 |
| 403 | FORBIDDEN_ROLE | 상담사가 아님 |
| 404 | CASE_NOT_FOUND | 사건 없음 |
| 409 | CASE_CLOSED | 이미 종료된 사건 |
| 422 | STAY_DATE_OUT_OF_RANGE | 원예약에 없는 숙박일 |

## 범위 밖

- 호텔 정정 회신 수신(API-022). 사용자 결정으로 제외한다.
- 상충(CONFLICT) 숙박일을 사람이 판정하는 화면
- 사건 상태 수동 변경(API-005)
- 출처 인증. 수동 기록은 `source_authenticated=false`로 저장한다.
