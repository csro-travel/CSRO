# API 계약 변경 기준

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

Spring의 내부 진입점은 `RiskAssessmentService.assess(hotelCode, targetCheckInDate)`다. `RiskObservations`가 평가 시점까지의 완전한 호텔 관측 이력을 제공해야 하며, 현재 제공자는 테스트 Fixture에만 있다. 운영 이력이 없으면 HTTP 요청 전에 중단한다. 정상 응답은 대상·시각·버전·점수를 확인한 뒤 여섯 입력 특성과 함께 기존 `risk_assessments`에 저장한다.

로컬 연동에서 Spring의 `ML_RISK_BASE_URL`은 ML 서버 주소를, `ML_RISK_SERVICE_TOKEN`은 Python의 `ML_SERVICE_TOKEN`과 같은 서버 간 인증 값을 사용한다. `ML_RISK_MODEL_VERSION` 기본값은 저장 모델 `process-synthetic-lightgbm-0.2-84d386281d22`다. `RiskAssessmentLiveTest`는 명시적으로 `CSRO_RISK_LIVE_TEST=true`를 설정하고 전용 PostgreSQL(`127.0.0.1:25439/csro_risk_fixture_test`, 사용자 `yb`, 비밀번호 `CSRO_RISK_TEST_DB_PASSWORD`)에 V1과 YB-001 시드를 준비한 경우 실행한다. 검증 범위는 평가 저장까지다.

동일 모델·대상·평가 시각·입력·저장 점수의 재시도는 같은 평가 행을 재사용한다. 내부 시연 진입점 `RiskRecheckService.assessAndReconfirm(hotel, target, policy)`은 정책을 명시해야 한다. 기준 시점의 예약 ID 집합과 현재 시뮬레이션 예약을 대조하고 ML 호출 뒤 잠금 안에서 다시 검사한다. 선택된 예약의 RECHECK Job과 평가를 같은 트랜잭션에서 저장하고 커밋 후 기존 재확인 서비스를 호출한다. 실행 중단 후에는 `RiskRecheckJobs.runDue(limit)`으로 해당 프로토콜의 작업만 수동 재개한다. 세 임계값은 비교용이며 운영 관측 공급자와 자동 스케줄러는 없다.

CI는 위험 평가, 정책 기반 사건 조회, Job 재시도·복구를 독립 DB에서 검증한다. Job 복구 검사는 `RISK_JOBS_TEST=true`와 전용 DB `127.0.0.1:25435/yeohaengbom_risk_stage2_test`를 사용한다.
