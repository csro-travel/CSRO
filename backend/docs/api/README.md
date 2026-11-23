# API 구현 인덱스

이 인덱스는 코드 위치와 구현 범위를 찾기 위한 저장소 안내다. API 설계와 업무 기준은 연결된 Notion 명세를 따른다. 새 API를 구현하면 행을 추가한다.

| API | 기능 | 경로 | 작업 범위·브랜치 | 현재 범위 | 구현 문서 |
| --- | --- | --- | --- | --- | --- |
| API-001 | 고객 예약 상세 조회 | `GET /api/v1/bookings/{bookingId}` | A팀 · `1116cy` 초기 구현 | DB 조회·인가 판단·DTO·오류 처리, 공통 Bearer 인증 연결 및 ID 형식 검증은 남음 | [API-001 구현·검토](API-001.md) |
| API-023 Spring 호출·후속 처리 | 호텔 위험 평가 저장·재확인 선별 | Spring → `POST /internal/v1/risk-assessments` | A팀 · `1122cy-risk-link` | 합성 관측 이벤트의 오프라인 특징값 생성, 평가·선별 작업 원자 저장, 동일 요청 ID로 재확인 재개. 실시간 수집·자동 스케줄러·ML 추론 실패 영속 저장은 미구현 | [2단계 실행·경계](ML-RISK-RECHECK-STAGE2.md) · [1단계 기록](ML-RISK-RECHECK-STAGE1.md) |
| 내부 자동 점검 배치 | 대상 호텔 자동 조회·평가·작업 재개 | 로컬 CLI → Spring 비웹 배치 → 기존 API-023/026/027 | A팀 · `1122cy-risk-link` | 고정 시연 날짜·합성 관측 catalog, 반복 평가·작업 재사용, 명시적 간격 반복. 운영 스케줄러·새 HTTP API 없음 | [자동화 실행·경계](ML-RISK-AUTOMATION.md) |

API-001의 구현은 `src/main/java/com/csro/backend/booking/`, 테스트는 같은 패키지의 `src/test/java/`에 있다. 각 클래스 JavaDoc과 JUnit DisplayName에도 API 번호가 표시된다. API 목록 전체가 구현됐다는 인덱스가 아니다.
