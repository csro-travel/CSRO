# 고객 입실·후속 업무·사건 종료

고객이 확정 대체 예약의 입실을 직접 확인하고, 상담사가 필수 업무를 완료한 뒤 서버의 종료 조건을 통과한 사건을 명시적으로 종료한다. 과거 승인·보류 구현 기준 `1124cy-core-flow/f5add12`를 보존하고, 이번 적용 기준은 API-002·API-024가 포함된 `1125cy-api024/db3b15f`다. [사용자 승인 최소 계약](API-008-017-018-PROPOSAL.md)으로 핵심 시연의 마지막 업무 단계를 연결한다. Notion 팀 전체 승인 상태·운영 배포·실제 호텔 입실 검증을 뜻하지 않는다.

## 이번 단계와 책임

| 기능 | 요청·권한 | 저장·보호 조건 |
| --- | --- | --- |
| API-017 입실 확인 | `POST /api/v1/replacement-bookings/{BRQ}/checkin-confirmations`, 예약 소유 고객, `{"checkedIn":true}` | CONFIRMED 대체 예약·숙박 기간 안의 서버 판단 날짜, 입실·감사·SYSTEM 안내와 해당 입실 업무 완료를 원자 저장 |
| API-008 업무 완료의 최소 부분 | `PATCH /api/v1/cases/{caseId}/tasks/{taskId}`, 상담사 | 기존 task의 DONE·완료 메모·expectedVersion과 원예약 후속 완료값만 허용하는 부분 구현. CHECKIN_CONFIRM을 수동 메모로 완료할 수 없음 |
| API-018 사건 종료 | `POST /api/v1/cases/{caseId}/closures`, 상담사 | 입실·전 기간 보장·결과 불명 및 진행 중 재확인 없음·필수 업무 완료·버전을 잠금 안에서 재검사 |

세 쓰기는 JSON·필수 `Idempotency-Key`와 기존 활성 사용자/소유권 검사를 사용한다. 같은 작업·키·본문은 최초의 불변 결과를 재생하고 다른 본문은 409다. 재생은 버전·메시지·상태를 바꾸지 않는다. 새 상태 변경은 `cases.version`을 증가시키며 업무 완료·종료의 오래된 expectedVersion은 409다.

Spring은 기존 checkin_confirmations·case_tasks·cases·case_actions·chat_messages를 사용한다. 새 테이블·마이그레이션·외부 HTTP는 없으며 Mock 재고·예약과 ML은 바꾸지 않는다. 이 단계가 원호텔 UNKNOWN을 AVAILABLE로 만드는 근거가 되지 않는다.

## 실행과 가상 시각 전진

전체 핵심 흐름을 새 격리 세션에서 실행하려면 저장소 루트의 [공통 실행기](../../../tools/demo/core-demo.py)를 사용한다. Docker Desktop 실행, Java 21·Node·pnpm과 서비스별 Python 의존성 설치가 필요하다. ML은 `ml/.venv`, Mock은 `mock-server/.venv`의 `requirements-stateful.txt`까지 준비한다. Agent는 `llm/agent/.venv`→루트 `.venv`→Mock 가상환경 순서로 실제 import 가능 여부를 확인한다. 프런트 node_modules가 없으면 먼저 `frontend/travel_bom`에서 `pnpm.cmd install --frozen-lockfile`을 실행한다.

```powershell
# 저장소 루트에서 실행하며, 이 터미널을 유지한다.
py tools\demo\core-demo.py
```

기본 실행은 Spring JAR·프런트 빌드·TypeScript를 확인하고, 자기가 소유한 새 전용 업무 DB 25434·Mock DB 25442와 ML 8002·Mock 8001·Agent 8000·Spring 18082·프런트 3100/3101을 시작한다. 기존 5432 업무 DB에는 접근하지 않는다. 필요한 포트가 점유되어 있으면 기존 프로세스·DB를 중단하거나 재사용하지 않고 멈춘다. 이전 시연을 실행 중이었다면 그 세션을 정상 종료한 뒤 시작한다. Mock·Agent·ML의 기본 포트가 사용 중이면 비어 있는 loopback 대체 포트를 각각 `--mock-port`, `--agent-port`, `--ml-port`로 명시할 수 있다. 예를 들어 `py tools\demo\core-demo.py --mock-port 18027 --agent-port 18025 --ml-port 18023`이다. 대체 포트도 비어 있어야 하며, 실행기가 자기 서비스와 Spring 호출 주소를 함께 맞춘다. 일반 서비스 설정·기본 포트는 바꾸지 않는다.

실제 저장 LightGBM과 합성 관측의 공통 위험 배치로 YB-001을 평가하며, 시연에서 선별 경로를 통과하도록 기존 비교 정책 `MAX_OBSERVED_RECALL`을 명시한다. 기본 cold-start 입력의 F1 미선별을 바꾸려고 특징값이나 모델 점수를 조작하지 않는다. 검증된 Mock 회신을 통해 사건을 만든 뒤 상담사·고객 URL을 출력한다.

고객 신고 주소는 `http://127.0.0.1:3101/customer/bookings/YB-001`이다. 여기서 새 API-002 신고를 보내면 이미 있는 활성 사건을 재사용하고 필수 확인 업무·재확인을 접수한다. 운영 로그인이나 API-001의 일반 Bearer 연결이 추가된 것은 아니다.

Agent 주소·토큰은 API-025와 선택적으로 실행할 API-024에 함께 설정한다. 실행기가 정형 Mock JSON을 자동으로 API-024에 보내지는 않는다. 자연어 분석은 [별도 실행 절차](API-024-SPRING.md)의 저장 원문 한 건만 명시적으로 선택한다. 실행했다면 생성된 필수 `VERIFY_HOTEL`도 종료 전에 확인·완료해야 한다.

검색→제안→고객 선택→상담사 가상 예약 CONFIRMED까지 진행한 다음 **실행기 터미널에 `arrive`**를 입력하면 같은 DB·토큰으로 Spring의 가상 시각만 `2026-11-10T15:00:00+09:00`으로 전진해 재시작한다. 이후 양 화면을 새로 조회해 입실·업무·종료를 진행한다. `status`는 현재 사건·부족 조건을 읽고 `stop`은 실행기가 시작한 프로세스·컨테이너만 중단한다. 컨테이너·데이터를 삭제하지 않는다. 실행 로그는 사용자 임시 디렉터리에 보관하며 Git에 추가하지 않는다. 실행기 자체의 최종 검증 여부는 아래 검증 절에 구분한다.

기존 시연을 수동으로 이어갈 때는 다음 절차를 사용한다.

먼저 [가상 예약 시연](REPLACEMENT-DEMO.md)으로 YB-001의 실제 상태 저장 예약을 CONFIRMED까지 만든다. 업무 DB는 기존 전용 `127.0.0.1:25434/yeohaengbom_chat_demo`, 사용자 `yb_chat_demo`를 유지한다. 상담사 3100·고객 3101, 별도 토큰·loopback·독점 `local-chat-demo` 검증도 그대로다. DB·원장을 지우거나 다시 시드하지 않는다.

예약 단계의 판단 시각은 `2026-11-09T10:20:00+09:00`이며 숙박일은 `2026-11-10~13`이다. **예약을 확정한 뒤** Spring을 정상 종료하고 기존 전용 DB·서버 토큰을 유지한 채 아래처럼 숙박일 안으로 가상 시각을 전진해 다시 시작한다. 다음 명령은 기존 실행 안내에서 준비한 JAR·환경을 재사용한다.

```powershell
# 같은 PowerShell을 재사용한다면 프런트용 토큰 별칭을 Spring 시작 전에 제거한다.
Remove-Item Env:CSRO_DEMO_TOKEN -ErrorAction SilentlyContinue
java -jar $offerJar --spring.profiles.active=local-chat-demo --quote.search.demo-as-of=2026-11-10T15:00:00+09:00
```

위 명령은 검색·예약 Worker를 기본 꺼진 상태로 두며 이미 확정한 원장과 재고를 유지한다. 이 단계는 공급사 HTTP를 호출하지 않으므로 기존 Mock의 가상 시계를 입실 시점으로 바꾸지 않는다. 입실 확인을 위해 예약 전부터 날짜를 바꾸면 과거 견적이 만료되므로 순서를 지킨다. `checkin_at`은 서버 판단 시각이며 감사 접수 시각은 실제 시각이다. 서버는 한국 날짜가 `[checkInDate,checkOutDate)` 안인지 확인하며 호텔별 체크인 가능 시각을 검증하는 것은 아니다.

프런트 두 프로세스를 재시작할 때 기존 실행 설정에 `CSRO_RESOLUTION_DEMO_PROXY=true`를 **시작 전** 추가한다. 상담사에는 정확한 API-008/018 경로, 고객에는 API-017 경로만 추가 허용한다. 기존 CSRO_REPLACEMENT_DEMO_PROXY와 토큰·Origin·Sec-Fetch·Host·멱등키 검사도 유지한다. 프런트용 `CSRO_DEMO_TOKEN`은 상담사 토큰과 같은 별칭으로 다시 설정하되 Spring 시작에 혼용하지 않는다.

## 화면에서 수행할 순서

1. 고객의 `/customer/cases/{실제 사건 번호}`에서 확정 예약·합성 판단 시각을 확인하고 직접 입실 확인을 누른다. 상담사가 고객 대신 확인하지 않는다. `checkedIn:false`, 임의 checkinAt·고객 ID·새 가격은 받지 않는다.
2. 상담사는 사건 상세에서 새로 읽은 버전과 `resolution`을 확인한다. CONTACT_CUSTOMER·VERIFY_HOTEL 등 화면에 남은 모든 필수 업무별로 실제 수행한 안내·확인 내용을 적어 완료한다. API-002·API-024가 추가한 확인 업무도 생략하지 않는다. CHECKIN_CONFIRM은 고객 입실 확인 근거로만 완료된다.
3. ORIGINAL_FOLLOWUP에는 `COMPLETED` 또는 `NOT_REQUIRED`와 사유를 기록한다. 요청·인계·대기를 완료로 기록하지 않는다. 시연 메모에는 **실제 취소·정산을 실행하지 않았음**을 명시한다.
4. 부족 조건을 확인한 뒤 종료 사유를 적어 별도의 사건 종료 버튼을 누른다. 실패하면 원인을 해결하고 최신 상태·버전을 다시 읽는다. 응답이 불명확하면 같은 의도·키를 유지해 결과를 확인한다.
5. 상담사·고객 화면을 새로 조회해 CLOSED·입실 요약·종료 결과를 확인한다. 종료 사건은 활성 목록에서 빠질 수 있으므로 기존 사건 주소·채팅 이력으로 기록을 확인한다.

API-008 요청 예시:

```json
{"status":"DONE","followupOutcome":"COMPLETED","completionNote":"시연용 원예약 후속 처리 완료를 수동 확인함. 실제 취소·정산은 실행하지 않음","expectedVersion":3}
```

followupOutcome은 ORIGINAL_FOLLOWUP에만 사용한다. 다른 업무에는 status·completionNote·expectedVersion만 보낸다. API-008은 이번에 기존 업무의 완료만 지원하며 생성·선점·담당자·기한 편집·일괄 완료·완료 취소를 구현한 것이 아니다.

API-018 요청 예시:

```json
{"reason":"고객 입실과 전 숙박일 보장 및 원예약 후속 업무 확인 완료","expectedVersion":4}
```

버전 숫자는 예시이며 현재 API-004 조회 값을 사용한다. 입실·업무 완료 등 다른 변경 뒤에는 다시 조회한다. completionNote는 trim 후 1~1000자, 종료 reason은 1~80자다. 고객 입실은 201, 업무 완료는 200, 종료는 201의 불변 결과를 반환한다.

## 종료 조건과 단일 부분 대체의 한계

서버는 사건이 열려 있고 버전이 맞는지, 처리 중·결과 불명 예약이 없는지, 일치하는 확정 대체 예약의 유효한 직접 입실 확인이 있는지 검사한다. 해당 원예약 전체의 RECHECK Job 중 `PENDING/RUNNING/WAITING`이 있으면 `RECHECK_PENDING`으로 종료를 막는다. 다른 사건 연결이나 다른 프로토콜의 Job도 포함하며 해당 작업을 종료 로직이 임의로 취소·완료하지 않는다. 원예약 booking_nights의 날짜 누락도 종료를 막는다. 모든 필요한 날짜는 입실 확인된 확정 대체 예약의 기간 또는 원호텔의 현재 VERIFIED+AVAILABLE 근거로 보장되어야 한다.

필수 CHECKIN_CONFIRM·ORIGINAL_FOLLOWUP이 존재하고 근거와 함께 DONE이어야 하며 VERIFY_HOTEL을 포함한 나머지 is_required 업무도 전부 DONE이어야 한다. 재확인이 끝났어도 수동 확인 업무가 자동 완료되는 것은 아니다. 필수 업무 개수는 신고·분석 이력에 따라 달라지므로 고정된 개수만 완료하고 종료하지 않는다. 필수 업무가 없거나 완료 사유가 비었다고 할 일이 없는 것으로 간주하지 않는다. 실패는 409 CASE_NOT_RESOLVED와 구조화된 부족 조건이며 상태·감사·메시지를 부분 저장하지 않는다.

전 기간 대체 예약이 보장하면 원호텔의 미확인 날짜를 거짓 AVAILABLE로 고치지 않고도 종료할 수 있다. 부분 대체는 **한 건의 확정 대체와 남은 원호텔의 검증된 보장**으로 전체 기간을 충족할 때만 종료한다. 남은 UNKNOWN·CONFLICT·미보장 날짜가 있으면 차단한다. 현재 보호 장치는 확정 예약 뒤 추가 제안·예약을 막으므로 여러 부분 예약을 순차로 추가하는 기능으로 표현하지 않는다.

성공은 CLOSED·closed_at·close_reason·버전과 종료 근거 스냅샷·SYSTEM 안내를 한 번 저장한다. 같은 키 재생은 최초 종료만 반환하고 다른 키로 재종료하면 409다. 예약 성공·고객 선택·완료 메모만으로 자동 종료하지 않는다.

## 읽기와 개인정보 경계

API-004는 상담사용 `resolution`에서 입실·기간 보장·완료를 포함한 필수 업무·완료 내용·부족 조건·종료 결과를 읽는다. 기존 top-level tasks와 nextAction은 계속 미완료 업무만 의미한다. API-011도 역할에 맞는 resolution을 추가하며 기존 메시지·선택·예약 포인터를 유지한다.

고객에게는 본인 입실·사건 상태·공개 종료 결과의 안전한 요약만 반환한다. 상담사 내부 메모·기간 보장 진단·내부 키·종료 사유를 고객 resolution에 노출하지 않는다. 조회는 상태 변경이나 작업 완료를 실행하지 않는다.

## 검증과 완료 범위

Linux에서 현재 코드의 집중 검증과 전체 대표 시연을 수행했다. 원시 로그·토큰·실행 환경 덤프는 저장소에 추가하지 않는다.

| 검증 | 이번 결과 |
| --- | --- |
| Spring HTTP·인증·DB 오접속 차단·입실/업무/종료·조회 | 43개 검사 대상 확인. 최초 42개 통과 후, DB 시간 정밀도를 반영해 수정한 신고 연계 검사 1개도 재실행 통과. `bootJar` 성공 |
| API-002·API-024 연계 | 실제 신고 서비스의 기존 사건 재사용·종료 후 동일 키 재생·새 신고의 새 활성 사건, 실제 분석 서비스가 생성한 VERIFY_HOTEL의 종료 차단/완료를 DB 검사로 확인 |
| 예약 전체 재확인 대기 | PENDING/RUNNING/WAITING 3상태, 다른 소유자 및 case_id 없는 RECHECK도 차단. 거부 시 작업·사건·감사 불변, 작업 종결 후 종료 확인 |
| 실패·동시성 | 종료 감사 저장 중 실패의 전체 롤백, 동시 입실/종료의 단일 저장, 버전 충돌·부분 숙박 보장·불변 응답 재생 확인 |
| 프런트 | Next production 빌드·별도 `tsc --noEmit`, 기존 신고를 포함한 프록시 26개 및 응답 제어 `case-resolution-browser.py` 통과 |
| 실행기 | `python3 -m unittest discover -s tools/demo -p test_core_demo.py` 안전 검사 6개 통과. 실제 start→arrive→stop에서 자기 자원만 종료하고 DB 데이터를 보존 |
| 실제 서비스·화면 | Agent 18024·Mock 18027·ML 18002·Spring 18082·새 전용 DB 25434/25442·상담사 3100/고객 3101로 연결. 실제 저장 모델 위험 점검→사건→고객 신고 합류→3곳 검색/제안→고객 선택→가상 예약 CONFIRMED→고객 입실→필수 업무→CLOSED 확인 |
| 응답 유실·개인정보 | 실제 종료 201을 커밋한 뒤 브라우저 응답을 유실시키고, 새로고침 후 종료 사건 복구 목록에서 같은 키·본문·최초 201 응답 재생 확인. 고객에게 내부 완료 메모·종료 사유 비노출 |
| 최종 DB | 사건 1건 CLOSED·입실 1건·CASE_CLOSED 감사 1건·필수 업무 4건 모두 DONE·Mock 예약 요청 1건. 원예약 CONFIRMED와 첫날 UNAVAILABLE/VERIFIED·나머지 UNKNOWN/UNCONFIRMED 유지, 종료 전후 Mock 재고 동일 |
| Windows 및 원격 CI | 이번 입실·종료 단계는 미실행. 기존 Windows의 앞 단계 검증과 구분함 |

첫 실제 입실일 이전 확인과 필수 업무 미완료 종료는 각각 409로 거부됐다. 상담사 데스크톱과 고객 모바일 화면도 확인했다. 시연은 CONTACT_CUSTOMER·VERIFY_HOTEL·CHECKIN_CONFIRM·ORIGINAL_FOLLOWUP을 완료했으며, 다른 이력에서 필수 업무 개수가 같다고 가정하지 않는다.

집중 Spring 명령은 `test --tests 'com.csro.backend.resolution.*' --tests '*LocalChatDemoAuthenticationTest' --tests 'com.csro.backend.cases.CaseMessagesIntegrationTest' bootJar`다. 아래 전용 resolution DB 설정과 별도로 메시지 조회 검사는 기존 `CHATTING_API_INTEGRATION_TEST` 및 전용 `25433/yeohaengbom_chat_api_test` 설정을 사용한다. 시간 정밀도 수정 후에는 실패했던 `CaseResolutionIntegrationTest.realIncidentReceiptSurvivesClosureAndOnlyANewReportCreatesAnotherActiveCase`만 재실행했다. 프록시 검사는 기존 messages·quotes·offers·replacements·incidents와 새 resolution 파일을 함께 실행한다.

쓰기 통합 검사는 전용 `127.0.0.1:25445/yeohaengbom_resolution_test`, 사용자 `yb_resolution_test`에서만 수행한다. 설정·실제 DB/사용자를 Spring·Flyway 전에 확인하며 일반 업무 DB·시연 DB를 테스트로 재사용하지 않는다. 소유권, 미래 날짜 확인 차단, 입실 없는 종료, 부분 대체의 남은 UNKNOWN, 필수 업무 누락·미완료, 키 재생/본문 충돌, 동시 확인/종료, 버전 충돌, 실패 원자성과 고객의 상담사 명령 거부를 검증한다.

코드가 제공하는 것은 합성 자료를 사용하는 고객 직접 입실 기록·상담사의 수동 후속 완료·검증된 종료다. 실제 호텔·공급사의 입실 검증, 실제 취소·환불·정산, 운영 로그인·배포·실사용 성과를 완료했다고 주장하지 않는다. 현재 작업 브랜치와 main 반영 여부, Linux·Windows·원격 CI의 실행 여부도 구분한다.
