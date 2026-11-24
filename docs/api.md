# API 계약 변경 기준

## API-009/010 견적 연결

사용자 승인 [최소 계약](../backend/docs/api/API-009-010-PROPOSAL.md)에 따라 상담사의 검색 접수와 저장 견적 조회를 구현한다. Spring이 기존 QUOTE_SEARCH·견적·비교 테이블을 소유하며 API-028/025는 기존 HTTP 경계에서 호출한다. 공급사 견적 ID는 검색 스냅샷별로 구분하고 제안·선택·가상 예약까지 보존할 값이다. 현재 연결은 사건 화면의 후보 표시까지이며 고객 전송·선택·예약은 후속이다. [실행·검증](../backend/docs/api/QUOTES-DEMO.md)

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
