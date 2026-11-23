# 공통 위험 평가 경로와 자동 배치 연결

합성 관측을 읽는 로컬 자동화를 공통 ML 평가·저장 경로에 연결한다. 비교 기준은 `1122cy-risk-link`의 `ff05840`과 PR #14의 `2f26f6b`다. 이 구현은 해당 PR의 병합이나 Windows 실행 완료를 뜻하지 않는다.

## 실행 경로

1. 기존 Python launcher가 catalog의 파일을 읽고 동결된 `prepare_projection`으로 비교 기준을 계산한다.
2. 공개 관측만 회차별 파일에 복사하고, 바이트 SHA-256과 기준 projection을 로컬 manifest v2에 연결한다. 최종 예약 목록·호텔 내부 속성·비공개 이벤트·숨은 truth는 공급하지 않는다.
3. 배치를 명시적으로 켰을 때만 `RiskFileObservations`가 등록된다. 파일 크기·해시·JSON 형식·호텔·자료형·시각을 검증하고 원천 관측을 공통 `RiskObservations` 인터페이스에 제공한다. 다른 공급자와 함께 등록돼 어느 것을 사용할지 모호하면 시작을 거부한다.
4. `RiskAssessmentService.prepare`와 공통 `RiskProjection`이 발생 시각과 관측 가능 시각이 모두 D-1 09:00 KST 이하인 이력으로 여섯 값과 예약 집합을 계산한다.
5. `RiskRecheckService`가 Spring 결과와 Python 기준의 호텔·날짜·시각·버전·여섯 값·예약 집합을 대조한다. 특징값의 부동소수점 비교에만 절대 오차 `1e-12`를 허용한다. 불일치는 ML HTTP 전에 거부한다.
6. 추론은 공통 `RiskAssessmentService.predict → RiskMlClient`, 저장은 `RiskAssessmentStore` 한 구현으로 처리한다. 선별은 새 응답의 원점수를 사용한다. 저장 점수의 반올림이나 특징값 비교 허용 오차를 정책 임계값에 적용하지 않는다.
7. 평가와 선택된 RECHECK Job을 같은 트랜잭션에서 저장한 뒤 외부 재확인을 수행한다. 성공 근거·숙박일·사건과 Job 완료도 한 트랜잭션에서 커밋한다. 실행권을 잃은 응답은 함께 롤백한다.

외부 HTTP 동안 DB 행 잠금을 유지하지 않는다. Python은 업무 DB에 접속하거나 상태를 수정하지 않는다. ML·Mock HTTP 계약, 고객 API, Flyway V1·시드, 동결된 Python 특징값 산식·설정·기준 모델·세 정책 임계값은 유지한다.

## 이전 실행과 달라진 점

- 자동 배치는 파일의 특징값을 곧바로 추론에 사용하지 않고 공통 Spring 집계 결과를 사용한다. Python 계산은 비교 기준이다.
- 수동 raw-request·projection 진입점은 명시적 개발 도구로 유지한다. 이 입력은 자동 관측 집계를 거쳤다고 설명하지 않는다. 추론·저장은 자동 배치와 같은 구현을 사용한다.
- 수동·배치 모두 공통 저장소에서 동일 모델·대상·asOf·입력·저장 점수의 평가를 재사용한다. 기존 수동 입력마다 신규 평가를 만들던 동작은 대체됐다.
- `RiskMlClient`의 별도 Spring 컴포넌트 등록을 제거했다. 공통 서비스가 엄격한 응답 검증·타임아웃·오류 분류를 재사용한다. 두 추론 결과를 차례로 저장하지 않는다.
- 공통 서비스의 인증 실패는 원문을 보존하지 않는 `RiskMlException`의 `AUTHENTICATION`과 HTTP 401로 분류한다. 관련 공통 라이브 테스트도 이 분류를 확인한다.
- PR #14의 원자적 Job 성공 처리와 실행권 검증을 보존했다. 과거 버전에서 만들어진 불일치 행을 자동 수리하지는 않는다.

## 설정과 호환

Python ML 서버는 `ML_SERVICE_TOKEN`, 공통 Spring 호출자는 `ML_RISK_SERVICE_TOKEN`을 사용하며 실제 값은 같아야 한다. launcher는 후자가 비어 있으면 전자를 자식 프로세스 환경에만 복사한다. 둘 다 설정됐는데 값이 다르면 `ML_AUTH_AMBIGUOUS`로 중단한다.

공통 Spring 주소는 `ML_RISK_BASE_URL`이고 기본값은 `http://127.0.0.1:8002`다. 기존 launcher 환경의 `RISK_ML_BASEURL`은 호환하며, 두 주소가 상충하면 중단한다. 직접 Spring을 실행할 때는 공통 변수명을 지정한다. 모델 버전은 기존 동결 버전만 허용한다.

catalog는 기존 `risk-observation-sources-1` 그대로다. 내부 회차 산출물만 `risk-automation-batch-2`로 변경했다. manifest는 `format`, `targetCheckInDate`, `projections`, `observations`를 가지며 관측 항목은 `{hotelId, path, sha256}`다. 이전 회차의 v1 manifest는 새 launcher로 다시 준비한다. 공개·팀 간 HTTP DTO 변경은 없다.

누락·오염된 호텔 관측이나 집계 불일치는 해당 호텔의 실패이며, 다른 호텔과 처리 시각이 된 기존 작업은 계속 처리한다. 구조가 모호한 manifest나 대상 수 상한 초과는 전체 실행 오류다. ML 실패를 0점이나 숙박일 제공 불가로 바꾸지 않는다.

## Windows 집중 확인

기존 `CSRO`, 브랜치, `.env`, DB 볼륨을 유지한다. 의존성 추가나 모델 재학습은 없다. 아래 검사는 DB 없는 집중 검사이며 Python 가상환경은 기존 것을 사용한다.

```powershell
& {
    Set-Location C:\Users\cy102\Downloads\CSRO\ml
    .\.venv\Scripts\python.exe -m unittest discover -s tests -p test_risk_automation.py
    if ($LASTEXITCODE -ne 0) { throw 'Python 공통 경로 검사 실패' }

    Set-Location ..\backend
    .\gradlew.bat test --tests "com.csro.backend.risk.RiskFileObservationsTest" --tests "com.csro.backend.risk.RiskBatchManifestTest" --tests "com.csro.backend.risk.RiskBatchRunnerTest" --tests "com.csro.backend.risk.RiskBatchDatabaseGuardTest" --tests "com.csro.backend.risk.RiskRecheckServiceTest" --tests "com.csro.backend.risk.RiskMlClientTest" --tests "com.csro.backend.risk.RiskProjectionTest" --tests "com.csro.backend.risk.RiskRecheckJobsTest" --tests "com.csro.backend.reconfirmation.ReconfirmationScopeTest"
    if ($LASTEXITCODE -ne 0) { throw 'Spring 공통 경로 검사 실패' }

    .\gradlew.bat bootJar
    if ($LASTEXITCODE -ne 0) { throw 'Spring JAR 빌드 실패' }
}
```

이후 기존 ML·Mock·DB를 준비한 같은 PowerShell에서 [자동화 실행 절차](ML-RISK-AUTOMATION.md)를 사용한다. 기본 catalog의 cold-start 입력과 F1 정책에서는 미선별이 정상이다. 기존 평가·사건을 지우거나 높은 위험 점수를 만들기 위해 입력을 조정하지 않는다. 같은 입력 반복에서 평가·완료 작업의 재사용 여부를 확인한다.

## 검증 범위와 남은 작업

재현 가능한 검사 코드는 공급자·집계 대조·공통 저장소·원자적 Job 완료에 포함한다. 쓰기 통합 검사는 전용 DB guard를 통과한 격리 PostgreSQL에서만 실행하며 기본 업무 DB를 테스트에 지정하지 않는다. 원시 로그·자격증명·환경 덤프는 Git에 남기지 않는다.

이 변경에서 클라우드 Linux·JDK 21로 실행한 범위:

- Python `test_risk_automation.py` 15개, 위 Spring 집중 검사 110개 통과. 실패·스킵 0개.
- 전용 PostgreSQL에서 `RiskBatchAssessmentIntegrationTest` 7개, `RiskBatchServiceIntegrationTest` 6개, `RiskJobsIntegrationTest` 11개 통과. 총 24개, 실패·스킵 0개. `bootJar` 빌드 성공.
- 새 격리 DB와 실제 API-023·Mock API-026/027로 F1 1회, C 정책 2회 실행 성공. 평가 1·완료 Job 1·연결 근거 2·사건 1·업무 1을 유지했다. 실제 HTTP는 ML 3회, 공급사 1회, 호텔 1회였고 완료 Job 반복에서 Mock 재발송은 없었다.
- 같은 DB의 조회 전용 Spring에서 API-003/004로 사건·원예약 CONFIRMED·다음 업무를 확인했다. 브라우저 UI 검증은 수행하지 않았다.
- PR #14 전체 소스와 이 브랜치의 변경을 합친 별도 클라우드 소스에서 `compileTestJava` 성공. Git 병합 성공이나 최신 main으로의 병합 완료를 뜻하지 않는다.
- 기준 모델 8개 파일, V1·시드, Python 특징값·projection·학습 코드, v0.2 설정·스키마, 정책 임계값의 원래 바이트를 확인했다. Job과 근거 성공 처리 파일은 PR #14의 `2f26f6b`와 동일하다.

사용자 Windows의 이번 변경 적용·검사는 아직 별도 확인이 필요하다. 기존 자동화 버전의 Windows 성공 기록을 이번 변경의 실행 결과로 대체하지 않는다. 영향 밖의 전체 테스트·데이터 재생성·모델 재학습은 실행하지 않았다.

합성 파일의 해시는 전송 사본의 무결성을 확인한다. 파일이 실제 호텔의 완전한 이력인지, 실제 장애율이나 보정된 실패 확률이 맞는지는 증명하지 않는다. 현재 예제는 수동으로 만든 YB-001 합성 관측 한 건이다.

실제 운영 관측 수집, 운영 임계값 승인, 상시 서비스·OS 스케줄러·배포, ML 추론 실패 자체의 DB 실행 원장은 후속 범위다. 상담 브라우저 화면 연결 확인과 B팀의 대체 숙소·고객 제안·예약·후속 업무도 별도 완료 기준이다.
