# API-008/017/018 — 입실·필수 업무 완료·사건 종료 사용자 승인 최소 계약

기준은 `main`의 `6b3d386`이다. Notion [업무 흐름과 비즈니스 규칙](https://app.notion.com/p/414e04d22bed81d0b4fffacbfcddc64f), [API-008](https://app.notion.com/p/417e04d22bed8103a2c6ea273d4cb26b), [API-017](https://app.notion.com/p/417e04d22bed8148baaece0b1d5675ea), [API-018](https://app.notion.com/p/417e04d22bed81d68ed0eddce1e5f90b), [ERD 03 상담 사건·후속 업무](https://app.notion.com/p/417e04d22bed818f9f55ca338ca3cb12), [ERD 05 대체 예약·실제 입실](https://app.notion.com/p/417e04d22bed81e4bb9fd92986ce85eb)를 대조했다. 세 API는 Notion에서 URI·상태 코드 후보만 있고 DTO·오류 코드·멱등성은 "계약 세부 미확정"이다. 아래 최소 계약을 사용자가 2026-11-26 대화에서 승인했다. Notion의 팀 전체 승인·진행 상태를 바꾸지 않는다. [실행·검증 범위](CLOSURE-DEMO.md)

## 목표와 영향

가상 예약 확정 뒤 고객 입실 확인 → 원예약 후속 등 필수 업무 완료 → 사건 종료를 연결한다. Notion 규칙 "가상 예약번호 발급만으로 종료하지 않는다. 입실 확인, 필요한 숙박일 보장, 필수 후속 업무 완료를 함께 검사한다"를 서버에서 강제한다.

새 Flyway·테이블·`jobs.kind`를 추가하지 않는다. 기존 V1의 `checkin_confirmations`, `case_tasks`(완료 제약 ck_2/ck_3), `cases.close_reason/closed_at`(ck_4/ck_5), `case_actions`의 `CASE_CLOSED` 부분 유일 인덱스를 사용한다. 외부 HTTP 호출은 없다. 변경 영역은 Spring `closure` 패키지, API-016 응답 필드 1개 추가, 격리 시연 인증 경로, 상담사·고객 화면과 프록시다.

## 1. API-017 — 고객 대체 숙소 입실 확인

`POST /api/v1/replacement-bookings/{replacementBookingId}/checkin-confirmations`

- 활성 CUSTOMER이며 원예약 소유자만 호출한다. 상담사는 403 FORBIDDEN_ROLE, 타인·없는 요청은 같은 404 REPLACEMENT_BOOKING_NOT_FOUND다.
- 본문은 빈 JSON 객체 `{}`다. 입실 시각은 서버 실제 시각이며 클라이언트 입력을 받지 않는다. 필수 `Idempotency-Key`(`[A-Za-z0-9._:-]{1,100}`)를 사용한다.
- 잠금 순서는 booking → case다. 같은 사건·같은 키·같은 BRQ는 최초 결과를 재생하고, 같은 키·다른 BRQ는 409 IDEMPOTENCY_KEY_REUSED다.
- 대체 예약이 CONFIRMED가 아니면 409 REPLACEMENT_NOT_CONFIRMED, 사건이 CLOSED면 409 CASE_CLOSED, 같은 대체 예약의 입실 확인이 이미 있으면 409 CHECKIN_ALREADY_CONFIRMED다.
- 한 트랜잭션에서 `checkin_confirmations`에 `REPLACEMENT`/`CUSTOMER`/`CUSTOMER_CONFIRMED` 1행을 저장한다(confirmed_by, idempotency_key, request_hash 포함). 열린 필수 `CHECKIN_CONFIRM` 업무를 `DONE`으로 바꾸고 completion_note에 입실 확인 번호를 남긴다. SYSTEM 채팅 안내와 `case_actions` `CHECKIN_CONFIRMED` 기록도 함께 저장한다.

`201` 응답:

```json
{"checkinConfirmationId":"CHK-1","caseId":"C-001","replacementBookingId":"BRQ-…","status":"CUSTOMER_CONFIRMED","checkedInAt":"2026-11-27T01:00:00Z"}
```

API-016 응답에는 `checkinConfirmation`(`{"checkinConfirmationId","checkedInAt"}` 또는 null)을 추가한다. 기존 필드는 바꾸지 않는다. 호텔·공급사 출처 입실(`PROVIDER_VERIFIED`)과 원예약(`ORIGINAL`) 입실 등록 경로는 이번 범위에서 제외한다.

## 2. API-008 — 필수 후속 업무 완료(축소)

`PATCH /api/v1/cases/{caseId}/tasks/{taskId}`

- 활성 COUNSELOR만 호출한다. `taskId`는 API-004의 `TASK-{id}`다.
- 본문은 정확히 `{"status":"DONE","completionNote":"…"}`만 허용한다. completionNote는 공백이 아닌 1~1000자다. 담당자·기한·IN_PROGRESS 변경은 이번 범위에서 제외한다.
- 잠금 순서는 booking → case → task다. 사건에 속하지 않은 업무는 404 TASK_NOT_FOUND다.
- 이미 DONE인 업무에 같은 메모를 보내면 최초 결과를 200으로 재생하고, 다른 메모는 409 TASK_ALREADY_COMPLETED다. PATCH의 상태 기반 멱등성을 쓰므로 Idempotency-Key는 요구하지 않는다.
- 사건이 CLOSED면 409 CASE_CLOSED다. `CHECKIN_CONFIRM`은 고객 입실 확인으로만 완료하므로 409 CHECKIN_REQUIRED다.
- 원예약 후속(`ORIGINAL_FOLLOWUP`)은 상담사가 수동 처리 결과를 completionNote로 남겨 완료한다. 자동 원예약 취소·환불·정산은 하지 않는다.
- 완료는 `case_tasks`(status, completed_at, completion_note)와 `case_actions` `TASK_COMPLETED` 기록을 같은 트랜잭션에 저장한다.

`200` 응답:

```json
{"caseId":"C-001","taskId":"TASK-3","type":"ORIGINAL_FOLLOWUP","status":"DONE","completedAt":"…","completionNote":"…"}
```

## 3. API-018 — 사건 종료 기록

`POST /api/v1/cases/{caseId}/closures`

- 활성 COUNSELOR만 호출한다. 본문은 `{}`이고 필수 `Idempotency-Key`를 사용한다.
- 잠금 순서는 booking → case다. 이미 종료된 사건에 같은 키를 보내면 최초 종료를 201로 재생하고, 다른 키는 409 CASE_ALREADY_CLOSED다.
- 종료 조건은 모두 충족해야 한다. 미충족 항목을 모두 모아 `409 CASE_CLOSE_BLOCKED`와 `blockers` 배열로 반환하고, 사건 상태는 바꾸지 않는다.

| blocker code | 조건 |
| --- | --- |
| REPLACEMENT_NOT_CONFIRMED | 사건에 CONFIRMED 대체 예약이 없음 |
| CHECKIN_NOT_CONFIRMED | 그 대체 예약의 CUSTOMER_CONFIRMED 또는 PROVIDER_VERIFIED 입실 기록이 없음 |
| STAY_NOT_COVERED | 원예약 숙박일 중 VERIFIED+AVAILABLE도 아니고 확정 대체 예약 기간(체크인 포함·체크아웃 제외)에도 없는 날짜가 있음. `stayDates` 포함 |
| REQUIRED_TASKS_OPEN | `is_required` 업무 중 DONE이 아닌 것이 있음. `tasks`(`taskId`, `type`) 포함 |

- 충족하면 `cases`를 `CLOSED`, `close_reason='REPLACEMENT_CHECKED_IN'`, closed_at, version+1로 갱신한다. `case_actions` `CASE_CLOSED` 1행(키·요청 해시·이전 상태·대체 예약·입실 번호)과 SYSTEM 채팅 안내를 같은 트랜잭션에 저장한다. 사건당 종료 기록은 부분 유일 인덱스로 1회만 가능하며, 닫힌 사건은 다시 열지 않는다.
- 부분 기간(1박) 대체만 확정된 사건은 나머지 미확인 숙박일 때문에 STAY_NOT_COVERED로 차단된다.

`201` 응답:

```json
{"caseId":"C-001","closureId":"CLS-42","status":"CLOSED","closeReason":"REPLACEMENT_CHECKED_IN","closedAt":"…"}
```

## 공통

오류는 기존 `application/problem+json`과 `code`, `traceId` 형식을 따른다. 공통 오류는 400 INVALID_CASE_ID/INVALID_REPLACEMENT_ID/INVALID_TASK_ID/INVALID_CLOSURE_REQUEST, 401 UNAUTHENTICATED, 403 FORBIDDEN_ROLE, 404 CASE_NOT_FOUND, 415 UNSUPPORTED_MEDIA_TYPE, 500 INTERNAL_ERROR다.

API-005(사건 상태 PATCH), API-007(업무 생성), 상담사·공급사 출처 입실 등록, Agent의 자동 종료는 이번 범위에 포함하지 않는다.
