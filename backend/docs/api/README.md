# API 구현 인덱스

이 인덱스는 코드 위치와 구현 범위를 찾기 위한 저장소 안내다. API 설계와 업무 기준은 연결된 Notion 명세를 따른다. 새 API를 구현하면 행을 추가한다.

| API | 기능 | 경로 | 작업 범위·브랜치 | 현재 범위 | 구현 문서 |
| --- | --- | --- | --- | --- | --- |
| API-001 | 고객 예약 상세 조회 | `GET /api/v1/bookings/{bookingId}` | A팀 · `1116cy` 초기 구현 | DB 조회·인가 판단·DTO·오류 처리, 공통 Bearer 인증 연결 및 ID 형식 검증은 남음 | [API-001 구현·검토](API-001.md) |
| API-023 Spring 호출·후속 처리 | 호텔 위험 평가 저장·재확인 선별 | Spring → `POST /internal/v1/risk-assessments` | A팀 · `1122cy-risk-link` | 수동 합성 입력·F1 기본 정책, 평가 저장·기존 재확인 연결. 집계·스케줄러·작업 복구는 미구현 | [1단계 구현·실행](ML-RISK-RECHECK-STAGE1.md) |

API-001의 구현은 `src/main/java/com/csro/backend/booking/`, 테스트는 같은 패키지의 `src/test/java/`에 있다. 각 클래스 JavaDoc과 JUnit DisplayName에도 API 번호가 표시된다. API 목록 전체가 구현됐다는 인덱스가 아니다.
