# DB 변경 기준

## 유지할 구조

- 두 팀의 Spring 업무 테이블은 같은 DB에 둔다. Mock 공급사 테이블과 변경 이력은 별도로 관리한다.
- 예약당 활성 사건 최대 한 건, 예약마다 같은 숙박일 한 행, 현재 고객 제안에 포함된 견적만 선택할 수 있다는 제약을 보존한다.
- 내부 BIGINT 키와 API·공급사 식별자를 구분한다. 숙박일은 `DATE`, 발생 시각은 `TIMESTAMPTZ`, 업무 금액은 KRW 정수로 유지한다.
- 사건 생성·재사용은 같은 예약 행을 기준으로 직렬화한다. 외부 HTTP는 행 잠금 트랜잭션 밖에서 호출한다.

## 허용 범위와 승인

- 현재 물리 구조에 매핑하는 변경은 기존 스키마를 유지한다. 실제 테이블·컬럼·타입·제약을 바꾸려면 변경 이유와 양 팀 영향을 합의하고, [공통 Flyway 경로](../backend/src/main/resources/db/migration/)에 고유한 새 버전으로 작성해 팀장 PR 승인을 받는다.
- 적용된 [V1](../backend/src/main/resources/db/migration/V1__initial_business_schema.sql)은 내용과 파일명을 보존한다. [YB-001 시드](../backend/src/main/resources/db/local/R__seed_yb001.sql)는 `local` 프로필의 별도 경로에 둔다.

## 별도 Mock 예약 저장소

사용자 승인 [API-015/016·Mock 최소 계약](../backend/docs/api/API-015-016-PROPOSAL.md)에 따라 `compose.mock.yaml`을 명시적으로 실행하면 `127.0.0.1:25442/yeohaengbom_mock`, 사용자 `yb_mock`의 공급사 전용 PostgreSQL을 사용한다. 기본 업무 DB Compose·Spring Flyway와 데이터 소유권은 유지한다. 비밀번호는 Git 제외 `.env.mock` 또는 프로세스 환경으로 전달한다.

Mock 전용 [V1__mock_supplier_schema.sql](../mock-server/db/migration/V1__mock_supplier_schema.sql)은 ERD-06의 `mock_products`, `mock_night_inventory`, `mock_quotes`, `mock_booking_requests` 4개 테이블과 FK·CHECK·UNIQUE를 사용한다. Spring V1 경로에 넣거나 별도 bookings 테이블을 만들지 않는다. Mock Python은 설정과 실제 DB/사용자 신원을 DDL 전에 확인한다. 비어 있는 전용 DB에만 초기 DDL을 적용하고 이후에는 스키마 comment의 마이그레이션·catalog 해시를 확인한다. 구조 차이는 자동 수선·삭제·초기화 없이 중단한다.

상품 메타데이터와 발급 견적은 불변이다. 초기 시드는 빠진 상품·숙박일만 준비하며 재시작으로 재고·가격·요청 결과를 덮어쓰지 않는다. 공급사는 전 숙박일 재고 행과 요청 결과를 자기 DB 안에서 원자 반영한다. Spring은 HTTP 결과를 업무 DB 원장에 별도 반영하며 분산 트랜잭션을 만들지 않는다. 상태 저장 모드 전환 뒤에는 새 검색을 통해 Mock 발급 견적을 확보한다. [실행·경계](../backend/docs/api/REPLACEMENT-DEMO.md)

## 원본

- [통합 ERD·테이블 정의서](https://app.notion.com/p/417e04d22bed812e86a7f2841b8073c5)
- [공통 Flyway·PR 승인 규칙](https://app.notion.com/p/418e04d22bed816abcede010eed08fdd)
