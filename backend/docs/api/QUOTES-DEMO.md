# 사건의 대체 견적 검색·저장·조회

API-010 검색 접수 → QUOTE_SEARCH 작업 → API-028 → 검증 견적 저장 → API-025 → API-009 조회 → 사건의 ‘대체 숙소’ 탭을 연결한다. [사용자 승인 최소 계약](API-009-010-PROPOSAL.md)을 따른다. 팀 전체의 Notion 상세 설계 승인이나 운영 인증 완료를 뜻하지 않는다.

## 구현 범위

- 같은 키·같은 조건은 같은 검색 작업을 반환한다. 접수 응답은 202이며 완료는 별도 조회한다.
- 작업은 원예약 전체 기간을 먼저 검색한다. 정상 빈 결과일 때만 VERIFIED+UNAVAILABLE 날짜의 연속 구간을 검색한다.
- 원본 견적을 먼저 저장하고 저장 행을 다시 읽어 비교 Agent에 전달한다. Agent 오류 시 공급사 원본 후보를 보존한다.
- 같은 검색의 재시도는 스냅샷을 덮어쓰지 않는다. 실행 중단 후 QUOTES_SAVED 단계부터 복구하며 이전 실행권의 늦은 응답은 반영하지 않는다.
- 부분 기간에는 원예약 전체 금액과의 차액을 표시하지 않는다. 원예약의 미확인 숙박일·사건·필수 업무 상태를 변경하지 않는다.
- 고객 후보 전송·선택은 [별도 계약과 실행 안내](OFFERS-DEMO.md)로 연결한다. 예약·입실·종료는 후속 단계다.

## 서버 설정

| 설정 | 의미 |
| --- | --- |
| `booking.reconfirmation.mock.base-url` | 기존 Mock 주소 재사용, 기본 127.0.0.1:8001 |
| `booking.reconfirmation.mock.service-token` | Mock의 MOCK_SERVICE_TOKEN과 같은 값, 미설정 시 호출 차단 |
| `quote.ranking.agent.base-url` | API-024/025 Agent 주소, 기본 127.0.0.1:8000 |
| `quote.ranking.agent.service-token` | Agent의 AGENT_SERVICE_TOKEN과 같은 값 |
| `quote.search.worker.enabled` | 기본 false. 기존 DB 신원 확인을 통과한 local-chat-demo 단독 프로필에서만 true 허용 |
| `quote.search.demo-as-of` | 선택적 가상 시각. 위 격리 프로필에서만 허용. 미설정은 REAL_TIME |
| `CSRO_QUOTE_DEMO_PROXY=true` | 3100→18082 격리 프록시에서 견적 검색 POST 허용. 기존 채팅 옵션과 별개 |

일반 프로필에서 HTTP API는 기존 인증 주체를 요구한다. `X-Demo-Subject` 등 브라우저 입력으로 인증을 만들지 않는다. 운영 인증 체계는 기존 공통 후속 과제로 남아 있다.

## 견적용 시계와 작업용 시계

Mock은 2026-11-10~13 숙박, 11/9 10:15 조회·10:25 만료의 고정 합성 fixture다. 이 데이터의 시연은 가상 판단 시각 `2026-11-09T10:20:00+09:00`을 명시한다. 화면에 해당 시각과 합성 데이터임을 표시한다. PC 날짜나 공급사 조회 시각을 변경하지 않는다.

SYNTHETIC_DEMO는 이 시각을 고정한다. REAL_TIME은 HTTP 응답 검증과 조회 시 실제 처리 시각을 사용하며 시간이 지나면 `expired=true`다. Job 선점·45초 lease·15분 처리 기한은 항상 실제 처리 시계를 사용한다. 공급사 checkedAt을 현재 시각으로 간주해 견적을 유효하게 만들지 않는다.

## 로컬 시연 실행

기존 [격리 local-chat-demo DB 준비](README.md#spring-http-로컬-시연)를 따른다. 사용 주소는 127.0.0.1:25434, DB yeohaengbom_chat_demo, 사용자 yb_chat_demo다. 일반 Compose의 업무 DB 5432에는 이 시연을 적용하지 않는다. 비밀번호·서버 토큰은 현재 PowerShell의 환경변수로 전달하며 Git에 저장하지 않는다.

Mock·ML·Agent는 각자의 실행 문서대로 시작한다. 이 시연에서 8000은 `llm/agent`의 API-024/025 프로세스다. 각 서비스의 토큰과 Spring 설정을 일치시킨다. 새 터미널의 환경변수는 다른 터미널에 자동 공유되지 않는다.

YB-001의 사건이 이 **전용 DB에** 이미 있다면 바로 Spring 웹 실행으로 진행한다. 새 전용 DB라면 기존 공통 위험 배치로 YB-001 사건을 먼저 만든다. `backend/tools/seed-chat-demo-case.sql`은 11/22의 YB-002 별도 채팅 fixture이므로 이번 11/10 견적의 준비 데이터로 사용하지 않는다.

프로젝트 루트에서 다음 명령을 실행한다. 기존 ML 가상환경·준비 함수로 manifest를 만들고 공통 Spring 배치에 전달한다.

```powershell
Push-Location ml
$quoteManifest = & .\.venv\Scripts\python.exe -c "from pathlib import Path; from travel_ml.risk_automation import load_sources, prepare_cycle; p,f=prepare_cycle(load_sources(Path('config/risk_automation_sources_v1.json')), '2026-11-10', Path('generated_quotes_demo')); assert not f; print(p)"
if ($LASTEXITCODE -ne 0) { Pop-Location; throw '시연 관측 준비 실패' }
$quoteManifest = (Resolve-Path $quoteManifest).Path
Pop-Location

Set-Location backend
.\gradlew.bat bootJar
if ($LASTEXITCODE -ne 0) { throw 'Spring 빌드 실패' }
$quoteJar = (Get-ChildItem build\libs\*.jar | Where-Object Name -NotLike '*-plain.jar' | Select-Object -First 1).FullName
java -jar $quoteJar --spring.profiles.active=local-chat-demo --spring.main.web-application-type=none --risk.batch.enabled=true "--risk.batch.manifest-file=$quoteManifest" --risk.batch.policy=MAX_OBSERVED_RECALL
if ($LASTEXITCODE -ne 0) { throw '기존 위험 배치 실패' }
```

MAX_OBSERVED_RECALL은 기존 세 선별 정책 중 합성 fixture 시연을 위한 명시적 선택이다. 운영 권고 정책으로 바꾸거나 점수를 올려 학습하지 않는다. 공통 배치의 `ML_RISK_BASE_URL`·`ML_RISK_SERVICE_TOKEN`, `BOOKING_RECONFIRMATION_MOCK_BASEURL`·`BOOKING_RECONFIRMATION_MOCK_SERVICETOKEN` 설정은 기존 실행 가이드를 따른다.

같은 전용 DB·토큰 설정으로 Spring 웹을 시작한다. Agent 주소·토큰도 Spring에 설정해야 한다. `quote.ranking.agent.service-token`은 같은 PowerShell에서 `QUOTE_RANKING_AGENT_SERVICE_TOKEN` 환경변수로 전달할 수 있다.

```powershell
java -jar $quoteJar --spring.profiles.active=local-chat-demo --quote.search.worker.enabled=true --quote.search.demo-as-of=2026-11-09T10:20:00+09:00
```

프런트는 기존처럼 빌드한 뒤 새 터미널에서 격리 프록시를 켠다. CSRO_DEMO_TOKEN은 위 Spring의 CSRO_CHAT_DEMO_TOKEN과 같은 값이어야 한다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\frontend\travel_bom
pnpm.cmd build
if ($LASTEXITCODE -ne 0) { throw '프런트 빌드 실패' }
$env:PORT = '3100'
$env:CSRO_DEMO_SPRING_PORT = '18082'
$env:CSRO_LOCAL_DEMO = 'true'
$env:CSRO_CHAT_DEMO_PROXY = 'true'
$env:CSRO_QUOTE_DEMO_PROXY = 'true'
node demo-server.mjs
```

`http://127.0.0.1:3100/cases`에서 YB-001 사건 → 대체 숙소 → 대체 숙소 검색을 누른다. ‘접수됨/검색 중’ 이후 A/C/B 순서와 480,000/465,000/510,000원의 3박 총액이 나타난다. 같은 접수 키를 재전송하면 작업·견적을 늘리지 않으며, 새로운 검색은 새 스냅샷 ID를 사용한다.

## 검증 범위

Linux에서 견적 관련 단위 23개·격리 DB 통합 10개, Mock ID 검사 12개, 프록시 4개, 빌드·TypeScript와 Chromium 검사를 통과했다. 별도 새 DB에서 실제 ML·Mock·Agent·Spring·브라우저를 실행해 YB-001 위험 점검→C-001 생성→검색 접수→견적 저장·비교→후보 표시를 확인했다. 같은 키 재전송은 한 검색을 유지했고, 새 검색 후에는 검색 2건·견적 6건·비교 2건, 사건 1건·필수 작업 1건이었다. 데스크톱·모바일에서 확인했다. Windows에서도 동일한 단위 23개·격리 DB 통합 10개·Mock 12개·프록시 4개와 프런트 빌드·TypeScript 검사가 통과했으며, 전체 서비스와 브라우저의 Windows 시연은 아직 확인하지 않았다.

일반 단위 검사는 `backend`에서 `.\gradlew.bat test --tests 'com.csro.backend.quotes.*'`다. DB 통합 검사는 QUOTE_SEARCH_TEST=true와 정확한 전용 설정을 명시해야 실행하며, 미설정 시 건너뛴다. 전용 URL은 `jdbc:postgresql://127.0.0.1:25440/yeohaengbom_quotes_test`, 사용자 `yb_quote_test`, 비밀번호는 QUOTE_SEARCH_TEST_DB_PASSWORD다. 실제 DB 이름·사용자를 Flyway 전에 검사한다. 일반 업무 DB에 연결하지 않는다.
