# 합성 관측 기반 자동 점검 배치

`1122cy-risk-link`의 로컬 실행기를 PR #14의 공통 평가·저장·재확인 경로에 연결한다. 한 명령이 합성 관측 파일과 Python 기준 계산을 준비하고, Spring이 같은 관측에서 여섯 특징값을 계산해 대조한 뒤 배치를 실행한다. 명시적으로 반복을 켜면 매 회차 최신 파일을 다시 읽으며, 이전 Spring 실행이 끝난 뒤 다음 회차를 시작한다. [공통 경로와 호환 범위](ML-RISK-COMMON-PATH.md)

이 기능은 **로컬 시연용 자동 반복 실행**이다. 운영 서버의 cron·Windows 작업 스케줄러·서비스 설치나 배포를 수행하지 않는다. 기존 서버를 일반적으로 시작하면 배치를 자동 실행하지 않는다. 새 테이블·HTTP API·공급사 계약·모델·특성 산식·Flyway를 추가하거나 변경하지 않는다.

[ML 실행기](../../../ml/docs/RISK_AUTOMATION.md) · [기존 2단계 규칙](ML-RISK-RECHECK-STAGE2.md) · [API-023 계약](../../../ml/docs/api/API-023.md)

## 실행 흐름과 책임

1. 로컬 `travel_ml.risk_automation`이 catalog에 지정한 호텔 관측 파일을 읽는다. 기존 Python `prepare_projection`/`build_features`로 비교 기준을 만들고, 공개 관측만 포함한 회차별 파일과 바이트 SHA-256을 준비한다. Python의 DB 접속·HTTP 추론·업무 상태 변경·재학습은 없다.
2. Spring `RiskBatchService`가 명시한 체크인 날짜와 D-1 09:00 KST에 해당하는 현재 합성 예약 호텔을 DB에서 찾는다. 관측 파일의 대상 예약 코드와 현재 업무 DB 대상 집합이 정확히 같아야 한다.
3. 명시적으로 켠 `RiskFileObservations`가 해당 회차 파일의 바이트 해시와 형식을 검증한다. `RiskAssessmentService.prepare`와 공통 `RiskProjection`이 관측 시점에 맞게 계산한다. Python 기준의 여섯 값·평가 시각·호텔·날짜·버전·예약 집합과 다르면 ML 호출 전에 중단한다. 자동화에서는 파일의 특징값을 그대로 추론에 사용하지 않는다.
4. `RiskRecheckService`가 공통 `RiskAssessmentService.predict`와 엄격한 `RiskMlClient`를 거쳐 추론하고, `RiskAssessmentStore`로 평가를 저장한다. 선별된 jobs도 같은 트랜잭션에서 등록한다. 별도로 `RiskAssessmentService.assess`를 먼저 호출해 평가를 두 번 저장하지 않는다.
5. 호텔별 입력·ML·DB 오류는 안전한 코드로 보고하고 다른 호텔을 계속 처리한다. 이어서 기존 소유 RECHECK 중 처리 시각이 된 작업을 제한된 수만큼 재개한다. 다른 팀의 작업은 처리하지 않는다.

catalog의 구조·중복 호텔·배치 manifest의 모호한 형식·대상 수 제한 초과는 전체 실행 오류다. 개별 원천 파일 오류는 준비 실패로 남기고 다른 파일을 처리한다. DB에 대상이 있는데 projection이 없으면 해당 호텔은 실패하며 0점으로 대신 저장하지 않는다.

관측 원천에서 해당 날짜의 확정 대상이 없는 정상 사례는 projection 생성을 생략한다. Spring은 계속 DB 대상을 조회하고 기존 대기 작업을 재개한다. DB에는 대상이 있는데 원천에 없다면 `MISSING_PROJECTION`으로 실패하므로 이 생략이 데이터 누락을 정상 점수로 바꾸지는 않는다.

DB 대상은 `SIMULATED` 호텔의 현재 CONFIRMED 예약 중 체크인 날짜가 같고 생성 시각이 cutoff 이하인 집합이다. 다른 공급사 예약을 몰래 빼고 대상 수를 맞추지 않는다. 현재 예약 상태로 완전한 과거 이력을 재구성한 것은 아니며, 실제 운영 관측 수집 어댑터도 아니다.

## 반복 실행·재시도

수동·배치 모두 공통 저장소를 사용한다. 호텔 행 잠금과 대상 재검증 후 호텔·날짜·asOf·모델·특성 버전·정확한 여섯 특징값·저장 점수가 같은 평가 행을 재사용한다. 없으면 새 평가를 저장하며, 선별 jobs 등록과 같은 트랜잭션에서 처리한다. 과거 수동 실행마다 새 평가를 만들던 동작은 공통 경로로 통일됐다.

ML HTTP는 매 회차 실행한다. DB의 점수는 반올림되어 있으므로 이를 모델 원점수로 대신 사용하지 않는다. 선별은 새 응답 원점수로 수행하고 HTTP 중 DB 행 잠금을 보유하지 않는다. 따라서 이 기능은 ML 호출 캐시가 아니다.

같은 평가·예약의 작업은 기존 `RISK-<평가 ID>-<예약 ID>`를 재사용한다. 완료·영구 실패 작업을 새 요청번호로 자동 재발송하지 않는다. WAITING/RUNNING은 기존 다음 시각·임대·예산에 따라 재개한다. 이전 수동 실행에서 만든 평가·작업을 재사용할 수 있고, 기존 작업에는 최초 실행의 정책·원점수·범위 정보가 남는다. 평가 행 자체에 현재 정책이나 대상 예약 코드가 새로 저장됐다고 설명하지 않는다.

PR #14의 성공 처리도 유지한다. 검증된 근거·숙박일·사건과 Job 완료는 같은 트랜잭션에서 커밋한다. 성공 저장 중 예외나 실행권 상실이면 함께 롤백하므로 새 실행에서 근거만 커밋되고 Job이 미완료로 남는 구간을 만들지 않는다. 이 보완은 과거 버전으로 저장된 불일치 행을 자동 수리하는 기능은 아니다.

현재 정책이 F1 미선별이어도 **이전에 다른 정책으로 선별한 대기 작업**은 별도 작업 복구 단계에서 처리될 수 있다. 그 작업의 검증된 호텔 근거가 사건을 만들 수 있다. 이번 F1 점수가 해당 사건을 직접 만들었다고 설명하지 않는다.

## Windows 준비

기존 CSRO 폴더·브랜치·`.env`·DB 볼륨을 유지한다. 쓰기 배치이므로 조회 전용 `local-demo` 서버와 목적을 구분한다. 같은 DB를 읽는 상담 서버는 이 배치를 대신 실행하지 않는다. ML 서버 8002와 Mock 서버 8001을 기존 방법으로 실행한다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO
docker compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'DB 준비 실패' }
Set-Location .\backend
.\gradlew.bat bootJar
if ($LASTEXITCODE -ne 0) { throw '자동화 코드 JAR 빌드 실패' }
```

배치 PowerShell에서 ML·Mock 서버와 같은 로컬 토큰을 지정한다. 토큰을 출력·커밋하지 않는다. Mock 호출자 설정은 `MOCK_SERVICE_TOKEN`과 별도다.

Python 서버는 `ML_SERVICE_TOKEN`, 공통 Spring 호출자는 `ML_RISK_SERVICE_TOKEN`을 사용한다. launcher는 후자가 없으면 전자의 값을 자식 프로세스에만 전달한다. 두 값이 모두 있는데 다르면 `ML_AUTH_AMBIGUOUS`로 중단한다. 주소는 `ML_RISK_BASE_URL`을 사용하고 기존 `RISK_ML_BASEURL`은 일치하는 경우에만 호환한다. 직접 Spring을 실행할 때는 공통 변수명을 지정한다.

```powershell
$env:ML_SERVICE_TOKEN = Read-Host 'ML 서버와 같은 로컬 토큰 입력'
$env:BOOKING_RECONFIRMATION_MOCK_BASEURL = 'http://127.0.0.1:8001'
$env:BOOKING_RECONFIRMATION_MOCK_SERVICETOKEN = Read-Host 'Mock 서버와 같은 로컬 토큰 입력'
```

## 첫 실행: 기존 F1 정책

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\ml
.\.venv\Scripts\python.exe -m travel_ml.risk_automation --target-check-in-date 2026-11-10
if ($LASTEXITCODE -ne 0) { throw '자동 점검 결과에 실패가 있음: 출력 코드 확인' }
```

기본 catalog는 `ml/config/risk_automation_sources_v1.json`이다. YB-001 cold-start 관측 한 건을 사용하므로 특징값은 `.05,1,0,0,0,false`, 원점수는 약 `.02019516`, 기본 F1은 미선별이다. 높은 점수나 새 Case가 생겨야 성공하는 검사가 아니다. 기존 DB에 같은 평가가 있으면 첫 배치부터 `REUSED`일 수 있다.

출력 `RISK_AUTOMATION_CYCLE`의 `successful`, 호텔별 `state`·`selected`·`recheckStates`·`recheckErrors`, 준비 실패와 기존 작업 복구 결과를 확인한다. `ASSESSED`는 평가 신규 저장, `REUSED`는 기존 평가 채택, `FAILED`는 해당 호텔 처리 실패다. 연결·원문·자격증명·예외 전문은 출력하지 않는다. 개별 실패 전 이미 성공한 평가·작업은 커밋될 수 있다.

`successful=true`는 이번 회차가 정상 처리됐다는 뜻이다. 재확인이 `PENDING`이면 아직 회신·재시도를 기다리는 작업이며 모두 완료됐다는 뜻은 아니다. 과거 영구 실패 작업이 모두 해결됐다는 의미도 아니다.

## 두 회차 또는 지속 반복

```powershell
# 매 회차가 끝난 뒤 30초 기다리는 두 회차. 같은 평가·완료 작업의 재사용 확인.
.\.venv\Scripts\python.exe -m travel_ml.risk_automation --target-check-in-date 2026-11-10 --cycles 2 --interval-seconds 30

# 필요할 때만 명시적으로 지속 반복. Ctrl+C로 중단.
.\.venv\Scripts\python.exe -m travel_ml.risk_automation --target-check-in-date 2026-11-10 --cycles 0 --interval-seconds 30
```

이는 고정 시각 cron이 아닌 완료 후 간격 반복이다. 대상 날짜와 cutoff는 시연용 값으로 고정하며 PC 시계를 바꾸지 않는다. 실제 날짜 기준으로 11/10 과거 예약이 자동 선택된다고 가정하지 않는다. 기본 Spring 한 회차 시간 제한은 300초이며 중단 시 이미 저장된 작업은 기존 ID로 복구한다. 마지막 회차 성공은 종료코드 0, 실패는 2, Ctrl+C는 130이다.

원래의 세 정책을 모두 유지한다. 선별 경로가 필요하면 기존 정책을 명시한다. 같은 관측과 C 정책의 별도 시연은 `--policy MAX_OBSERVED_RECALL`이며 실험의 Recall 100%나 실제 고객 실패 확률을 보장하지 않는다. 입력을 수정해 선별을 강제하지 않는다.

## Spring 직접 진입점과 검증

로컬 manifest는 `risk-automation-batch-2`이며 `format`, `targetCheckInDate`, `projections`, `observations`만 포함한다. `observations`의 각 항목은 `hotelId`, `path`, `sha256`이다. HTTP DTO가 아니며 이전 v1 산출물은 새 launcher로 다시 준비한다. `risk.batch.enabled=true`, `risk.batch.manifest-file`, `risk.batch.policy`, `risk.batch.max-targets`(기본100), `risk.batch.job-limit`(기본10)을 사용하는 비웹 일회성 runner다. 기존 평가 runner·jobs-only runner와 동시에 켜면 업무 처리 전에 거부한다. 작업 재개만 필요하면 기존 jobs-only runner를 사용한다.

DB 없는 집중 검사:

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\ml
.\.venv\Scripts\python.exe -m unittest discover -s tests -p test_risk_automation.py
Set-Location ..\backend
Remove-Item Env:RISK_BATCH_TEST -ErrorAction SilentlyContinue
.\gradlew.bat test --tests "com.csro.backend.risk.RiskBatchManifestTest" --tests "com.csro.backend.risk.RiskBatchRunnerTest" --tests "com.csro.backend.risk.RiskBatchDatabaseGuardTest" --tests "com.csro.backend.risk.RiskRecheckServiceTest"
```

쓰기 검사는 `RiskBatchAssessmentIntegrationTest`와 `RiskBatchServiceIntegrationTest`다. Spring/Flyway 전 guard에서 `RISK_BATCH_TEST=true`, `RISK_BATCH_TEST_DB_URL=jdbc:postgresql://127.0.0.1:25436/yeohaengbom_risk_batch_test`, `RISK_BATCH_TEST_DB_USER=yb`, 별도 비밀번호 `RISK_BATCH_TEST_DB_PASSWORD`를 요구한다. 기존 업무 DB를 쓰기 테스트로 지정하거나 초기화하지 않는다.

## 완료 범위 밖

운영 관측 수집·상시 운영 서비스·배포·공용 서버 인증, ML 추론 실패 자체의 DB 실행 원장은 구현하지 않았다. ML 실패는 회차 결과로 보고하고 다음 회차에서 다시 시도할 수 있다. 특징값·모델·훈련 데이터·임계값을 새로 만들지 않았으며 합성 시연을 현실 성능 근거로 사용하지 않는다. 생성 파일은 `generated_risk_automation/`에 누적되며 Git에서 제외된다. 전체 프로젝트 DB를 자동으로 채우는 도구가 아니다.
