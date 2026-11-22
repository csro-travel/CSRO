# ML 위험 평가 → Spring 저장·재확인 연결: 1단계

> 이 문서는 1단계 구현 당시의 범위와 수동 입력 기록이다. 현재 코드는 [2단계](ML-RISK-RECHECK-STAGE2.md)의 오프라인 이벤트 입력과 영속 RECHECK 작업을 추가했다. 아래의 “worker·복구 미구현” 설명과 초기 브랜치 생성 명령을 현재 작업 절차로 사용하지 않는다. 기존 수동 요청 JSON과 세 임계값은 그대로 지원한다.

Spring이 기존 API-023에 시연 입력을 보내고, 유효한 위험 평가를 업무 DB에 저장한 뒤 임계값으로 재확인 여부를 결정한다. 첫 실행은 기존 F1 기준을 유지한다. 예약 1건·비성수일 fixture는 이 기준에서 **미선별**되므로, 위험 평가 저장과 재확인 미호출을 확인하는 단계다.

[API-023 현행 구현 계약](../../../ml/docs/api/API-023.md) · [업무 규칙](../../../docs/business.md) · [기존 재확인·근거 저장 시연](booking-reconfirmation-demo.md) · [ML 담당 설계](../../../ml/docs/ML_DATA_RULES_v0.2.md)

## 구현 범위

- 내부 Java 진입점: `RiskRecheckService.assessAndReconfirm(request, policy)`. 공개 HTTP 엔드포인트를 추가하지 않는다.
- Spring → Python: 기존 `POST /internal/v1/risk-assessments`. 요청 5필드·응답 6필드를 유지한다. Python은 JSON 분석 결과만 반환한다.
- 현재 로컬 시연의 호텔·대상 예약 수를 확인하고 ML을 호출한다. 외부 HTTP 호출은 DB 트랜잭션 밖에서 실행한다.
- 유효한 응답을 기존 `risk_assessments`에 짧은 트랜잭션으로 저장·커밋한다. 테이블·Flyway V1·기존 로컬 시드를 변경하지 않는다.
- API 원점수에 `riskScore >= threshold`를 적용한다. 미선별이면 공급사·호텔 재확인 서비스를 호출하지 않는다.
- 선별이면 기존 `BookingReconfirmationEvidenceService`로 각 대상 예약을 재확인한다. 원예약 매핑과 호텔 근거를 검증하는 기존 절차를 거친다. ML 점수만으로 `UNAVAILABLE`, Case, CaseTask를 만들지 않는다.
- 실행 결과에 평가 ID, 적용 정책·임계값, 원점수, 선별 여부와 재확인 결과를 남긴다. 이 내부 결과를 고객 예약 상세 응답에 추가하지 않는다.

스케줄러, `jobs` worker, 업무 큐와 원자적 발송 보장, 운영 배포, 새로운 공개 API는 이번 범위에 없다.

## 모델·특성과 세 정책

고정 모델은 `process-synthetic-lightgbm-0.2-84d386281d22`, 특성 버전은 `risk-features-0.2`다. 모델 파일 SHA-256은 `84d386281d22f4050cb6a8a02cc03d3bcf09e64194213f613e1069e915d68c88`이다. 모델 재학습·자동 교체·XGBoost 전환을 수행하지 않는다.

| 순서 | 정책 이름 | 임계값 | 의미 |
| --- | --- | ---: | --- |
| A · 첫 실행 기본값 | `F1_BASELINE` | `0.22344968880463367` | 기존 10월 검증 F1 기준 |
| B · 후속 비교용 유지 | `RECALL_50_F1` | `0.08083838778859916` | 검증 Recall ≥50% 후보에서 F1 최대 |
| C · 후속 비교용 유지 | `MAX_OBSERVED_RECALL` | `0.008907415636863826` | 검증에서 관측된 Recall 최대 후보의 가장 높은 임계값 |

세 정책을 같은 실행에서 연속 적용하지 않는다. 첫 시연은 A를 사용하고 B·C는 후속 단계에서 명시적으로 선택한다. C는 검증 표본에서의 최대 Recall이며, 실제 호텔 또는 미래 데이터의 Recall 100%를 보장하지 않는다. 새 임계값은 기존 테스트 결과를 본 이후 정리한 운영 비교안이다. 독립 최종 검증이나 현실 성능 입증으로 설명하지 않는다.

| 특성 | v0.2 입력 범위 | 이 시연의 출처 |
| --- | --- | --- |
| `pastFailureRate90d` | 유한 수, 0~1 | 수동 가상 과거 이력 가정 |
| `targetConfirmedBookingCount` | 0 이상 정수 | 시연 대상 수와 대조해야 하는 입력 |
| `bookingVolumeGrowth7d` | 유한 수, -1~10 | 수동 가상 예약량 가정 |
| `reconfirmationOverdueRate7d` | 유한 수, 0~1 | 수동 가상 재확인 이력 가정 |
| `inventoryUnavailableRate7d` | 유한 수, 0~1 | 수동 가상 재고 응답 가정 |
| `isPeakDay` | JSON boolean | 예시 날짜는 `false` |

기준 시각은 체크인 D의 전날 09:00:00 `Asia/Seoul`이다. 같은 순간을 나타내는 UTC 표현도 가능하다. 여기서는 날짜를 2026년 11월 시연 fixture에 맞추며 PC 시계나 2025년 학습·평가 구간을 바꾸지 않는다.

## 입력은 실제 DB 집계가 아니다

제공한 JSON 두 개는 API·Spring 흐름을 점검할 **수동 합성 fixture**다. 과거 90일 장애율, 예약량 증가율, 재확인 지연율, 재고 미제공률을 실제 업무 DB 이벤트로 계산하지 않았다. 실제 호텔 데이터, 새 학습 데이터, 정답이 붙은 성능 평가 표본으로 취급하지 않는다.

대상 검사는 `SIMULATED` 호텔에 대해 현재 `CONFIRMED`이고 생성 시각이 `asOf` 이하인 `MOCK_SUPPLIER` 예약을 사용한다. 다른 공급사의 대상 예약을 섞거나 입력의 대상 수와 DB 대상 수가 다르면 실행을 거부한다. 현재 bookings 테이블의 생성 시각·현재 상태만으로 과거 취소·변경 이력을 복원할 수 없으므로, 이 검사를 일반적인 과거 시점 코호트 재구성으로 설명하지 않는다.

현재 로컬 시드는 `HOTEL-001`에 YB-001 한 건과 11/10·11·12의 세 숙박일을 준비한다. **예약 수 1과 숙박일 수 3은 다르다.** 호텔의 그날 대상 예약 수를 실제로 추가하지 않고 `targetConfirmedBookingCount=12`로 바꾸어 선별을 강제하지 않는다.

| 파일 | 수동 가정 | 고정 모델 원점수 | A 기준 |
| --- | --- | ---: | --- |
| [risk-stage1-high-count1.json](../../examples/risk-stage1-high-count1.json) | 과거 장애율 .06, 예약 수 1, 증가율 .4, 지연율 .95, 재고 미제공률 .9, 비성수일 | 약 `0.16093129796741762` | 미선별 |
| [risk-stage1-low-count1.json](../../examples/risk-stage1-low-count1.json) | 과거 장애율 .005, 예약 수 1, 나머지 비율·증가율 0, 비성수일 | 약 `0.008501510369138389` | 미선별 |

높은 신호 예시의 수치는 과거 알려진 결과 80건 중 장애 5건에 Beta(1,19) 사전값을 더한 `.06`, 신규 7일 6건·직전 28일 16건의 증가율, 기한 대상 20건 중 19건 지연, 응답 10건 중 9건 미제공이라는 가정에 대응한다. 이 분모·분자를 DB에 적재하거나 관측한 것은 아니다. 낮은 예시의 `.005`는 알려진 정상 180건·장애 0건 가정에 같은 사전값을 적용한 값이다.

예약 수 1·`isPeakDay=false`를 고정한 모델 분할 조사에서 최대 원점수도 약 `.1609313`이었다. A가 선별하지 않는 결과는 오류가 아니다. B·C의 후속 시연에서 같은 높은 입력이 선별되는지 확인하되 입력을 몰래 바꾸지 않는다.

## Windows에서 첫 실행

### 1. 최신 main에서 새 작업 브랜치 준비

기존 `CSRO` 폴더 한 곳을 사용한다. 새 worktree를 만들지 않는다. 먼저 현재 작업을 확인한다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO
git status --short --branch
git fetch origin
if ($LASTEXITCODE -ne 0) { throw 'fetch 실패' }
git switch main
if ($LASTEXITCODE -ne 0) { throw 'main 전환 실패' }
git pull --ff-only origin main
if ($LASTEXITCODE -ne 0) { throw 'main 갱신 실패: 오류 확인 후 진행' }
git switch -c 1122cy-risk-link
if ($LASTEXITCODE -ne 0) { throw '새 브랜치 생성 실패' }
```

명령이 실패하면 그 자리에서 멈추고 오류를 확인한다. `reset --hard`, 강제 push, 기존 브랜치에 덮어쓰기, 자동 merge를 사용하지 않는다. `?? tools/`는 이전 별도 초안이므로 이 작업에 `git add .`로 함께 올리거나 삭제하지 않는다. ZIP 적용은 새 브랜치 준비 후 안내받은 파일만 기존 저장소에 적용한다.

### 2. API-023 실행 코드 준비 확인

```powershell
Test-Path .\ml\travel_ml\risk_api.py
Test-Path .\ml\requirements-api.txt
Test-Path .\ml\artifacts\reference_model_v02\model.txt
```

API-023 PR #7은 2026-11-22 `main`에 병합됐다. 기준 커밋 `75a331b243d7287a7078e4fbd121f89d8c8ee573`에는 세 파일이 모두 있다. 최신 main을 가져온 뒤 세 결과가 모두 `True`인지 확인하고 아래 Python 서버를 실행한다. 이전 API 브랜치나 어제 ZIP을 추가 병합할 필요는 없다.

로컬 Python 서버를 실행하는 별도 PowerShell에서 진행한다. 이미 ML 전용 `.venv`가 준비됐다면 다시 만들지 않는다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\ml
if (-not (Test-Path .\.venv\Scripts\python.exe)) {
    py -3.13 -m venv .venv
}
.\.venv\Scripts\python.exe -m pip install -r requirements-api.txt
if ($LASTEXITCODE -ne 0) { throw 'ML API 패키지 설치 실패' }
$env:ML_SERVICE_TOKEN = Read-Host '기존 로컬 ML 시연 토큰 입력'
if (-not $env:ML_SERVICE_TOKEN) { throw 'ML 토큰 필요' }
.\.venv\Scripts\python.exe -m uvicorn travel_ml.risk_api:app --host 127.0.0.1 --port 8002
```

토큰 값을 Git·로그·이 문서에 기록하지 않는다. ML 서버와 Spring 호출자에서 같은 `ML_SERVICE_TOKEN`을 사용한다. 실행 중인 다른 서버를 임의로 종료하거나 포트를 가로채지 않는다. API-023 서버의 준비 확인·추론 검사는 API-023 인계 문서에 따른다.

### 3. 업무 DB 준비와 A 정책 수동 실행

기존 로컬 DB와 `.env`를 유지한다. PostgreSQL 비밀번호를 새로 변경하거나 볼륨을 초기화하지 않는다. 별도 Spring 실행 PowerShell에서 진행한다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO
docker compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw '로컬 DB 준비 실패' }
Set-Location .\backend
$env:ML_SERVICE_TOKEN = Read-Host 'ML 서버와 같은 로컬 시연 토큰 입력'
if (-not $env:ML_SERVICE_TOKEN) { throw 'ML 토큰 필요' }
.\gradlew.bat bootRun --args="--spring.profiles.active=local --spring.main.web-application-type=none --risk.demo.enabled=true --risk.demo.request-file=examples/risk-stage1-high-count1.json"
```

`risk.demo.policy`를 생략하면 `F1_BASELINE`을 사용한다. `risk.demo.enabled`를 명시하지 않은 일반 Spring 시작은 이 수동 평가를 자동 실행하지 않는다. 수동 실행은 결과를 출력한 뒤 종료한다. A의 예상 결과는 원점수 약 `.1609313`, 임계값 약 `.2234497`, `selected=false`, 재확인 결과 목록이 빈 상태다. 모델 원점수가 실패하면 0점으로 대신 저장하지 않는다.

이 명령은 성공한 **위험 평가 한 행을 로컬 업무 DB에 실제로 추가**한다. 반복 실행은 새 평가 행을 추가하며, 위험 평가의 요청 멱등성을 보장하지 않는다. 기존 숙박일·근거·Case를 초기 상태로 되돌리지 않는다. 이미 근거·Case가 있는 DB에서 실행했다면 “이번 실행으로 추가 변경되지 않음”과 “처음부터 없음”을 구분한다.

### 4. 저장 값 확인

같은 Spring 터미널에서 서버가 종료된 뒤 저장소 루트로 돌아와 읽기 전용으로 확인한다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO
docker compose exec postgres psql -X -U yb -d yeohaengbom -P pager=off -c "SELECT h.hotel_code, r.target_check_in_date, r.as_of, r.risk_score, r.model_version, r.feature_set_version, r.feature_snapshot FROM risk_assessments r JOIN hotels h ON h.id = r.hotel_id ORDER BY r.id DESC LIMIT 1;"
```

`risk_score`는 기존 DB 타입 `numeric(5,4)` 때문에 `.1609`로 저장될 수 있다. 선별은 저장 후 반올림 값이 아니라 API 원점수로 수행한다. `feature_snapshot`은 여섯 특징값만 저장한다. 기존 테이블에 정책·임계값·선별 결과 전용 컬럼은 없으므로 정책 감사 정보를 DB에 완전히 보존했다고 설명하지 않는다. 내부 실행 결과를 함께 보관한다.

## 실패·재실행 경계

| 상황 | 이번 처리 |
| --- | --- |
| 입력 형식·시간·버전·범위 오류, 최초 DB 대상 불일치 | 거부. ML 호출·위험 평가 저장·재확인으로 진행하지 않음 |
| ML 호출 중 대상 예약 ID·계약 내용 변경 | 저장 직전 재검증에서 거부. 이미 수행한 ML 호출 외에 위험 평가 저장·재확인으로 진행하지 않음 |
| 토큰 미설정, ML 401/400/422/503, 연결 실패·시간 초과·잘못된 응답 | 0점 대체 없이 실패. 위험 평가와 재확인을 만들지 않음 |
| ML 응답은 유효하지만 DB 저장 실패 | 위험 평가 트랜잭션 실패. 재확인 미호출 |
| 평가 저장 후 미선별 | 평가만 저장. 공급사 API-026·호텔 API-027 호출 없음 |
| 평가 저장 후 선별 | 커밋 뒤 기존 공급사→호텔 재확인 흐름으로 진행 |
| 선별 후 알려진 재확인 외부 오류·대기 | 평가 행은 유지. 결과에 실패·대기를 표시하며 점수만으로 사실·사건을 생성하지 않음 |
| 평가 커밋 후 예상 밖 DB 오류·업무 충돌 | 오류를 전파하고 실행을 중단. 평가 행은 남을 수 있으며 완성된 결과나 남은 예약 처리까지 보장하지 않음 |

선별 후 재확인 요청 ID는 평가·예약 식별자에서 만든 안정적인 값이다. 발송 결과가 불명확한 경우 같은 재확인 요청 ID로 결과를 확인해야 한다. 전체 평가 명령을 다시 실행하면 새 평가 ID와 새 요청 ID가 생길 수 있으므로, 이를 “같은 발송 재시도”로 사용하지 않는다.

위험 평가 커밋과 재확인 발송 사이에는 프로세스 중단 가능 구간이 있다. durable outbox·작업 재개·자동 재시도·실패 행 영속 기록은 구현하지 않는다. 현재 단계의 수동 연결 시연 한계를 운영 완료로 표현하지 않는다.

## 핵심 검사와 실행 범위

새 코드는 API-023 HTTP 경계, 원점수 선별, 트랜잭션 분리, 대상 집합 재검증, 저장 실패 시 재확인 중단을 검사한다. 테스트 결과는 PR에 실행 명령·핵심 결과·미검증 범위만 요약하며 콘솔 원시 로그·날짜별 반복 결과·실행 환경 덤프는 Git에 추가하지 않는다. 변경과 무관한 전체 테스트·학습·동일 HTTP 시연을 반복하지 않는다.

Windows에서 이번 코드를 처음 확인할 때는 아래 명령으로 DB 없는 핵심 검사를 실행한다. `RISK_LINK_TEST`를 끄면 전용 DB 통합 검사는 생략된다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\backend
Remove-Item Env:RISK_LINK_TEST -ErrorAction SilentlyContinue
.\gradlew.bat test --tests "com.csro.backend.risk.*"
```

자동 검사 통과와 Windows 실제 서버 실행·팀 통합 환경 검증은 구분한다. 수동 JSON 검사는 실제 호텔 성능 평가나 과거 이벤트 집계 검증이 아니다.

새 쓰기 통합 테스트는 `RISK_LINK_TEST=true`와 아래 전용 DB URL·비밀번호가 모두 있어야 실행한다. 기본 업무 DB URL은 Spring·Flyway 시작 전에 거부한다.

```text
RISK_LINK_TEST_DB_URL=jdbc:postgresql://127.0.0.1:25434/yeohaengbom_risk_link_test
RISK_LINK_TEST_DB_PASSWORD=<새 전용 시연 DB의 비밀번호>
```

전용 DB와 실제 ML·Mock 서버를 준비한 경우에만 `RiskRecheckIntegrationTest`를 실행한다. 기본 테스트 서버 주소는 ML `http://127.0.0.1:18002`, Mock `http://127.0.0.1:18001`이며 `RISK_LINK_TEST_ML_URL`·`RISK_LINK_TEST_MOCK_URL`로 지정할 수 있다. 두 서버 토큰은 각각 `ML_SERVICE_TOKEN`·`MOCK_SERVICE_TOKEN`에서 읽는다. 통합 검사는 비어 있는 전용 DB를 전제로 한다.

팀 공통 DB나 기존 로컬 업무 DB를 테스트 DB로 사용하거나 초기화하지 않는다. 기존 재확인 테스트의 `25433 / yeohaengbom_reconfirmation_test`와도 구분한다. 검증이 필요한 변경이 생겼을 때 해당 검사만 실행한다.
