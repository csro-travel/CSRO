# 입실 확인·필수 업무 완료·사건 종료

가상 예약 확정 뒤 고객이 API-017로 입실을 확인하고, 상담사가 API-008로 원예약 후속 등 필수 업무를 완료한 다음, API-018로 사건을 종료한다. [사용자 승인 최소 계약](API-008-017-018-PROPOSAL.md)을 구현한 범위이며 Notion 팀 전체 설계 상태를 변경하지 않는다. 외부 HTTP·새 Flyway·새 jobs.kind는 없다.

## 실행 순서

[가상 예약 시연](REPLACEMENT-DEMO.md)의 전용 DB·ML·Mock(상태 저장)·Agent·Spring(`local-chat-demo`, 견적·예약 Worker 켬) 준비를 그대로 사용한다. 상담사 3100·고객 3101 프록시를 시작하기 **전에 두 프로세스 모두** `CSRO_CLOSURE_DEMO_PROXY=true`를 추가로 설정한다. 기존 `CSRO_*_DEMO_PROXY` 설정과 토큰 분리는 유지한다.

1. 상담사 `http://127.0.0.1:3100/cases` → C-001 → 사건 탭의 **후속 업무와 사건 종료**. 예약 전에 **사건 종료**를 누르면 확정 대체 예약 없음·보장되지 않은 숙박일·미완료 필수 업무가 차단 사유로 표시된다.
2. 고객 안내 업무(`CONTACT_CUSTOMER`)에 처리 내용을 적고 **업무 완료**를 누른다.
3. 대체 숙소 검색 → 3곳 제안 → 고객 3101에서 선택 → 상담사 **대체 예약 진행**으로 CONFIRMED까지 진행한다. 이 시점에 종료하면 입실 미확인과 `CHECKIN_CONFIRM`·`ORIGINAL_FOLLOWUP` 미완료로 차단된다.
4. 고객 `http://127.0.0.1:3101/customer/cases/{사건 번호}`의 대체 예약 처리에서 **입실 완료 확인**을 누른다. `CHECKIN_CONFIRM` 업무가 같은 트랜잭션에서 완료된다.
5. 상담사가 **원예약 후속 처리**에 수동 처리 결과를 적어 완료한 뒤 **사건 종료**를 누른다. 사건은 `CLOSED`/`REPLACEMENT_CHECKED_IN`이 되고 채팅 입력이 닫힌다.

## 시연 3 — 호텔 직접 확인 (API-006)

[사용자 승인 최소 계약](API-006-PROPOSAL.md)에 따라 다음 순서로 진행한다.

1. **재확인 Worker 켜기:** Spring 실행 옵션에 `--incident.recheck.worker.enabled=true`를 추가한다.
2. **고객 신고 경로 열기:** 고객 3101 프록시에 `CSRO_INCIDENT_DEMO_PROXY=true`를 추가로 설정한다.
3. **호텔 무응답 재현:** Mock 서버(8001)를 잠시 끈 상태에서 고객이 `http://127.0.0.1:3101/customer/bookings/YB-001`에서 체크인 문제를 신고한다. 재확인이 재시도 끝에 FAILED가 되면 필수 `VERIFY_HOTEL` 업무가 생긴다.
4. **근거 부족 기록:** 상담사 사건 탭의 **호텔 직접 확인 기록**에서 일부 숙박일을 "확인 안 됨"으로 기록하면 사건이 확인 대기가 된다.
5. **확인 완료 기록:** 나머지 숙박일을 제공 가능·불가로 기록해 전 숙박일이 VERIFIED가 되면 `VERIFY_HOTEL`이 이 근거로 완료되고 사건은 처리 중으로 돌아온다.

2026-11-26 Windows 브라우저에서 위 순서를 실제로 확인했다. 결과는 새 사건 C-002, 재확인 작업 FAILED(2회 시도), 확인 대기, 처리 중, `VERIFY_HOTEL` DONE(근거 연결)이었다. 같은 규칙은 `ManualEvidenceIntegrationTest`(근거 부족→확인 대기→전 숙박일 확정, 상충 시 CONFLICT, 권한·범위·종료 오류)가 예약 전용 DB에서 검증한다.

## 프록시 경계

| 프록시 | 허용 경로 | 조건 |
| --- | --- | --- |
| 상담사 3100 | `PATCH /demo/cases/C-…/tasks/TASK-…`, `POST /demo/cases/C-…/closures`, `POST /demo/cases/C-…/evidence` | 같은 출처·JSON, 종료·근거 등록은 Idempotency-Key 필수 |
| 고객 3101 | `POST /demo/replacement-bookings/BRQ-…/checkin-confirmations` | 같은 출처·JSON·Idempotency-Key 필수 |

Spring 격리 인증은 입실 경로만 고객 주체로 연결하고 업무·종료 경로는 상담사 토큰만 인정한다. 역할 검사는 서비스가 다시 수행한다.

## 검증 범위

2026-11-26 Windows에서 새 tmpfs 업무 DB·Mock DB와 실제 ML·Mock·Agent·Spring·두 프록시를 띄우고 앱 내 브라우저로 위 1~5단계를 진행했다. 종료 전 차단 사유 2회(예약 전·입실 전), 고객 입실, 업무 자동/수동 완료, 종료, 채팅 입력 비활성화를 확인했다. 이어 HTTP로 종료·입실의 같은 키 재생(201), 새 키 409(`CASE_ALREADY_CLOSED`·`CASE_CLOSED`), 상담사 입실 403, 같은 메모 업무 재생 200·다른 메모 409, 잘못된 본문 400, 고객 프록시의 업무 변경 403을 확인했다.

핵심 업무 규칙은 `ClosureIntegrationTest`가 실제 V1 행에서 검증한다. 견적·제안·선택·예약 서비스를 그대로 거치며 공급사·Agent HTTP만 대역이다. 종료는 예약 원장 위에서만 의미가 있으므로 예약 전용 DB를 함께 사용한다.

| 시나리오 | 검증 내용 |
| --- | --- |
| 3박 정상 종료 | 예약 전 차단 3종(대체 예약 없음·숙박일 미보장·필수 업무) → 예약 후 입실·업무 차단 → 입실 뒤 원예약 후속만 차단 → 종료. 차단 중 사건 상태·종료 기록 불변, 같은 키 재생·새 키 409, 종료 후 입실 409 |
| 1박 부분 대체 | 입실·모든 업무 완료 뒤에도 11/11·11/12 미확인으로 `STAY_NOT_COVERED`. 두 날짜가 VERIFIED+AVAILABLE이 된 뒤에만 종료 |
| 미확정 예약 | PROCESSING 예약의 입실은 409 `REPLACEMENT_NOT_CONFIRMED` |

```powershell
$env:REPLACEMENT_TEST = 'true'
$env:REPLACEMENT_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:25444/yeohaengbom_replacements_test'
$env:REPLACEMENT_TEST_DB_USER = 'yb_replacement_test'
# REPLACEMENT_TEST_DB_PASSWORD는 전용 테스트 DB 비밀번호
.\gradlew.bat test --tests 'com.csro.backend.closure.ClosureIntegrationTest'
```

Windows에서 새 tmpfs 전용 DB로 `ClosureIntegrationTest` 3개와 기존 `ReplacementIntegrationTest` 15개가 통과했다. 그 밖에 `ciUnitTest`(인증 경계 테스트 포함), 프런트 빌드·`tsc --noEmit`·`node --test tests/demo-closure-proxy.cjs`를 확인했다. 기존 프록시 테스트 4개는 Windows에서 `ECONNRESET`으로 실패하며 `main`의 원본 프록시에서도 같다.

미검증: GitHub Actions 원격 실행. 호텔·공급사 출처 입실, 원예약 자동 취소·환불, 운영 인증은 범위 밖이다.
