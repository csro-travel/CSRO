# API 계약 변경 기준

## API-002 고객 현장 신고

[Notion API-002](https://app.notion.com/p/8952f2597c5783a0a3f2813647f2dc9e)의 기존 접수 계약을 구현한다. `POST /api/v1/bookings/{bookingId}/incident-reports`는 고객 본인의 신고·첫 채팅·활성 사건·필수 업무·재확인 대기를 원자 저장하고 최초 201을 멱등 재생한다. 고객 신고를 호텔 근거로 취급하지 않으며, 기존 활성 사건의 상태와 담당자를 유지한다. Spring 업무 DB와 적용된 Flyway는 유지한다. [실행·검증·인증 범위](../backend/docs/api/API-002.md)

별도 고객 시연 토큰의 정확한 신고 POST 경로만 `local-chat-demo`와 3101 프록시에 추가한다. 일반 로그인이나 API-001의 운영 Bearer 연결을 완료한 것은 아니다. 재확인 Worker는 기본 꺼짐이며 검증된 전용 시연 프로필에서만 명시적으로 켠다.

API-024의 분석 HTTP 서버는 B팀, 원문 저장 후 해당 서버를 호출하고 응답을 검증·저장하는 Spring 연결은 A팀 책임이다. 이번 API-002는 기존 정형 026/027 경로를 재사용하며, 자연어 API-024 호출·분석 저장을 새로 구현하지 않는다.

## API-009/010 견적 연결

사용자 승인 [최소 계약](../backend/docs/api/API-009-010-PROPOSAL.md)에 따라 상담사의 검색 접수와 저장 견적 조회를 구현한다. Spring이 기존 QUOTE_SEARCH·견적·비교 테이블을 소유하며 API-028/025는 기존 HTTP 경계에서 호출한다. 공급사 견적 ID는 검색 스냅샷별로 구분하고 제안·선택·가상 예약까지 보존할 값이다. [실행·검증](../backend/docs/api/QUOTES-DEMO.md)

## API-013/014 제안·선택과 API-011 확장

사용자 승인 [최소 계약](../backend/docs/api/API-013-014-PROPOSAL.md)으로 `POST /api/v1/cases/{caseId}/offers`와 `PUT /api/v1/cases/{caseId}/selection`을 구현한다. Notion 팀 전체의 설계 승인 상태를 바꾼 것은 아니다. 상담사는 같은 사건·검색·기간 그룹의 유효한 저장 견적 중 서로 다른 숙소 3곳을 발송하고, 예약 소유 고객만 현재 ACTIVE 제안의 실제 항목을 선택한다. 요청의 가격·고객 ID·판단 시각으로 업무 사실을 덮어쓰지 않는다.

각 요청은 필수 Idempotency-Key와 정규화 본문 해시를 사용한다. 같은 키·같은 본문은 최초의 불변 결과, 다른 본문은 409다. 인증·현재 소유권 확인 후 같은 booking→case 잠금에서 재생 여부를 먼저 판단한다. 이후 제안 교체·기한 만료·사건 종료에도 옛 요청은 기록만 재생하며 현재 상태를 되돌리지 않는다. 새 제안과 항목·OFFER 메시지·감사 기록, 새 선택과 SYSTEM 알림·감사 기록은 각각 원자 저장한다.

API-011의 기존 메시지·페이지 계약에 `activeOfferId`, `currentSelection`, `effectiveAt`, `clockMode`를 추가해 현재 상태를 읽는다. 새 전체 기간 제안의 기존 `priceDifference` 필드는 발송 때 `case_actions.details`에 보존한 기준 원금으로 계산한다. 부분 기간·옛 스냅샷은 null이며 현재 booking 금액으로 재계산하지 않는다. 고객용 새 조회 URI를 만들거나 API-004를 고객에게 개방하지 않는다.

실제 예약·입실·종료는 이 API들이 실행하지 않는다. `local-chat-demo`에서만 별도 고객 토큰을 고정 고객 주체에 연결하고 3101→18082의 메시지 조회·선택 프록시를 명시적으로 켠다. 기존 전용 DB·loopback·독점 프로필 검증과 일반 인증 경계는 유지한다. [실행·검증과 남은 범위](../backend/docs/api/OFFERS-DEMO.md)

## API-015/016 대체 예약과 API-029/030

사용자 승인 [최소 계약](../backend/docs/api/API-015-016-PROPOSAL.md)에 따라 상담사만 `POST /api/v1/cases/{caseId}/replacement-bookings`로 `{"selectionId":"…"}`와 필수 Idempotency-Key를 접수한다. 현재 선택의 상품·기간·가격을 서버에서 동결하고 202 및 BRQ 접수번호를 반환한다. 같은 키·본문은 최초 접수 결과를 재생하며, 같은 선택에 다른 키를 쓰면 기존 BRQ와 409를 반환한다. API-011의 `latestReplacementBooking` 포인터로 새로고침 뒤에도 같은 요청을 찾는다.

`GET /api/v1/replacement-bookings/{replacementBookingId}`는 상담사 또는 원예약 소유 고객에게 업무 DB의 저장 상태만 반환한다. 공급사 호출·예약 실행·재시도 예산 초기화를 하지 않는다. 결과 불명과 조회 장애를 확정 실패로 해석하지 않는다.

선택적으로 켠 격리 Spring Worker가 잠금 밖에서 API-029/030을 호출한다. 응답 유실 뒤에는 동일 BRQ로 결과를 확인하고, 인증된 030 미존재일 때만 동결한 같은 본문·같은 BRQ의 제한된 POST 재시도를 허용한다. 한도 소진은 RESULT_UNKNOWN/manualRequired로 보존한다. 다른 키·변경 가격으로 대체 예약을 만들지 않는다. 확정 결과·SYSTEM 안내·필수 입실/원예약 후속 TODO와 감사 기록을 원자 저장하며 기존 업무를 자동 완료하지 않는다.

Mock은 별도 PostgreSQL에서 전 숙박일 재고 차감·예약 요청 결과를 원자 저장한다. 기존 DB 없는 026/027/028 모드는 유지하며 업무 DB·Spring V1·jobs.kind·ML 모델을 변경하지 않는다. 상태 저장 모드로 전환한 뒤 새 검색→제안→선택을 해야 하며 Spring의 과거 견적을 Mock에 복사하지 않는다. 기존 상담사·고객 시연 토큰에 정확한 015/016 경로만 추가하고 운영 인증은 그대로 후속이다. [실행·복구·검증 범위](../backend/docs/api/REPLACEMENT-DEMO.md)

## API-017/008/018 입실·필수 업무 완료·사건 종료

사용자 승인 [최소 계약](../backend/docs/api/API-008-017-018-PROPOSAL.md)에 따라 예약 소유 고객이 CONFIRMED 대체 예약의 입실을 `POST /api/v1/replacement-bookings/{id}/checkin-confirmations`(빈 본문, Idempotency-Key)로 1회 기록하고, 같은 트랜잭션에서 필수 `CHECKIN_CONFIRM`을 완료한다. 상담사는 `PATCH /api/v1/cases/{caseId}/tasks/{taskId}`(`{"status":"DONE","completionNote":"…"}`)로 원예약 후속 등 필수 업무를 완료한다. `POST /api/v1/cases/{caseId}/closures`(빈 본문, Idempotency-Key)는 확정 대체 예약·입실 기록·숙박일 보장·필수 업무 완료를 함께 검사하고, 미충족이면 `409 CASE_CLOSE_BLOCKED`와 `blockers`를 반환한다. API-016에는 `checkinConfirmation`이 추가된다. 원예약 자동 취소·환불과 Agent의 자동 종료는 하지 않는다. [실행·검증 범위](../backend/docs/api/CLOSURE-DEMO.md)

## 유지할 계약

- 각 API의 URI·메서드·요청·응답·오류 조건은 현행 Notion 상세 명세에서 확인한다. 성공은 리소스 JSON을 직접 반환한다. 오류는 `application/problem+json`과 안정적인 `code`, 추적용 `traceId`를 유지한다.
- 조회는 업무 상태를 바꾸지 않는다. 고객 조회는 인증된 주체와 예약 소유권을 확인한다. API-001은 미존재 예약과 타인 예약에 동일한 404를 반환하고, 내부 PK·위험 점수·호텔 원문·미검증 주장을 응답에서 제외한다.
- 부작용이 있는 요청은 상세 명세의 멱등성 규칙을 따른다. 같은 키의 같은 요청은 기존 결과를 확인하고, 본문이 다른 요청은 충돌로 처리한다.
- 고객·상담사 API는 `/api/v1`, Spring에서 ML·Agent로 보내는 요청은 `/internal/v1` 상세 계약을 따른다. Python은 분석 결과를 반환하고 업무 상태 변경은 Spring이 담당한다. Spring에서 가상 공급사로 보내는 요청은 `/mock-supplier/v1`, 가상 호텔로 보내는 요청은 `/mock-hotel/v1` 상세 계약을 따른다.

## 허용 범위와 승인

기존 URI·JSON 필드·상태 코드·권한·동작을 보존하는 내부 정리는 가능하다. 공개 계약이나 이미 합의한 내부 HTTP 계약을 바꾸려면 영향받는 호출자와 변경 이유·대안을 먼저 확인하고 승인받는다. API-001의 실제 Bearer 인증 연결과 예약 ID 형식 검증은 구현 전이다. 토큰 발급 방식과 최종 ID 형식은 명세에서 확정한 뒤 적용한다.

## 원본

- [REST API 목록·공통 규약 및 상세 명세 인덱스](https://app.notion.com/p/414e04d22bed81cb9c46fbe6dc645f63)
- [공통 상태코드·오류 응답](https://app.notion.com/p/417e04d22bed81589c21e3a3a43951b9)
- [API-001 고객 예약 상세 조회](https://app.notion.com/p/417e04d22bed816b92f8f8e9fbe7091e)

## API-023 구현

[Notion API-023](https://app.notion.com/p/417e04d22bed8136935fc2504f94525f)의 URI·응답·인증·오류·책임 경계에 따라 Python 내부 추론 서버를 구현한다. 원문의 가상 v0.1 특성 대신 기존 ML 모델의 명시적인 `risk-features-0.2` 여섯 특성만 받는다. v0.1은 422로 거부하며 특성 산식의 팀 전체 승인이나 Spring 연동 완료를 뜻하지 않는다. [구현 계약](../ml/docs/api/API-023.md) · [실행·인계](../ml/docs/api/API-023_HANDOFF.md)

Spring의 내부 진입점은 `RiskAssessmentService.assess(hotelCode, targetCheckInDate)`다. `RiskObservations`가 평가 시점까지의 완전한 호텔 관측 이력을 제공해야 하며, 운영 관측 공급자는 후속이며, 명시적으로 켠 로컬 자동 배치에서는 합성 파일용 `RiskFileObservations`를 사용한다. 운영 이력이 없으면 HTTP 요청 전에 중단한다. 정상 응답은 대상·시각·버전·점수를 확인한 뒤 여섯 입력 특성과 함께 기존 `risk_assessments`에 저장한다.

로컬 연동에서 Spring의 `ML_RISK_BASE_URL`은 ML 서버 주소를, `ML_RISK_SERVICE_TOKEN`은 Python의 `ML_SERVICE_TOKEN`과 같은 서버 간 인증 값을 사용한다. `ML_RISK_MODEL_VERSION` 기본값은 저장 모델 `process-synthetic-lightgbm-0.2-84d386281d22`다. `RiskAssessmentLiveTest`는 명시적으로 `CSRO_RISK_LIVE_TEST=true`를 설정하고 전용 PostgreSQL(`127.0.0.1:25439/csro_risk_fixture_test`, 사용자 `yb`, 비밀번호 `CSRO_RISK_TEST_DB_PASSWORD`)에 V1과 YB-001 시드를 준비한 경우 실행한다. 검증 범위는 평가 저장까지다.

동일 모델·대상·평가 시각·입력·저장 점수의 재시도는 같은 평가 행을 재사용한다. 내부 시연 진입점 `RiskRecheckService.assessAndReconfirm(hotel, target, policy)`은 정책을 명시해야 한다. 기준 시점의 예약 ID 집합과 현재 시뮬레이션 예약을 대조하고 ML 호출 뒤 잠금 안에서 다시 검사한다. 선택된 예약의 RECHECK Job과 평가를 같은 트랜잭션에서 저장하고 커밋 후 기존 재확인 서비스를 호출한다. 검증된 근거·사건과 Job 성공 상태는 같은 트랜잭션에서 반영한다. 실행 중단 후에는 `RiskRecheckJobs.runDue(limit)`으로 해당 프로토콜의 작업만 수동 또는 명시적 로컬 배치에서 재개한다. 세 임계값은 비교용이며 운영 관측 공급자와 상시 운영 스케줄러는 없다.

CI는 위험 평가, 정책 기반 사건 조회, Job 재시도·복구와 배치 평가·대상 선별 및 실제 ML/Mock 재확인을 각각 독립 DB에서 검증하도록 구성한다. Job 복구 검사는 `RISK_JOBS_TEST=true`와 전용 DB `127.0.0.1:25435/yeohaengbom_risk_stage2_test`를 사용한다. 배치 검사는 `RISK_BATCH_TEST=true`와 `127.0.0.1:25436/yeohaengbom_risk_batch_test`, 재확인 통합 검사는 `RISK_LINK_TEST=true`와 `127.0.0.1:25434/yeohaengbom_risk_link_test`를 사용한다.

## 공통 위험 평가와 로컬 자동화

`RiskAssessmentService`의 관측 준비·추론과 `RiskAssessmentStore`의 저장을 공통 경로로 사용한다. API-023 HTTP는 엄격한 `RiskMlClient` 한 구현을 거친다. `RiskRecheckService`는 선별된 예약의 평가·RECHECK 등록을 같은 트랜잭션에서 처리하고 HTTP는 잠금 밖에서 실행한다. PR #14(`2f26f6b`)의 성공 근거·사건·Job 완료 원자성과 실행권 검증을 유지한다.

자동 배치에는 명시적으로 켠 합성 파일용 `RiskFileObservations`를 사용한다. Spring이 계산한 여섯 값과 대상 예약을 기존 Python 계산과 대조하며 실제 운영 관측 공급자를 구현한 것은 아니다. 고객 API·ML/Mock HTTP 계약·Flyway·기준 모델은 변경하지 않는다. [실행과 한계](../backend/docs/api/ML-RISK-COMMON-PATH.md)
