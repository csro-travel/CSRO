# 입실 확인·필수 업무 완료·사건 종료

가상 예약 확정 뒤 고객이 API-017로 입실을 확인하고, 상담사가 API-008로 원예약 후속 등 필수 업무를 완료한 다음, API-018로 사건을 종료한다. [사용자 승인 최소 계약](API-008-017-018-PROPOSAL.md)을 구현한 범위이며 Notion 팀 전체 설계 상태를 변경하지 않는다. 외부 HTTP·새 Flyway·새 jobs.kind는 없다.

## 실행 순서

[가상 예약 시연](REPLACEMENT-DEMO.md)의 전용 DB·ML·Mock(상태 저장)·Agent·Spring(`local-chat-demo`, 견적·예약 Worker 켬) 준비를 그대로 사용한다. 상담사 3100·고객 3101 프록시를 시작하기 **전에 두 프로세스 모두** `CSRO_CLOSURE_DEMO_PROXY=true`를 추가로 설정한다. 기존 `CSRO_*_DEMO_PROXY` 설정과 토큰 분리는 유지한다.

1. 상담사 `http://127.0.0.1:3100/cases` → C-001 → 사건 탭의 **후속 업무와 사건 종료**. 예약 전에 **사건 종료**를 누르면 확정 대체 예약 없음·보장되지 않은 숙박일·미완료 필수 업무가 차단 사유로 표시된다.
2. 고객 안내 업무(`CONTACT_CUSTOMER`)에 처리 내용을 적고 **업무 완료**를 누른다.
3. 대체 숙소 검색 → 3곳 제안 → 고객 3101에서 선택 → 상담사 **대체 예약 진행**으로 CONFIRMED까지 진행한다. 이 시점에 종료하면 입실 미확인과 `CHECKIN_CONFIRM`·`ORIGINAL_FOLLOWUP` 미완료로 차단된다.
4. 고객 `http://127.0.0.1:3101/customer/cases/{사건 번호}`의 대체 예약 처리에서 **입실 완료 확인**을 누른다. `CHECKIN_CONFIRM` 업무가 같은 트랜잭션에서 완료된다.
5. 상담사가 **원예약 후속 처리**에 수동 처리 결과를 적어 완료한 뒤 **사건 종료**를 누른다. 사건은 `CLOSED`/`REPLACEMENT_CHECKED_IN`이 되고 채팅 입력이 닫힌다.

## 프록시 경계

| 프록시 | 허용 경로 | 조건 |
| --- | --- | --- |
| 상담사 3100 | `PATCH /demo/cases/C-…/tasks/TASK-…`, `POST /demo/cases/C-…/closures` | 같은 출처·JSON, 종료는 Idempotency-Key 필수 |
| 고객 3101 | `POST /demo/replacement-bookings/BRQ-…/checkin-confirmations` | 같은 출처·JSON·Idempotency-Key 필수 |

Spring 격리 인증은 입실 경로만 고객 주체로 연결하고 업무·종료 경로는 상담사 토큰만 인정한다. 역할 검사는 서비스가 다시 수행한다.

## 검증 범위

2026-11-26 Windows에서 새 tmpfs 업무 DB·Mock DB와 실제 ML·Mock·Agent·Spring·두 프록시를 띄우고 앱 내 브라우저로 위 1~5단계를 진행했다. 종료 전 차단 사유 2회(예약 전·입실 전), 고객 입실, 업무 자동/수동 완료, 종료, 채팅 입력 비활성화를 확인했다. 이어 HTTP로 종료·입실의 같은 키 재생(201), 새 키 409(`CASE_ALREADY_CLOSED`·`CASE_CLOSED`), 상담사 입실 403, 같은 메모 업무 재생 200·다른 메모 409, 잘못된 본문 400, 고객 프록시의 업무 변경 403을 확인했다.

자동 검사: `backend`에서 `.\gradlew.bat ciUnitTest`(인증 경계 테스트 포함), `frontend/travel_bom`에서 빌드·`tsc --noEmit`·`node --test tests/demo-closure-proxy.cjs`. 기존 프록시 테스트 4개는 Windows에서 `ECONNRESET`으로 실패하며 `main`의 원본 프록시에서도 같다.

미검증: 부분 기간(1박) 대체 확정 사건의 `STAY_NOT_COVERED` 차단(대체 예약이 없을 때의 차단만 확인), 전용 PostgreSQL 통합 테스트, GitHub Actions 원격 실행. 호텔·공급사 출처 입실, 원예약 자동 취소·환불, 운영 인증은 범위 밖이다.
