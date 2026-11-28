<div align="center">

# 여행봄 (CSRO)
### 숙박 예약의 위험을 먼저 발견하고, 확인부터 고객 구제까지 연결하는 상담 지원 서비스

** 멀티 AI Agent 기반 지능형 고객상담 프로젝트 · 2026.10 ~ 2026.12(8주)

<!-- TODO: 서비스 대표 이미지 또는 실제 화면 GIF -->
<!-- TODO: 서비스 배포 주소 · 데모 영상 · 최종 발표 자료 링크 -->

</div>

---

## 1. 프로젝트 소개

숙소를 예약했다는 사실만으로 **체크인 당일 객실 이용까지 보장되는 것은 아닙니다.** 공급사의 예약 계약은 확정되어 있어도 호텔의 객실 제공 상태가 다르거나, 일부 숙박일에 문제가 발생할 수 있습니다.

**여행봄**은 이런 숙박 예약 위험을 선제적으로 살펴보고, 공급사·호텔 재확인으로 근거를 수집하며, 이상이 확인되면 상담 사건과 대체 숙소 대응으로 이어지도록 설계한 **여행 예약 고객상담 지원 서비스**입니다.

상담업무 AI 지원 과제를 **숙박 예약 장애의 사전 탐지와 고객 구제**라는 구체적인 상황으로 확장했습니다. AI가 장애를 확정하거나 고객 대신 예약을 임의로 처리하는 것이 아니라, **위험 신호·상대방의 주장·검증된 사실·상담사의 결정**을 구분하는 것이 핵심입니다.

## 2. 어떤 문제를 해결하나요?

| 문제 | 여행봄의 해결 방향 |
| --- | --- |
| 예약 확정과 실제 객실 제공 여부가 다를 수 있음 | 공급사 계약 상태와 호텔의 **숙박일별 제공 상태를 분리**해 재확인 |
| 문제가 체크인 직전에야 드러날 수 있음 | ML로 **호텔·체크인 날짜 단위의 위험 신호**를 우선순위화 |
| 호텔의 모호한 회신을 사실로 오인할 수 있음 | 회신 원문·Agent 해석·검증된 사실을 구분하고 미확인은 미확인으로 유지 |
| 상담사가 근거와 후속 업무를 흩어져 관리함 | 동일 예약의 사건·근거·다음 조치를 하나의 상담 흐름에서 조회 |
| 기존 숙소 이용이 어렵다면 대체안 마련이 필요함 | 전체 숙박기간 보장을 우선하는 **대체 숙소 탐색 및 대응 흐름** 설계 |

## 3. 핵심 서비스 흐름

```text
숙박 예약 / 체크인 예정
          │
          ▼
[ML] 호텔·날짜별 위험 신호 분석
          │
          ▼
[재확인] 공급사 계약 + 호텔 숙박일별 회신
          │
          ▼
[Agent 보조] 회신 내용에서 주장·원문 근거 추출
          │
          ▼
[Spring] 출처·예약·숙박일 검증, 사실 상태 기록
          │
          ├── 이상 없음 / 미확인 → 상태 유지·추가 확인
          │
          └── 장애 확인 → 상담 사건·후속 업무
                              │
                              ▼
                       상담사 검토·고객 안내
                              │
                              ▼
                   대체 숙소 탐색·후속 처리
```

> **설계 흐름과 구현 현황은 구분합니다.** 위는 서비스가 지향하는 전체 업무 흐름이며, ML → Spring → 재확인 → Agent → 상담 → 대체 예약까지 운영 환경에서 완전히 연결되었다는 뜻은 아닙니다.

## 4. 주요 기능과 현재 구현 범위

| 기능 | 사용자 관점에서 하는 일 | 저장소에서 확인한 범위 |
| --- | --- | --- |
| 숙박 예약 상세 조회 | 고객 예약과 숙박 정보를 확인 | Spring API-001 조회·오류 처리 코드. 실제 Bearer 인증 연결 등은 후속 작업 |
| 고객 체크인 문제 신고 | 예약 소유 고객이 긴급 상담을 접수 | API-002 신고·채팅·활성 사건·필수 업무·재확인 대기 원자 저장과 멱등 재시도. 별도 고객 로컬 시연을 제공하며 신고만으로 객실 불가를 확정하지 않음 ([계약·실행](backend/docs/api/API-002.md)) |
| 숙박 위험 탐지 | 문제 가능성이 높은 호텔·날짜를 먼저 확인 | 합성 데이터 기반 LightGBM·API-023과 Spring 공통 집계·평가 저장·재확인 자동 배치. 실제 운영 관측 수집은 후속 |
| 예약·객실 재확인 | 공급사 계약과 호텔 회신을 비교 | 가상 공급사·호텔 API-026/027 및 Spring 재확인·근거 저장 시연 코드 |
| 호텔 회신 해석 | 어떤 숙박일이 가능·불가·미확인인지 구분 | 기존 B팀 API-024와 A팀 Spring의 저장 원문 선택·호출·응답 검증·분석 저장·필수 수동 확인 연결. 사람 확인이 필요한 주장으로 보존하며 숙박일 사실을 자동 변경하지 않음 ([실행·범위](backend/docs/api/API-024-SPRING.md)) |
| 상담 사건 관리 | 사건 현황, 검증 근거, 다음 업무를 파악 | 상담 사건 조회 API 코드 및 로컬 시연용 상담사 화면 |
| 대체 숙소 비교·고객 선택 | 문제가 생긴 예약의 대체 숙소 후보를 검토·전송·선택 | 합성 공급사 견적의 Spring 저장·비교·제안 발송과 고객 본인 선택. 별도 실시간 검색 시연과 구분 |
| 가상 대체 예약 | 상담사가 고객 선택을 가상 공급사에 예약하고 결과를 확인 | 별도 Mock PostgreSQL의 전 숙박일 재고·요청 결과 원자 저장과 Spring API-015/016·동일 요청번호 복구 코드. 최종 통합 검증 진행 중 |
| 입실 확인·사건 종료 | 고객 입실 확인, 원예약 후속 등 필수 업무 완료, 조건 검사 후 사건 종료 | 사용자 승인 API-008(완료만)/017/018 최소 계약, 미충족 시 409 차단 사유. [실행·검증](backend/docs/api/CLOSURE-DEMO.md) |

### 서비스 화면

<!-- TODO: ① 사건 목록·상세 화면 캡처 또는 GIF — 실제 화면으로 교체 -->
<!-- TODO: ② 재확인 근거·숙박일별 상태 화면 캡처 또는 GIF -->
<!-- TODO: ③ 대체 숙소 후보 비교 화면 캡처 또는 GIF -->
<!-- TODO: 사용 시나리오를 3~5컷으로 연결하고, 시연 데이터 여부를 각 캡션에 명시 -->

## 5. 누구에게 어떤 가치를 제공하나요?

**여행 고객**에게는 체크인 시점의 예약 문제를 줄이고, 장애가 확인되었을 때 일관된 안내와 대체 숙소 선택지를 제공하는 것을 목표로 합니다.

**고객센터 상담사**에게는 예약 계약, 날짜별 객실 상태, 호텔의 회신 근거, 다음 할 일을 한곳에서 확인하도록 지원해 반복적인 확인 업무를 줄이는 방향을 제안합니다.

**여행사·예약 플랫폼 및 고객센터 운영 조직**에는 사후 민원 대응에서 **위험 우선순위 기반 사전 점검**으로 전환할 수 있는 운영 모델을 제시합니다.

> 위 내용은 **기대 가치**이며 실제 상담 처리시간 단축, 고객 피해 감소, 운영비 절감 등은 아직 실사용 데이터로 입증된 성과로 표시하지 않습니다.

## 6. 기술 구성

| 영역 | 확인된 기술·역할 |
| --- | --- |
| Frontend | Next.js 16, React 19, TypeScript — 상담 사건 조회 및 대체 숙소 비교 시연 UI |
| Backend | Java 21, Spring Boot 4, Spring Data JPA — 예약·근거·사건 업무 로직 |
| Database | PostgreSQL, Flyway — 공통 업무 데이터와 스키마 관리 |
| ML | Python, LightGBM — 합성 데이터 기반 위험 모델 및 추론 HTTP API |
| Agent | Python, FastAPI — 호텔 회신의 숙박일별 주장·근거 추출 |
| 시연 외부 시스템 | FastAPI Mock 공급사·호텔 — 재확인 시나리오와 예외 응답 재현 |

<!-- TODO: 실제 아키텍처 이미지 — 프론트/백엔드/ML/Agent/Mock/DB 소유권과 호출 방향 표시 -->
<!-- TODO: 개발·시연 구성과 실제 배포 구성을 구분해 표시 -->

### 설계에서 중요하게 본 점

- **위험 점수 ≠ 장애 확정:** ML 점수는 검토 우선순위를 정하는 신호이지, 예약 취소나 고객 피해가 확정되었다는 증거가 아닙니다.
- **예약 확정 ≠ 모든 숙박일 제공:** 공급사 계약과 호텔의 실제 제공 여부를 별도 상태로 관리합니다.
- **호텔의 주장 ≠ 검증된 사실:** 출처·예약·숙박일이 확인된 근거만 사실 상태에 반영하고, 모호한 회신은 사람의 검토를 요구합니다.
- **업무 상태 변경은 Spring에서:** Python ML/Agent는 분석 결과를 반환하고, 사건·예약 상태 변경은 Spring 업무 로직이 담당합니다.

## 7. 검증과 남은 과제

**확인된 검증:** 가상 호텔 100곳의 과정 기반 합성 데이터로 LightGBM 위험 모델을 평가했습니다. 확인 라벨이 있는 테스트 5,204행에서 **AP 0.4550, ROC-AUC 0.9282**를 기록했습니다. 이는 **합성 시뮬레이션 내부의 모델 성능**이며 실제 예약 장애 예측 정확도나 고객상담 개선효과를 의미하지 않습니다. [실험 결과와 한계](ml/docs/RESULTS.md)

합성 관측 기반 위험 점검 자동 배치는 [공통 실행 경로와 검증 범위](backend/docs/api/ML-RISK-COMMON-PATH.md)를 참고합니다. 가상 대체 예약의 [별도 Mock DB·실행·복구 범위](backend/docs/api/REPLACEMENT-DEMO.md)는 운영 공급사 예약과 구분합니다. 가상 예약 단계의 최종 통합 검증, 부분 기간 대체의 종료 차단 검증, 운영 관측 수집, 실서비스 인증·실제 공급사 연동과 운영 환경 검증은 남아 있습니다.

<!-- TODO: 실제 사용자 테스트 결과(측정 방법, 표본 수, 처리시간/품질 수치, 비교 기준) -->
<!-- TODO: 데모 실행 영상 및 상담사 사용성 피드백 -->
<!-- TODO: 시연 외 실서비스 적용 범위·보안·운영 계획 -->

## 8. 팀 소개

<!-- TODO: 실제 팀원 이름·담당 범위·GitHub 프로필 입력 -->

| 담당 | 주요 역할 | 팀원 |
| --- | --- | --- |
| ML·재확인 | 위험 모델, 공급사·호텔 재확인, 근거·사건 흐름 | 작성 예정 |
| Agent·상담 | 호텔 회신 해석, 상담 지원, 대체 숙소 대응 | 작성 예정 |
| Frontend·공통 | 상담사 화면, 서비스 연동·검증 | 작성 예정 |

## 9. 프로젝트 자료

- [업무 규칙과 판단 기준](docs/business.md)
- [API 계약과 책임 경계](docs/api.md)
- [API 구현 현황](backend/docs/api/README.md)
- [ML 모델과 실험](ml/README.md)
- [공통 위험 점검 자동 배치](backend/docs/api/ML-RISK-COMMON-PATH.md)
- [호텔 회신 추출 Agent](llm/agent/README.md)
- [가상 공급사·호텔 시연](mock-server/README.md)
- [가상 대체 예약 실행·복구와 별도 Mock DB](backend/docs/api/REPLACEMENT-DEMO.md)
- [로컬 실행·테스트·문제 해결 가이드](docs/local-development.md)

<!-- TODO: Figma · 화면 설계 · 시연 영상 · 최종 발표 자료 · 팀 회고 링크 -->

## 10. Airbnb 탐색 및 사건 후처리 로컬 시연

Windows PowerShell 기준입니다. JDK 21, Python, Node.js 및 pnpm, Docker Desktop이 필요합니다. 아래 명령은 저장소 루트에서 같은 PowerShell 세션으로 실행합니다. Python 3.14.3에서 의존성과 두 FastAPI 앱 로드를 확인했습니다.

### 1. Python 설치 및 실행 환경 설정

```powershell
$repo = (Get-Location).Path
if (-not (Test-Path .\.venv\Scripts\python.exe)) { python -m venv .venv }
$python = Join-Path $repo '.venv\Scripts\python.exe'
& $python -m pip install -r requirements.txt
& $python -m pip check

$env:OPENAI_API_KEY = (Get-Credential -UserName 'OpenAI' -Message 'Password 칸에 OpenAI API 키 입력').GetNetworkCredential().Password
$env:GOOGLE_MAPS_API_KEY = (Get-Credential -UserName 'Google Maps' -Message 'Password 칸에 Google Maps API 키 입력').GetNetworkCredential().Password
$env:POSTPROCESSING_SERVICE_TOKEN = [guid]::NewGuid().ToString('N')
$env:POSTPROCESSING_AGENT_URL = 'http://127.0.0.1:8003'
$env:CSRO_CHAT_DEMO_TOKEN = [guid]::NewGuid().ToString('N')
$env:AIRBNB_DEMO_BOOKING_ENABLED = 'true'
Remove-Item Env:CSRO_DEMO_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:CSRO_CUSTOMER_DEMO_TOKEN -ErrorAction SilentlyContinue
```

Google Maps 키는 실제 숙소 검색에 사용합니다. 후처리 초안과 문서 생성에는 OpenAI 키가 필요합니다. 후처리 공유 토큰은 Python과 Spring에 전달하고, 상담사 시연 토큰은 Spring과 프록시에 전달합니다. 키와 토큰은 현재 세션 및 그 세션에서 시작한 프로세스에만 설정합니다.

### 2. 전용 PostgreSQL 준비 및 두 Agent 실행

처음 시작할 때는 아래 명령으로 전용 DB를 실행합니다. 이미 같은 이름의 컨테이너가 실행 중이면 실행 명령을 생략하고 기존 컨테이너의 DB 비밀번호를 사용합니다.

```powershell
$env:CSRO_CHAT_DEMO_DB_PASSWORD = [guid]::NewGuid().ToString('N')
$env:POSTGRES_PASSWORD = $env:CSRO_CHAT_DEMO_DB_PASSWORD
docker run --rm --detach --name csro-local-chat-demo-db --tmpfs /var/lib/postgresql -p 127.0.0.1:25434:5432 -e POSTGRES_USER=yb_chat_demo -e POSTGRES_DB=yeohaengbom_chat_demo -e POSTGRES_PASSWORD postgres:18.7
Remove-Item Env:POSTGRES_PASSWORD
```

기존 컨테이너를 재사용할 때는 `$env:CSRO_CHAT_DEMO_DB_PASSWORD = docker exec csro-local-chat-demo-db printenv POSTGRES_PASSWORD`로 현재 세션에 값을 불러올 수 있습니다.

```powershell
docker exec csro-local-chat-demo-db pg_isready -h 127.0.0.1 -U yb_chat_demo -d yeohaengbom_chat_demo
Start-Process powershell.exe -WorkingDirectory $repo -ArgumentList '-NoExit', '-Command', '.\.venv\Scripts\python.exe -m uvicorn llm.alternative-search.app:app --host 127.0.0.1 --port 18025 --no-proxy-headers'
Start-Process powershell.exe -WorkingDirectory $repo -ArgumentList '-NoExit', '-Command', '.\.venv\Scripts\python.exe -m uvicorn llm.postprocessing.api:app --host 127.0.0.1 --port 8003'
```

검색 서버는 [상태 확인](http://127.0.0.1:18025/health)에서 `{"status":"ok"}`를 반환합니다. 후처리 서버는 OpenAPI를 비활성화합니다. 시작 로그로 포트를 확인하거나 아래 요청으로 인증 경로를 확인할 수 있습니다. 이 요청의 기대 응답은 `401 UNAUTHENTICATED_SERVICE`입니다.

```powershell
curl.exe -i -X POST http://127.0.0.1:8003/internal/v1/case-postprocessing/drafts -H "Content-Type: application/json" -d "{}"
```

포트가 이미 사용 중이면 새 Agent를 빈 포트에서 실행하고, 검색은 프론트 빌드의 `NEXT_PUBLIC_AI_SEARCH_API_URL`, 후처리는 Spring을 시작할 때의 `POSTPROCESSING_AGENT_URL`을 변경합니다.

### 3. Spring과 상담사 프론트 실행

원래 PowerShell에서 `java -version`이 JDK 21을 가리키는지 확인한 뒤 실행합니다. DB가 연결을 수락하면 Spring이 Flyway 마이그레이션을 적용합니다.

```powershell
Start-Process powershell.exe -WorkingDirectory (Join-Path $repo 'backend') -ArgumentList '-NoExit', '-Command', '.\gradlew.bat bootRun --args=--spring.profiles.active=local-chat-demo'
```

Spring 시작 로그가 나온 뒤 `Invoke-RestMethod http://127.0.0.1:18082/hello`로 확인합니다. 신규 시연 DB에 사건이 없으면 [상담 사건 시드 절차](backend/docs/api/README.md#api-012-구현-메모)를 따라 샘플 사건을 준비합니다. 실제 검색에는 체크인 날짜가 지나지 않은 시연 사건을 사용합니다.

```powershell
Set-Location (Join-Path $repo 'frontend\travel_bom')
$env:NEXT_PUBLIC_AI_SEARCH_API_URL = 'http://127.0.0.1:18025'
pnpm.cmd install --frozen-lockfile
pnpm.cmd build

$env:PORT = '3100'
$env:CSRO_DEMO_SPRING_PORT = '18082'
$env:CSRO_LOCAL_DEMO = 'true'
$env:CSRO_CHAT_DEMO_PROXY = 'true'
$env:CSRO_AIRBNB_DEMO_PROXY = 'true'
$env:CSRO_CUSTOMER_DEMO_PROXY = 'false'
$env:CSRO_DEMO_TOKEN = $env:CSRO_CHAT_DEMO_TOKEN
node .\demo-server.mjs
```

[상담 사건 목록](http://127.0.0.1:3100/cases)을 엽니다. 조회가 401이면 프록시의 `CSRO_DEMO_TOKEN`과 실행 중인 Spring의 `CSRO_CHAT_DEMO_TOKEN`이 일치하는지 확인합니다. 같은 원래 세션에서 두 서버를 시작하면 토큰을 공유할 수 있습니다.

Airbnb 시연 후보 3곳을 보낸 뒤 약 5초가 지나면 첫 후보 선택이 기록됩니다. 화면 표시에는 채팅 조회 주기만큼 더 걸릴 수 있습니다. **예약 진행**으로 시연 예약을 만들고, **후속 업무 → 초안 생성**에서 문서를 검토하고 DOCX를 내려받을 수 있습니다. 메시지 근거는 큰따옴표로 인용되며 내부 근거 ID는 검증에 사용합니다.

종료할 때 서버 창에서 `Ctrl+C`를 누릅니다. 시연 DB 컨테이너는 메모리 볼륨을 사용하므로 `docker stop csro-local-chat-demo-db`를 실행하면 해당 시연 데이터가 사라집니다.

- [대체 숙소 검색 상세](llm/alternative-search/README.md)
- [후처리 CLI 및 내부 서버](llm/postprocessing/README.md)
