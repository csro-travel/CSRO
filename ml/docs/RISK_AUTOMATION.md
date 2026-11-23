# 로컬 위험 점검 자동 실행

`travel_ml.risk_automation`은 **명시적으로 실행하는 개발용 launcher**다. 기존 합성 관측 파일에서 특징값을 준비하고 Spring batch JAR를 한 번씩 실행한다. Python HTTP 서비스·업무 DB 소유권·기존 API-023 계약을 바꾸지 않는다. OS 작업 스케줄러나 운영 서비스를 설치하지 않는다.

호출 순서는 `관측 catalog → 기존 prepare_projection → 로컬 batch manifest → Spring RiskBatchService → 기존 API-023·선별·jobs`다. Python은 DB에 접속하지 않고 HTTP·학습·모델 로딩도 수행하지 않는다. main의 별도 `RiskAssessmentService.assess()`를 먼저 호출하지 않는다. Spring 실행·저장·재시도의 상세 경계는 [백엔드 자동화 문서](../../backend/docs/api/ML-RISK-AUTOMATION.md)를 따른다.

## 데이터와 시각

기존 `features.py`, v0.2 설정·스키마·고정 모델을 유지한다. 각 cycle마다 원천 이벤트 파일을 다시 읽고 기존 `risk_projection.prepare_projection`으로 정확한 여섯 특성·예측 시점 예약 집합을 계산한다. 미래 사건·늦게 알려진 관측·private truth를 특성에 넣지 않는다. 실시간 수집 어댑터를 구현한 것은 아니다.

`--target-check-in-date`는 필수다. 현재 예제는 **2026-11-10 시연 날짜**이며 특징값 cutoff는 2026-11-09 09:00 KST다. PC 시계를 바꾸거나 2025년 학습용 이벤트를 날짜·ID 수정 없이 이 예약에 섞지 않는다. 작업의 retry·deadline 시계는 Spring의 실제 처리 시계다. 반복 간격은 운영 SLA나 매일 D-1 실행 시각으로 합의된 규칙이 아니다.

기본 catalog `config/risk_automation_sources_v1.json`은 기존 `HOTEL-001` cold-start 파일만 가리킨다. `.05,1,0,0,0,false`는 관측 이력이 없을 때의 기존 산식 결과이며 높은 위험을 만들기 위한 수동 특성 입력이 아니다. F1 기본 정책에서는 미선별이다.

catalog는 정확히 아래 형태이고 같은 호텔을 두 번 지정할 수 없다. `path`는 **catalog 파일이 있는 디렉터리 기준**으로 해석한다. 다른 관측 파일을 추가할 때 그 호텔·예약·날짜가 Spring 시연 DB의 대상과 맞아야 한다.

```json
{
  "format": "risk-observation-sources-1",
  "sources": [
    {"hotelId": "HOTEL-001", "path": "../examples/risk-events-cold-start.json"}
  ]
}
```

## Windows에서 한 번 실행

ML·Mock 서버와 기존 Docker DB를 기존 방법으로 준비한다. 새 데이터 생성·모델 재학습은 필요 없다. 먼저 변경된 Spring JAR를 빌드한다.

```powershell
Set-Location C:\Users\cy102\Downloads\CSRO\backend
.\gradlew.bat bootJar
if ($LASTEXITCODE -ne 0) { throw 'Spring JAR 빌드 실패' }

Set-Location ..\ml
$env:ML_SERVICE_TOKEN = Read-Host 'ML 서버와 같은 로컬 토큰 입력'
$env:BOOKING_RECONFIRMATION_MOCK_BASEURL = 'http://127.0.0.1:8001'
$env:BOOKING_RECONFIRMATION_MOCK_SERVICETOKEN = Read-Host 'Mock 서버와 같은 로컬 토큰 입력'
.\.venv\Scripts\python.exe -m travel_ml.risk_automation --sources config\risk_automation_sources_v1.json --target-check-in-date 2026-11-10 --policy F1_BASELINE
```

토큰을 파일·Git·로그·공유 메시지에 쓰지 않는다. launcher는 `ML_SERVICE_TOKEN`과 Mock 호출 토큰이 비어 있으면 Java 실행 전에 중단한다. Mock 토큰은 `BOOKING_RECONFIRMATION_MOCK_SERVICETOKEN` 또는 `BOOKING_RECONFIRMATION_MOCK_SERVICE_TOKEN`을 허용하며 두 값이 다르면 중단한다. 기존 Spring·서비스에서 실제로 사용하는 값과 일치시켜야 한다.

JAR는 저장소의 `backend/build/libs`에서 `*-plain.jar`를 제외하고 정확히 하나를 찾는다. 파일이 없거나 여러 개면 자동 선택하지 않는다. 필요한 경우 `--jar <경로>`를 명시한다. `--java <실행파일>`로 JDK 21 실행 파일을 지정할 수 있다. Java는 언제나 `backend`를 작업 디렉터리로 실행하여 기존 local `.env` 경로를 유지한다. Spring은 `local` 프로필에서 업무 DB를 **실제로 쓰는 batch**다. 기존 `local-demo` 조회 서버와 다르다.

기본 `F1_BASELINE`과 명시적 `RECALL_50_F1`·`MAX_OBSERVED_RECALL`을 지원한다. 정책을 바꾸어 선별된 경우 작업과 근거가 생길 수 있다. 같은 cycle에서 세 정책을 자동으로 모두 적용하지 않는다.

## 반복 실행과 중단

기본은 한 cycle이다. 예를 들어 완료 후 30초 간격으로 세 번 실행하려면 다음을 사용한다.

```powershell
.\.venv\Scripts\python.exe -m travel_ml.risk_automation --target-check-in-date 2026-11-10 --cycles 3 --interval-seconds 30
```

`--cycles 0`은 Ctrl+C까지 계속 실행하는 명시적 선택이다. 서로 겹치게 Java를 실행하지 않으며 **이전 cycle 종료 후** 간격만큼 기다린다. 벽시계 cron·분산 스케줄러·운영 상시 서비스는 아니다. 변경된 이벤트 파일은 다음 cycle에서 다시 읽지만 같은 cutoff 뒤에 알려진 관측은 여전히 제외된다.

cycle마다 ML 읽기 전용 추론을 다시 호출할 수 있다. Spring batch는 동일한 평가 입력의 저장 행을 재사용하며, selected RECHECK는 고정된 기존 요청 ID로 처리한다. HTTP가 정확히 한 번만 전송된다는 보장은 아니다. Ctrl+C·timeout으로 Java가 멈춘 뒤에도 커밋된 Spring 작업은 DB에 남고, 다음 실행에서 기존 job의 lease·기한·시도 예산에 따라 재개된다. 실패한 작업을 새 요청 ID로 자동 교체하지 않는다.

## 결과와 실패

`RISK_AUTOMATION_CYCLE` JSON에는 cycle 번호·정책·호텔별 준비 오류와 Spring 결과 요약을 출력한다. 호텔별 평가 ID·원점수·선별 여부, `recheckStates`·`recheckErrors`, 재개 작업 상태별 건수를 포함한다. 자식 Spring의 원시 로그·응답 원문·환경변수는 출력하지 않는다.

- catalog 형식·중복 호텔·인증·JAR 문제가 있으면 실행을 중단한다.
- 개별 원천 파일이 잘못됐으면 해당 호텔을 `PREPARATION_FAILED`로 표시한다. 가짜 특징값을 만들지 않으며, 나머지 projection과 기존 due-job 처리를 위해 Spring을 계속 실행한다. 해당 호텔이 DB상 대상이면 Spring의 누락 projection 오류도 확인한다.
- 유효한 관측 이력에 그 날짜의 확정 대상 집합이 없으면 기존 생성기의 정확한 `NOT_ELIGIBLE` 결과만 정상 생략한다. projection이나 가짜 0점은 만들지 않는다. Spring에도 대상이 없으면 기존 due-job 복구만 수행할 수 있다. 반대로 DB에는 대상이 있는데 관측 집합이 비었다면 Spring은 `MISSING_PROJECTION`으로 실패 처리하므로 데이터 불일치를 숨기지 않는다.
- Java 비정상 종료·시간 초과·잘못된 결과는 안정적인 오류 코드만 표시한다. 다음 cycle을 지정했다면 다시 시도할 수 있다. 결과 날짜가 요청과 달라도 성공으로 보고하지 않는다.
- 명령의 종료 코드는 **마지막 cycle**이 성공하면 0, 실패하면 2, Ctrl+C는 130이다. 이전 일시 오류가 나중에 복구되면 마지막 성공을 반환한다. 중간 오류는 앞선 cycle 요약에 남는다. WAITING은 확인 완료가 아니며, terminal 실패는 오류·수동 확인 필요로 남는다.

생성 파일은 기본 `ml/generated_risk_automation/cycle-<UUID>/`에 cycle별로 남는다. 기존 파일을 덮어쓰거나 자동 삭제하지 않는다. `generated*/`는 Git에서 제외된다. `--cycles 0`을 오래 실행하면 출력 폴더가 계속 늘어나므로 저장 공간 관리는 실행자가 해야 한다. 이것은 운영 보관·감사 시스템이 아니다.

개발용 입력 상한은 catalog 1MiB·100호텔, 원천 한 파일 64MiB, Spring 원시 출력 확인 16MiB다. Java 실행 timeout 기본값은 300초이고 `--timeout-seconds 5..3600`으로 지정한다. 큰 데이터·많은 대상의 처리 시간 보장은 아니며 timeout은 이미 커밋된 DB 작업을 롤백하지 않는다. 지연·실패를 위험도 0으로 치환하지 않는다. ML 추론 실패 자체의 DB 실행 원장은 이번에 추가하지 않았다.

## 집중 검사

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -p test_risk_automation.py
```

새 검사만 실행하며 전체 데이터 생성·학습·기존 HTTP 시연을 반복하지 않는다. 이 launcher의 프로세스 제어·파일 준비 검사는 실제 Windows 전체 통합 실행·서버 운영 검증과 구분한다.
