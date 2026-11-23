# ML 위험 평가 연결: 이벤트 입력과 재확인 작업 복구

같은 `1122cy-risk-link` 브랜치에서 1단계를 보완한다. 공개 API·팀 간 HTTP DTO·Flyway·로컬 시드·고정 모델·여섯 특성 산식·세 선별 임계값은 변경하지 않는다. Python은 업무 DB에 접속하지 않는다.

[API-023 계약](../../../ml/docs/api/API-023.md) · [이벤트 입력](../../../ml/docs/RISK_EVENT_PROJECTION.md) · [업무 규칙](../../../docs/business.md) · [1단계 기록](ML-RISK-RECHECK-STAGE1.md)

## 이번에 가능한 흐름

1. 합성 관측 이벤트 파일을 기존 `build_features`에 전달한다. 사건 발생 시각과 관측 가능 시각이 모두 D-1 09:00 이하인 관측만 기존 산식에 사용된다. 사후 truth·최종 예약 상태로 특징값을 만들지 않는다.
2. 로컬 projection 파일에 기존 API-023 요청, 대상 예약 코드 목록, 추적용 해시를 담는다. Spring은 해당 코드 집합이 현재 시연 DB 대상과 같은지 추가 검사한다. API-023 HTTP에는 기존 요청 5필드만 보낸다.
3. ML 원점수가 선별되면 위험 평가와 예약별 `jobs(PENDING)`를 한 트랜잭션으로 커밋한다. 미선별이면 위험 평가만 저장한다.
4. 작업을 짧은 트랜잭션에서 `RUNNING`으로 선점하고 커밋한 뒤 기존 공급사→호텔 HTTP·근거 검증을 실행한다. 검증된 근거만 숙박일 사실·Case·최초 업무를 만든다. 근거에는 기존 `job_id` FK를 함께 기록한다.
5. 현재 공통 경로에서는 성공 근거·숙박일·사건과 Job 완료를 같은 트랜잭션에 기록한다. 실행권을 잃은 응답은 함께 롤백한다. 회신 대기·실패·다음 재시도 시각은 별도 짧은 트랜잭션에 기록한다. 실패했다고 위험 점수를 0으로 바꾸거나 숙박일을 `UNAVAILABLE`로 만들지 않는다.

새 테이블·컬럼·job kind를 만들지 않는다. 기존 `jobs`의 `RECHECK`, 상태, 재시도 횟수, 기한, JSON 필드를 사용한다. 공개 HTTP 엔드포인트나 자동 실행 스케줄러도 추가하지 않는다.

## 입력의 의미와 한계

예제 `risk-events-cold-start.json`은 YB-001 예약 확정 관측 한 건만 가진 **수동으로 작성한 합성 관측 파일**이다. 자동 수집 데이터나 100호텔 시뮬레이터의 전체 실행 결과가 아니다. 같은 형식의 기존 시뮬레이터 호텔 shard도 입력할 수 있지만, 2025년 실험 shard를 2026년 시연 DB와 날짜·예약 ID가 같은 데이터인 것처럼 연결해서는 안 된다.

예제의 여섯 값은 `.05, 1, 0, 0, 0, false`다. `.05`는 과거 관측이 없을 때 기존 사전값이다. D-1 cutoff와 정확히 같은 시각의 확정은 대상 집합에는 들어가지만 기존 증가율의 반열린 시간 창에서는 제외된다. 고정 모델 원점수는 약 `.02019516`으로 A·B에서는 미선별, C에서는 선별된다. 높은 위험을 만들려고 과거 장애 이력을 꾸며 넣지 않았다.

개별 수동 projection의 해시는 추적용이며 원천을 인증하지 않는다. 후속 자동 배치는 공개 관측 사본의 바이트 해시를 검증하고 공통 Spring 집계 결과를 Python 기준과 대조한다. [현재 공통 경로](ML-RISK-COMMON-PATH.md)를 참고한다. 서명된 공급사 증빙, 실시간 DB 이벤트 집계, 전체 과거 상태 재구성을 구현한 것이 아니다. 현재 bookings의 상태·생성 시각·계약 값과 파일의 예측 시점 대상이 맞아야 하는 로컬 시연이다. 현재 상태가 달라졌다면 강제로 수를 맞추지 않고 중단한다.

기존 수동 `request-file`도 유지한다. `request-file`과 `projection-file` 중 정확히 하나만 지정한다. 현재 공통 저장소에서는 동일한 모델·대상·asOf·여섯 입력·저장 점수의 평가를 재사용한다. 수동 raw request는 자동 관측 집계를 거치는 입력과 구분한다.

## 작업·재시도 규칙

| 상황 | 처리 |
| --- | --- |
| 선별된 예약에 활성 RECHECK 없음 | `RISK-<최초 평가 ID>-<예약 ID>`를 작업 코드·외부 요청 ID로 고정 |
| 같은 평가·예약·고정 범위의 이 구현 소유 활성 작업 있음 | 기존 작업을 재사용. 다른 평가에 속한 활성 작업은 충돌로 처리하며 원점수·정책·원래 평가 FK를 덮어쓰지 않음 |
| 다른 흐름의 활성 작업 또는 다른 예약 범위 | 평가와 신규 작업 저장을 함께 롤백. 다른 팀의 작업을 가져오지 않음 |
| 202 회신 대기, 연결 오류·시간 초과·503 등 일시 오류 | `WAITING`, 실패 분류·다음 시각 저장. 근거 없는 Case 생성 없음 |
| 인증·요청 오류, 404, 형식 불일치, 고정 범위 변경, 본문 충돌 | `FAILED`, `manualRequired=true`. 임의 새 요청 ID로 자동 재시도하지 않음 |
| 성공 저장 중 예외·프로세스 중단·실행권 상실 | 현재 공통 경로는 근거·사건·Job 완료를 한 트랜잭션에서 커밋하거나 함께 롤백. 미완료 작업은 같은 ID·같은 범위로 복구 |
| 기한·시도 예산 소진 | `FAILED`와 수동 확인 필요 표시. 이미 원자적으로 커밋한 성공은 이후 기한 초과로 실패로 바꾸지 않음. 과거 버전의 불일치 행을 자동 수리하지는 않음 |
| ML 추론 자체 실패, 평가 저장 실패 | 0점 대체·재확인 작업 생성 없음. 아래 미구현 범위 참고 |

외부 요청에는 예약 코드·공급사 예약 ID·호텔·기간·인원·객실 수·숙박일의 고정 사본을 사용한다. 현재 예약 범위가 바뀌면 HTTP 전에 거부한다. 처리 중 범위가 바뀌면 기존 근거 적용 단계에서도 재검증한다. 동일 ID의 본문을 바꾸지 않는다.

개발용 내부 기본값은 총 **3회**, 재시도 간격 **30초**, RUNNING 임대 **60초**, 처리 기한 **15분**이다. 팀이 승인한 운영 SLA가 아니다. 작업마다 생성 당시 값을 저장한다. `attempt_count`는 **재시도 횟수**이므로 최초 실행은 0, 다음 실행은 1·2다. 비교용 2026년 `asOf`와 작업의 실제 처리 시계는 별개다.

자동 타이머는 없다. 최초 선별 직후 한 번 실행하고, 남은 작업은 아래 명령으로 한 번씩 처리한다. 한 실행에서 최대 100개까지만 허용한다. RUNNING 임대가 유효하거나 WAITING의 다음 시각 전이면 HTTP를 보내지 않는다. 동시 작업자는 짧은 선점과 claim token으로 오래된 완료 결과가 새 작업 상태를 덮지 못하게 한다. HTTP 지연으로 임대가 만료되면 중복 전송이 가능하므로 외부의 동일 ID 처리와 내부 근거 재사용이 필요하다. 정확히 한 번 발송을 보장하지 않는다.

## Windows 실행

기존 `CSRO`·`1122cy-risk-link`를 사용한다. 새 브랜치 생성, DB 초기화, `tools/` 포함, PR 생성은 이 절차에 없다. `.venv`·서버·Docker가 준비되어 있으면 재설치·기존 A/B/C 반복도 필요 없다.

### 이벤트에서 projection 생성: DB 변경 없음

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\ml
.\.venv\Scripts\python.exe -m travel_ml.risk_projection --source examples\risk-events-cold-start.json --hotel-id HOTEL-001 --target-check-in-date 2026-11-10 --output generated_risk_stage2\projection.json
if ($LASTEXITCODE -ne 0) { throw '이벤트 특징값 생성 실패' }
Get-Content generated_risk_stage2\projection.json -Encoding UTF8
```

이미 있는 출력은 덮어쓰지 않는다. 다시 생성해야 하면 다른 출력 파일명을 지정하고 Spring의 경로도 맞춘다. 출력은 `.gitignore`의 `generated*/` 규칙에 따라 Git에 넣지 않는다.

### F1 기본 평가: 동일한 평가 재사용 또는 한 행 추가

ML 서버(8002)를 기존 방법으로 실행한다. Spring 터미널에는 ML 서버와 같은 토큰을 설정한다. 아래 실행은 예제에서 미선별이므로 Mock HTTP가 필요 없다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\backend
$env:ML_RISK_SERVICE_TOKEN = Read-Host 'ML 서버와 같은 로컬 토큰 입력'
.\gradlew.bat bootRun --args="--spring.profiles.active=local --spring.main.web-application-type=none --risk.demo.enabled=true --risk.demo.policy=F1_BASELINE --risk.demo.projection-file=../ml/generated_risk_stage2/projection.json"
```

출력 접두사 `RISK_STAGE1_RESULT`는 호환성을 위해 유지한다. `RISK_PROJECTION_PROVENANCE`는 추적용 해시다. 이미 있던 Case·근거·숙박일을 초기화하지 않는다. C 정책을 선택하면 실제 재확인·근거 저장이 발생하므로 아래 Mock 호출 설정과 기존 검증 절차를 따른다.

### 남은 작업만 재개: ML 재평가 없음

Mock 서버(8001)를 기존 방법으로 실행하고, 별도 Spring 터미널에서 호출자 설정을 지정한다. 새 ML 평가를 실행해 실패 작업을 재시도하지 않는다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\backend
$env:BOOKING_RECONFIRMATION_MOCK_BASEURL = 'http://127.0.0.1:8001'
$env:BOOKING_RECONFIRMATION_MOCK_SERVICETOKEN = Read-Host 'Mock 서버와 같은 로컬 토큰 입력'
.\gradlew.bat bootRun --args="--spring.profiles.active=local --spring.main.web-application-type=none --risk.jobs.demo.enabled=true --risk.jobs.demo.limit=10"
```

`risk.demo.enabled=true`와 `risk.jobs.demo.enabled=true`를 동시에 사용하면 작업 전에 거부한다. 처리 결과가 `[]`이면 처리 시각이 된 이 구현 소유 작업이 없는 것이다. 이미 완료·영구 실패한 행을 강제로 재개하지 않는다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO
docker compose exec postgres psql -X -U yb -d yeohaengbom -P pager=off -c "SELECT job_code, status, attempt_count, next_attempt_at, finished_at, result_payload ->> 'manualRequired' AS manual_required FROM jobs WHERE kind='RECHECK' AND parameters ->> 'protocol'='risk-recheck-v1' ORDER BY id;"
```

## 아직 완료하지 않은 범위

- ML 추론 실패 **자체**의 영속 기록·재시도: 기존 `risk_assessments`는 성공 점수·모델 버전이 필수이고, `jobs`는 예약별 RECHECK/QUOTE_SEARCH만 허용한다. 실패를 0점이나 가짜 RECHECK로 저장하지 않는다. 별도 저장 설계는 DB 계약 변경 승인을 받아야 한다.
- 실시간 관측 수집·업무 이벤트 원장·온라인 특징값 저장소·주기 스케줄링·운영 배포·모니터링·전체 사건 후속 흐름.
- 단일 합성 사례로 Recall, 현실 장애율, 확률 보정, 실제 고객 피해 감소를 증명하는 일. 기존 2025년 학습·평가·모델은 이번에 다시 만들지 않는다.

## 핵심 검증

새 코드의 집중 테스트는 cutoff 누수 방지, projection 대상 일치, 영속 작업·재시도·완료 기록 경합, 고정 HTTP 범위, 근거 `job_id` 연결을 검증한다. 원시 실행 로그는 Git에 넣지 않는다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\ml
.\.venv\Scripts\python.exe -m unittest discover -s tests -p test_risk_projection.py
Set-Location ..\backend
Remove-Item Env:RISK_LINK_TEST -ErrorAction SilentlyContinue
Remove-Item Env:RISK_JOBS_TEST -ErrorAction SilentlyContinue
.\gradlew.bat test --tests "com.csro.backend.risk.RiskEventProjectionTest" --tests "com.csro.backend.risk.RiskJobsDemoRunnerTest" --tests "com.csro.backend.risk.RiskRecheckJobsTest" --tests "com.csro.backend.reconfirmation.ReconfirmationScopeTest" --tests "com.csro.backend.risk.RiskRecheckServiceTest" --tests "com.csro.backend.risk.RiskDemoRunnerTest"
```

쓰기 통합 검사는 `RiskJobsIntegrationTest`만 별도로 실행한다. `RISK_JOBS_TEST=true`, URL `jdbc:postgresql://127.0.0.1:25435/yeohaengbom_risk_stage2_test`, user `yb`, 전용 비밀번호가 모두 필요하다. 기본 업무 DB·다른 URL은 Spring/Flyway 전 검증에서 차단한다. 기존 업무 DB를 테스트용으로 지정하거나 지우지 않는다.

환경변수 이름은 각각 `RISK_JOBS_TEST_URL`, `RISK_JOBS_TEST_USER`, `RISK_JOBS_TEST_PASSWORD`다. 전용 DB가 준비된 경우에만 `./gradlew test --tests "com.csro.backend.risk.RiskJobsIntegrationTest"`를 실행한다. 이 11개 시나리오는 기존 CI의 두 PostgreSQL 검사 목록에 자동 추가되지 않는다. 향후 CI 확대는 공통 설정 담당자와 별도로 정한다. 새 단위 검사 클래스는 `ciUnitTest`의 일반 검색 대상이고, Python `test_risk_projection.py`는 기존 `test_risk*.py` 패턴에 들어간다.

후속 [자동 점검 배치](ML-RISK-AUTOMATION.md)는 이 경로를 재사용한다. 현재 공통 경로에서는 수동·배치 평가 저장을 통일하고, 자동 배치에 관측 사본 검증·집계 대조·명시적 로컬 반복 실행을 제공한다. 초기 단계의 수동 실행당 신규 평가 동작은 공통 저장소의 재사용 동작으로 대체됐다.
