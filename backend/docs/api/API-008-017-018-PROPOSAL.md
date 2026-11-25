# 입실·후속 업무·사건 종료 — 사용자 승인 최소 구현 계약

**상태: 사용자가 이 대화에서 구현 승인. 구현·검증 결과는 별도 확인한다.** 기준은 `1124cy-core-flow`의 `f5add12`이다. 과거 승인 출처는 유지하며, 현재 적용 기준은 API-002·API-024가 포함된 `1125cy-api024`의 `db3b15f`다. 현재 코드에 맞춘 재확인 종료 보호·수동 확인 업무 연결을 함께 검증한다. 이 문서는 Notion의 팀 전체 승인·진행 상태를 변경하지 않는다. [실행·범위](RESOLUTION-DEMO.md)

[Notion E2E](https://app.notion.com/p/cfb2f2597c57821bb16e014bdc22c147), [API-008](https://app.notion.com/p/8662f2597c57825ab2d68101f3d81b29), [API-017](https://app.notion.com/p/5ce2f2597c578258a6df012f970686a8), [API-018](https://app.notion.com/p/83b2f2597c5783219b0b01faea190ed9), [사건·업무 ERD](https://app.notion.com/p/85f2f2597c5782329f578145d22975b9), [입실 ERD](https://app.notion.com/p/fd42f2597c578371b09301096d923d57)와 실제 Spring V1을 대조했다. 세 API 원본의 상세 요청·응답 공백을 아래의 사용자 승인 계약으로 보충한다.

## 목적·영향·대안

가상 예약 확정 뒤 고객이 직접 입실을 확인하고, 상담사가 원예약 후속 조치와 남은 필수 업무를 기록한 다음 서버 검증을 통과한 사건만 종료한다. 전체 기간 대체 예약의 시연 흐름을 끝까지 연결한다.

승인한 변경은 Spring의 입실·업무·종료 서비스와 컨트롤러, 기존 API-004/011 조회의 추가 필드, 기존 격리 인증·프록시의 정확한 경로 허용, 상담사·고객 화면 및 관련 검증·문서다. 양 팀의 Spring/UI 호출 계약에 미치는 영향을 이 범위로 제한한다. Spring V1, 업무 DB 소유권, Mock HTTP·저장소, ML, 일반 인증, 기존 서비스 실행 방향은 보존한다.

기존 구조를 유지하는 대안은 입실·후속 업무·종료를 문서나 SQL로만 기록하고 화면 연결을 미루는 것이다. 재현 가능한 사용자 흐름과 종료 보호 장치를 제공하기 위해 위 API의 최소 구현을 제안한다. 작업 생성·담당자 선점·기한 편집, 자동 원예약 취소·결제·환불, 공급사 입실 웹훅, 원호텔 입실 등록 API는 이번 범위에 추가하지 않는다.

## 공통 계약

- 기존 활성 사용자 인증과 예약 소유권을 검사한다. 고객은 본인의 API-017만 쓰며 API-008/018은 활성 COUNSELOR만 쓴다. 상담사가 고객인 것처럼 입실 확인을 대신 보내지 않는다.
- 세 쓰기는 필수 `Idempotency-Key`를 기존 `[A-Za-z0-9._:-]{1,100}`로 받는다. 인증·현재 소유권 확인 → booking → case 잠금 → 기존 키 재생 → 새 요청 검증 순서다. 같은 작업·키·정규화 본문은 최초 불변 응답을 재생하고 다른 본문은 409다. 다른 입실 대상이나 task로 키를 바꿔 쓰는 충돌도 거부한다.
- `case_actions.details`의 `case-resolution-v1` 프로토콜에 작업 종류·요청 해시·주체·불변 응답·변경 근거를 보존한다. 고객 입실은 기존 `checkin_confirmations`와 해당 감사 행을 함께 저장한다. 새 테이블·컬럼·마이그레이션은 없다.
- 실제 감사 접수 시각과 업무 판단 시각을 구분한다. 서버의 기존 `QuoteSearchRuntime.effectiveAt()`와 `clockMode`를 사용하고 클라이언트의 날짜·고객·호텔·가격·사건 상태를 신뢰하지 않는다. 이 단계에는 외부 HTTP가 없다.
- 새로운 상태 변경은 `cases.version`을 증가시킨다. 재생은 상태·버전을 바꾸지 않는다. API-008/018은 조회한 `expectedVersion`이 다르면 409 `CASE_VERSION_CONFLICT`를 반환한다.
- 성공은 리소스 JSON, 오류는 기존 `application/problem+json`과 `code`, `traceId`다. 잘못된 JSON·본문·헤더 400, 미인증 401, 역할 거부 403, 미존재/타인 고객 대상 동일 404, 업무 조건·키 충돌 409, 잘못된 Content-Type 415를 구분한다. 모든 응답은 private/no-store다.

## API-017 — 고객 직접 입실 확인

`POST /api/v1/replacement-bookings/{replacementBookingId}/checkin-confirmations`

요청은 다음 한 가지 확인 의도만 받는다. 누락·false·추가 필드는 거부한다.

```json
{"checkedIn":true}
```

본인 사건의 CONFIRMED 대체 예약에만 기록한다. PROCESSING·RESULT_UNKNOWN·FAILED는 입실 확인으로 바꾸지 않는다. 같은 사건에 결과 불명 요청이 남아 있으면 새로운 확인을 거부한다. 신규 확인은 서버 판단 시각의 한국 날짜가 확정 숙박 기간 `[checkInDate, checkOutDate)` 안에 있어야 한다. 시연 시에는 예약을 마친 뒤 기존 가상 시각을 명시적으로 `2026-11-10T15:00:00+09:00` 등 숙박일 안으로 전진시켜 Spring을 다시 실행한다. 기본 `2026-11-09`에 다음 날 입실했다고 만들지 않는다. 이 날짜 검사는 호텔별 체크인 가능 시각을 검증한다는 뜻이 아니다.

`target_type=REPLACEMENT`, `source_type=CUSTOMER`, `confirmation_status=CUSTOMER_CONFIRMED`, 인증된 고객, 서버 판단 `checkin_at`을 저장한다. 201 예시:

```json
{
  "checkinConfirmationId":"CHECKIN-example",
  "caseId":"C-001",
  "replacementBookingId":"BRQ-example",
  "status":"CUSTOMER_CONFIRMED",
  "checkinAt":"2026-11-10T15:00:00+09:00",
  "isSynthetic":true,
  "clockMode":"SYNTHETIC_DEMO"
}
```

공개 확인 번호는 감사 응답에 저장한 불변 식별자다. 같은 키는 기간이 지난 뒤나 종료 뒤에도 최초 결과를 재생한다. 다른 키로 이미 확인한 같은 예약을 다시 확인하면 409 `CHECKIN_ALREADY_CONFIRMED`와 기존 공개 번호를 반환한다.

같은 트랜잭션에서 입실 SYSTEM 안내를 추가하고 이 확인에 해당하는 미완료 `CHECKIN_CONFIRM` 업무만 실제 확인 근거를 명시하여 완료한다. 다른 필수 업무·원예약 숙박일·대체 예약 상태·사건 종료는 바꾸지 않는다. 시연 자료임을 화면에 명시하며 실제 호텔이나 공급사가 입실을 검증했다고 표시하지 않는다.

## API-008 — 상담사 후속 업무 완료의 최소 부분 구현

`PATCH /api/v1/cases/{caseId}/tasks/{taskId}`

```json
{"status":"DONE","completionNote":"고객에게 대체 숙소 예약번호와 숙박 기간을 안내함","expectedVersion":3}
```

이번 구현은 기존 업무의 완료만 지원한다. `status`는 DONE, `completionNote`는 trim 후 1~1000자, `expectedVersion`은 0 이상 정수다. 담당자·기한·isRequired·완료 시각·근거 ID 변경, 새 업무 생성, 일괄 완료 및 완료 취소는 받지 않는다. 해당 사건의 미완료 task에만 적용한다. 이미 완료한 task를 다른 키로 덮어쓰지 않는다.

`CHECKIN_CONFIRM`을 수동 메모만으로 완료하는 우회는 거부한다. 다른 업무는 완료 내용·상담사·시각과 기존 완료 메모를 보존하고 감사 행을 원자 저장한다. 200은 `caseId`, `taskId`, `status`, `completionNote`, `completedAt`, `version`의 불변 결과다. `is_required`는 바꾸지 않는다.

`ORIGINAL_FOLLOWUP`에 한해서는 본문에 `followupOutcome`을 추가로 요구한다. `COMPLETED`(가상/수동 조치 완료를 확인) 또는 `NOT_REQUIRED`(조치 불필요 사유를 확인)만 완료로 기록하며 해당 값과 이유를 감사에 보존한다. `REQUESTED`·`HANDED_OFF`·`PENDING` 등 요청·인계·대기는 완료로 받지 않는다. 다른 task에는 이 필드를 받지 않는다. 원예약 후속 완료 예시는 다음과 같다.

```json
{"status":"DONE","followupOutcome":"COMPLETED","completionNote":"시연용 원예약 후속 처리 완료를 수동 확인함. 실제 취소·정산은 실행하지 않음","expectedVersion":3}
```

이는 상담사가 원예약 관련 조치를 확인하고 기록했다는 뜻이다. 공급사 취소 API나 결제/환불 완료를 만들어내지 않으며 `supplier_status`와 원예약 숙박일 사실도 바꾸지 않는다. 시연 화면에는 실제 취소·정산을 실행하지 않았음을 표시한다. 메모만으로 미확인 숙박일을 AVAILABLE로 바꾸지 않는다.

## API-018 — 근거와 필수 업무를 검사한 사건 종료

`POST /api/v1/cases/{caseId}/closures`

```json
{"reason":"고객 입실과 전 숙박일 보장 및 원예약 후속 업무 확인 완료","expectedVersion":4}
```

`reason`은 trim 후 1~80자(기존 `cases.close_reason` 크기), `expectedVersion`은 0 이상 정수다. 서버가 다음을 잠금 안에서 다시 계산한다.

1. 사건이 열려 있고 현재 버전이 맞으며 결과 불명·처리 중인 대체 예약이 없다. 해당 원예약 전체의 RECHECK Job에 `PENDING/RUNNING/WAITING`이 하나라도 있으면 `RECHECK_PENDING` 부족 조건으로 종료를 차단한다. Job의 소유 프로토콜·case_id 연결 여부와 무관하게 예약 단위로 확인하며, 종료를 위해 다른 Worker의 작업을 취소·완료·실패로 덮어쓰지 않는다.
2. 해당 사건·원예약·CONFIRMED 대체 예약에 일치하는 유효한 직접 입실 확인이 있다. 예약 성공이나 상담사 메모만으로 입실을 대신하지 않는다.
3. 원예약의 체크인부터 체크아웃 전날까지 `booking_nights`가 빠짐없이 존재한다. 모든 필요한 날짜는 입실 확인이 연결된 확정 대체 견적의 기간 또는 원예약의 현재 `VERIFIED + AVAILABLE` 근거로 보장된다. 기간·성인·객실도 원예약 수요와 맞아야 한다. 부분 대체 밖의 UNKNOWN·CONFLICT·미보장 날짜가 있으면 종료하지 않는다. 전체 기간을 유효한 대체 예약이 보장하면 원호텔의 UNKNOWN을 거짓 AVAILABLE로 고치지 않고 종료 가능하다.
4. 필수 `CHECKIN_CONFIRM`과 `ORIGINAL_FOLLOWUP`이 존재하고 DONE이며, 나머지 `is_required=true` 업무도 전부 DONE이다. API-002 접수나 API-024 분석이 만든 `VERIFY_HOTEL`도 포함되며 상담사가 실제 확인 내용을 기록해 API-008로 완료한다. 완료 근거·메모가 비어 있거나 관련 예약이 다르면 거부한다. 누락된 필수 업무를 '할 일 없음'으로 간주하지 않는다.

현재 확정 대체 예약이 있으면 다음 제안·선택·예약을 막는 기존 보호 장치를 유지한다. 여러 부분 예약을 차례로 추가하는 기능을 구현했다고 표시하지 않는다. 이번 단계에서 부분 대체 사건은 해당 단일 대체와 나머지 원숙소의 검증된 보장으로 전체 기간을 충족할 때만 종료 가능하다.

실패는 409 `CASE_NOT_RESOLVED`와 구조화된 부족 조건을 반환한다. 실패 시 상태·업무·원문·감사 결과를 부분 저장하지 않는다.

성공은 같은 트랜잭션에서 `cases.status=CLOSED`, `closed_at`, `close_reason`, 버전을 반영하고 종료 근거 스냅샷의 `CASE_CLOSED` 감사 행과 SYSTEM 안내를 한 번 저장한다. 기존 CASE_CLOSED 부분 UNIQUE를 보존한다. 201은 `closureId`, `caseId`, `status`, `reason`, `closedAt`, `version`, `isSynthetic`, `clockMode`의 불변 결과다. 다른 키의 재종료는 409이며 같은 키의 응답 유실 재시도는 최초 201을 돌려준다.

## 기존 신고·분석과의 연결

- API-002의 고객 신고 POST와 별도 고객 토큰 허용을 유지한다. 새 신고는 원예약의 기존 활성 사건에 연결하며 신고만으로 숙박일 사실을 바꾸지 않는다. 신고가 재확인 Job을 추가하면 위 `RECHECK_PENDING` 보호를 적용한다.
- API-024는 저장된 호텔 자연어 원문의 주장 분석과 필수 수동 확인 업무만 연결한다. 분석 성공·모호함·실패를 객실 제공 사실이나 완료로 치환하지 않는다. 생성된 `VERIFY_HOTEL`을 이 단계의 완료 메모로 처리해도 원예약 숙박일·출처 인증은 바꾸지 않는다.
- API-008은 계속 기존 task의 `DONE` 완료만 구현한다. 업무 생성·선점·담당자/기한 편집·완료 취소를 추가한 것이 아니다.

## 읽기·화면·격리 실행

- API-004의 기존 필드는 보존하고 상담사용 `resolution`을 추가한다. 입실 요약, 전체 기간 보장 여부·미보장 날짜, 완료를 포함한 필수 업무·완료 내용, 종료 가능 조건·부족 조건, 종료 결과를 읽는다. 기존 `tasks`·`nextAction`은 미완료 업무의 의미를 유지한다.
- API-011에는 고객에게 안전한 `resolution` 요약(사건 상태, 본인 입실 확인, 종료 결과)을 추가한다. 상담사 내부 메모·원문·내부 키는 고객에게 보내지 않는다. 기존 메시지·선택·예약 포인터는 유지한다.
- 고객 화면은 확정 예약의 입실 확인 버튼과 재시도 상태를 제공한다. 상담사는 필수 업무별 완료 메모와 종료 전 부족 조건을 확인한다. 종료는 별도 명시적 버튼으로 실행한다. 서버 실패를 성공으로 추정하지 않고 같은 의도·키를 새로고침 후에도 복구한다.
- 기존 `local-chat-demo`의 고정 주체·전용 DB·loopback·독점 프로필 검증을 보존한다. 3101 고객 프록시는 API-017만 추가 허용하며 3100 상담사 프록시는 API-008/018만 추가 허용한다. 각각 명시적 기능 옵션·정확한 경로·동일 출처·서버 토큰·멱등키를 검증한다. 일반 인증·운영 배포를 구현한 것으로 표현하지 않는다.

## 구현·검증 계획

1. 입실·업무·종료 서비스와 트랜잭션, 기존 스키마/제약 기반 멱등성·동시성·종료 검증.
2. 세 HTTP API와 API-004/011의 안전한 추가 필드, 격리 인증 경계.
3. 상담사·고객 화면과 프록시, 같은 키의 재시도·종료 차단 표시.
4. 관련 CI·실행 안내와 전체 핵심 흐름 시연 확인.

쓰기 검사는 새 전용 `127.0.0.1:25445/yeohaengbom_resolution_test`, 사용자 `yb_resolution_test`에서만 수행하며 설정·실제 DB/사용자를 Spring/Flyway 전에 검사한다. 기본 업무 DB·시연 DB를 테스트로 쓰거나 초기화하지 않는다. 성공 흐름 외에 소유권, 미래 날짜 확인 차단, 입실 없는 종료, 부분 대체의 남은 UNKNOWN, 필수 업무 누락·미완료, 같은 키 재생·다른 본문 충돌, 동시 확인·종료, 오래된 버전, 실패 원자성, 고객의 상담사 명령 거부, API-024 필수 `VERIFY_HOTEL` 미완료와 예약 전체 RECHECK 대기 상태의 종료 차단을 검증한다.

브라우저→Spring→DB 연결로 고객 입실 확인·상담사 후속 메모·종료와 새로고침 복구를 확인한다. 원호텔 사실·예약 상태·Mock 재고가 이 단계 때문에 바뀌지 않는지 대조한다. 영향 밖의 ML 재학습이나 이미 통과한 전체 검사를 반복하지 않으며 Linux·Windows·원격 CI의 실제 실행 여부를 구분한다.
