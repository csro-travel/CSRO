# API 구현 인덱스

이 인덱스는 코드 위치와 구현 범위를 찾기 위한 저장소 안내다. API 설계와 업무 기준은 연결된 Notion 명세를 따른다. 새 API를 구현하면 행을 추가한다.

| API | 기능 | 경로 | 작업 범위·브랜치 | 현재 범위 | 구현 문서 |
| --- | --- | --- | --- | --- | --- |
| API-001 | 고객 예약 상세 조회 | `GET /api/v1/bookings/{bookingId}` | A팀 · `1116cy` 초기 구현 | DB 조회·인가 판단·DTO·오류 처리, 공통 Bearer 인증 연결 및 ID 형식 검증은 남음 | [API-001 구현·검토](API-001.md) |
| API-011 | 사건별 상담 채팅 메시지 조회 | `GET /api/v1/cases/{caseId}/messages` | 상담 백엔드 | 활성 고객의 소유 사건·활성 상담사 큐 조회, 이전 페이지와 제안 카드 이력 | [API-011 구현 메모](#api-011-구현-메모) |

API-001의 구현은 `src/main/java/com/csro/backend/booking/`, 테스트는 같은 패키지의 `src/test/java/`에 있다. 각 클래스 JavaDoc과 JUnit DisplayName에도 API 번호가 표시된다. API 목록 전체가 구현됐다는 인덱스가 아니다.

## API-011 구현 메모

`CaseController`와 `CaseQueries`에서 [Notion API-011 검토안](https://app.notion.com/p/ab66adbd23b3830daa1481395fa135b5)을 구현한다. 인증 주체는 기존 사건 조회의 Servlet `Principal` 또는 격리 `local-demo`에서 설정된다. 운영용 Bearer 검증은 공통 인증 계층 연결 후 사용할 수 있다.

활성 고객은 소유 예약의 사건을, 활성 상담사는 단일 큐의 사건을 읽는다. 접근 가능한 종료 사건도 조회한다. `limit` 기본값은 30, 범위는 1~50이다. `beforeMessageId`는 같은 사건의 공개 메시지 번호다. `(sent_at, id)`로 메시지 페이지를 먼저 선택하고, 선택된 페이지를 오래된 순으로 반환한다.

제안은 V1에 저장된 카드를 발송 순서대로 읽는다. V1에는 발송 당시 비교 기준액이 없어 `priceDifference`는 `null`이다. 제안당 카드 3장의 수량 보장은 API-013 발송 시점의 책임이다.

통합 테스트는 새 격리 PostgreSQL을 사용한다. `CHATTING_API_INTEGRATION_TEST=true`, `CHATTING_API_TEST_DB_URL=jdbc:postgresql://127.0.0.1:25433/yeohaengbom_chat_api_test`와 `CHATTING_API_TEST_DB_PASSWORD`를 테스트 프로세스에 설정한 뒤 `backend`에서 `.\gradlew.bat test --tests '*CaseMessagesIntegrationTest'`를 실행한다. 테스트는 Spring과 Flyway를 시작하기 전에 DB 이름과 사용자 `yb`를 확인한다.
