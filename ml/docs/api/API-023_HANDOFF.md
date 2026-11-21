# API-023 Windows 실행·팀 인계

저장한 LightGBM 기준 모델을 **내부 HTTP API**로 호출하는 절차다. 이미 검증한 CLI와 같은 `risk-features-0.2` 모델을 사용하며, 데이터 재생성·재학습은 필요하지 않다. 이번 서버 실행에는 DB나 Docker도 필요하지 않다.

현재 `C:\Users\cy102\Downloads\CSRO` 폴더를 계속 사용한다. API 작업 브랜치 `1121cy-api023`은 최신 `main`의 `85c391e`를 기준으로 이미 생성됐으므로 추가 clone·worktree·브랜치 생성은 필요하지 않다. 2026-11-21 사용자 Windows에서 전체 테스트 91개와 실제 HTTP 점검 8개를 통과했으며, 아래는 같은 서버를 다시 실행·확인할 때 사용하는 절차다.

## 1. 설치·서버 실행 — 첫 번째 PowerShell

```powershell
cd C:\Users\cy102\Downloads\CSRO\ml
.\.venv\Scripts\python.exe -m pip install -r requirements-api.txt
$env:ML_SERVICE_TOKEN = 'csro-local-ml-token'
.\.venv\Scripts\python.exe -m uvicorn travel_ml.risk_api:app --host 127.0.0.1 --port 8002
```

`requirements-api.txt`는 기존 ML 의존성에 HTTP 서버 패키지를 추가한다. `.venv`를 활성화하거나 기존 모델을 다시 학습할 필요는 없다.

`csro-local-ml-token`은 명시적으로 설정하는 **공개 로컬 개발용 예시 값**이다. 서버에 이 토큰의 자동 기본값은 없다. 실제 토큰을 코드·문서·Git에 기록하지 않는다. 운영 인증·키 관리 구성은 별도 작업이다.

기본 모델은 `ml/artifacts/reference_model_v02`다. `ML_MODEL_DIR`를 지정하지 않아도 이 폴더를 사용한다. 다른 모델 묶음을 시험할 때만 서버 시작 전에 이 환경 변수를 설정한다. 상대 경로는 `ml` 루트 기준이다. 환경 변수는 서버 실행 전에 지정하며 변경 후에는 재시작한다. 프로젝트 `.env`를 자동으로 읽는 서버는 아니다.

8002는 Spring 8080·Mock 8001·Agent용 8000과 구분하려는 이번 로컬 제안이다. `127.0.0.1`은 실행 PC 자기 자신이므로 다른 팀원이 접근할 공통 서버 주소가 아니다.

서버 터미널은 실행 중인 상태로 계속 남겨 둔다. 호출은 새 터미널에서 하고 종료할 때는 서버 터미널에서 `Ctrl+C`를 누른다.

## 2. 준비 상태·예측 — 두 번째 PowerShell

서버를 켠 채 새 PowerShell에서 다음을 실행하면 실제 HTTP 요청 8개를 한 번에 확인한다.

```powershell
cd C:\Users\cy102\Downloads\CSRO\ml
$env:ML_SERVICE_TOKEN = 'csro-local-ml-token'
.\.venv\Scripts\python.exe scripts\check_risk_api.py
```

첫 번째 터미널에서 설정한 환경 변수는 새 터미널에 자동 복사되지 않는다. 검사 스크립트도 인증 헤더를 만들기 위해 같은 `ML_SERVICE_TOKEN`을 읽으므로 여기서 다시 설정한다. 실제 토큰을 바꿨다면 양쪽 터미널에 같은 값을 사용한다.

검사 범위는 `/healthz`, `/readyz`, 정상 추론 2회, 토큰 없는 401, 잘못된 JSON의 400, 미지원 버전의 422, 범위 초과 특성의 422다. HTTP 점수를 **같은 PC의 기준 모델 CLI 예측**과 대조하고, 오류 JSON과 추적 ID도 검사한다. 성공 시 마지막 줄은 `Running API-023 checks: passed (8 checks)`다. 다른 모델 경로로 서버를 실행했다면 검사 명령에도 `--model-dir`로 같은 모델 폴더를 지정한다.

직접 요청·응답을 보고 싶을 때는 같은 터미널에서 아래 수동 예제를 사용할 수 있다.

```powershell
cd C:\Users\cy102\Downloads\CSRO\ml
Invoke-RestMethod 'http://127.0.0.1:8002/readyz'

$mlHeaders = @{ Authorization = 'Bearer csro-local-ml-token' }
$mlRequest = Get-Content examples\risk-request.json -Raw -Encoding UTF8
Invoke-RestMethod -Method Post `
  -Uri 'http://127.0.0.1:8002/internal/v1/risk-assessments' `
  -Headers $mlHeaders `
  -ContentType 'application/json' `
  -Body $mlRequest | ConvertTo-Json -Depth 10
```

준비됐을 때 예상 응답은 `/readyz`의 `status=ready`, `modelReady=true`, `authConfigured=true`와 예측의 `riskScore≈0.38033996022231914`, `modelVersion=process-synthetic-lightgbm-0.2-84d386281d22`다. 전체 요청·응답·오류 계약은 [API-023.md](API-023.md)를 확인한다.

수동 예제는 `$mlHeaders`로 인증 헤더를 직접 만든다. 서버의 토큰을 바꿨다면 이 헤더도 같은 값이어야 한다.

`/healthz`는 생존 확인이고 `/readyz`는 추론 준비 확인이다. 토큰 미설정이나 모델 로딩 실패 상태의 서버도 살아 있을 수 있다. `/healthz`가 200이라는 이유만으로 모델 호출까지 성공했다고 기록하지 않는다. 모델 파일을 복구하거나 토큰을 설정한 뒤에는 서버를 재시작한다.

Swagger는 서버 실행 후 `http://127.0.0.1:8002/docs`에서 확인한다. **Authorize**에는 설정한 토큰 값만 입력하고 `Bearer ` 접두사를 직접 붙이지 않는다.

## 3. 모델과 버전 인계

이 API는 고정 LightGBM 기준 모델을 사용한다. XGBoost 후속 실험의 검증 권고를 이유로 모델을 자동 교체하지 않는다. 기준 모델 폴더 전체와 manifest를 보존하고, HTTP 도입을 위해 기존 학습 코드·CLI·평가 값·업무 DB를 바꾸지 않는다.

과거 LightGBM 기준 코드의 재현 참조는 `ml-v0.2-lightgbm-baseline` 태그로 보관하며, 새 clone에서 해당 참조가 없을 때만 `git fetch origin tag ml-v0.2-lightgbm-baseline`으로 가져오면 된다.

| 구분 | 이번 구현 |
| --- | --- |
| HTTP 계약 | `/internal/v1/risk-assessments`, 성공 6개 필드·Problem 오류 |
| 특성 계약 | `risk-features-0.2`의 여섯 필드만 지원 |
| 모델 | 저장한 합성 데이터 LightGBM 모델, 0~1 선별 점수 |
| Spring 특성 집계·저장·임계값·재확인 | 별도 구현·연결 대상 |
| 실제 Spring→ML→업무 DB E2E | 별도 검증 대상 |
| 실데이터 예측 성능·운영 배포 | 이번 구현으로 검증하지 않음 |

원 [Notion API-023](https://app.notion.com/p/cc12f2597c5782d2873d01e8b0c11e51)의 `risk-features-0.1` 필드와 점수 0.78은 가상 예시다. 이번 API는 명시적인 v0.2 확장이며 v0.1을 자동 변환하지 않는다. 과거 장애율 외 특성 선택·산식은 담당 설계안으로 유지한다.

2025년 요청 파일은 HTTP·모델 연결 예제다. 예제의 날짜를 오늘로 바꾸거나 PC 날짜를 변경할 필요는 없다. 해당 JSON을 전송했다고 2026년 YB-001의 DB나 현재 호텔 사실을 조회한 것이 아니다. `riskScore`를 ‘고객의 실제 실패 확률’이나 ‘객실 제공 불가 확정’으로 표시하지 않는다.

## 4. Spring 팀이 연결할 부분

1. D-1 09시까지 이용 가능한 이벤트로 여섯 특성과 고정 대상 집합을 계산한다. Python이 업무 DB에서 이를 대신 집계하지 않는다.
2. base URL·내부 서비스 토큰·HTTP 시간 제한을 Spring 환경 설정으로 전달한다. 외부 HTTP 요청은 DB 행 잠금 트랜잭션 밖에서 수행한다.
3. 응답의 호텔·날짜·시점·특성 버전·모델 버전과 0~1 점수를 확인하고 RiskAssessment 또는 실패를 기록한다.
4. 운영 임계값·처리량에 따라 대상 예약의 공급사·호텔 재확인을 연결한다. 점수만으로 Case를 만들거나 숙박일 미제공을 확정하지 않는다.

401·422는 인증·계약 오류, 503은 모델 준비 실패로 구분한다. 오류를 정상 점수 0으로 저장하지 않는다. 재시도·수동 대응과 업무 상태 변경은 Spring 책임이다.

## 5. 실행 기록의 범위

2026-11-21 사용자가 공유한 최종 Windows PowerShell 출력에서 다음을 확인했다. 직접 원격 PC에서 수집한 로그와 구분하며, [Windows 최종 검증 기록](../verification/api023-windows-2026-11-21.md)에 근거와 범위를 남겼다.

| 확인 항목 | 실제 결과 |
| --- | --- |
| 전체 자동 테스트 | `Ran 91 tests in 19.192s` / `OK`. 기존 회귀 73개와 API/runtime 18개 포함 |
| 실행 중인 서버 생존·준비 상태 | `/healthz` 200, `/readyz` 200 |
| 실제 HTTP 추론 | 2회 모두 200, 같은 PC의 저장 모델 CLI 점수와 일치 |
| 인증·입력 오류 | 토큰 누락 401, 잘못된 JSON 400, 미지원 버전 422, 범위 초과 422 |
| HTTP 점검 요약 | `Running API-023 checks: passed (8 checks)` |

자동 테스트 91개와 별도 실행 서버의 HTTP 점검 8개는 서로 다른 검증 범위다. [자동 테스트 출력](../verification/api023-windows-tests-2026-11-21.txt)과 [HTTP 점검 출력](../verification/api023-windows-live-2026-11-21.txt)을 각각 보존한다.

초기 실행은 임시 테스트 모델을 텍스트로 다시 쓰는 과정에서 Windows 줄바꿈으로 바뀌어 중단됐다. [원인·바이트 보존 수정 기록](../verification/api023-windows-fixture-fix-2026-11-21.md)의 수정 적용 후 위 91개 테스트와 HTTP 점검이 통과했다. 원본 기준 모델과 실제 추론 점수를 변경한 수정은 아니다.

이번 성공은 Python ML HTTP 서버 단독 검증이다. Spring의 특성 집계·추론 호출·위험 평가 저장·예약 재확인 연결과 업무 DB E2E는 아직 별도 구현·검증 대상이다. 100호텔 전체 재생성·기준 모델 재학습·실데이터 성능·운영 배포를 이번 Windows HTTP 점검으로 검증한 것은 아니다. `localhost`/`127.0.0.1:8002`는 사용자 PC 내부 주소이며 팀 공통 서버가 생성된 상태도 아니다.

모델 폴더 내부에 실행 로그나 README를 추가하면 정확한 파일 집합 검사가 실패한다. 설명·실행 기록은 `docs`에 보관하고 토큰은 출력·공유하지 않는다.
