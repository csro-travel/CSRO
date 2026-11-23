# 여행봄 ML v0.2 — 과정 기반 시뮬레이션·학습·추론

기존 합성 관측과 Spring batch를 한 명령으로 연결하는 [로컬 위험 점검 자동 실행](docs/RISK_AUTOMATION.md)을 추가합니다. 모델·특성 산식·기존 HTTP 계약을 유지하며, 기본 한 번 실행과 명시적 반복 실행을 지원합니다. Python은 파일 준비·Spring 프로세스 실행만 맡고 업무 DB는 Spring만 처리합니다.

호텔 예약의 전달·재고 동기화·복구·실제 이행·증빙 수집 과정을 시뮬레이션하고, **관측 가능한 증빙에서 학습 정답을 추출**하는 독립 ML 작업 폴더입니다. 생성 데이터의 특성으로 정답을 직접 추첨하거나, 완성된 정답을 나중에 무작위로 가리는 방식은 사용하지 않습니다.

주 seed는 **42**, 본 실행은 **가상 호텔 100곳·2025년**, 예측 시점은 **체크인 전날 09:00 한국 시각**입니다. LightGBM과 과거 장애율 기반 규칙을 비교하고 모델 파일·평가 기록·CLI 추론 예제를 인계합니다.

2026-11-20 사용자 Windows PC에서 **자동 테스트 62개·기준 모델 무결성 검사·CLI 예측**을 확인했습니다. [Windows 검증 범위와 출력](docs/verification/windows-2026-11-20.md)을 참고하세요. 전체 100호텔 데이터 재생성과 기준 모델 재학습은 클라우드에서 검증했으며, 이번 Windows 실행으로 재현한 범위에는 포함하지 않습니다.

XGBoost 후속 비교도 별도 명령으로 제공합니다. 같은 CSV·특성·라벨·기간을 사용한 [비교 기준과 결과](docs/XGBOOST_COMPARISON.md), 기존 폴더에서 실행하는 [Windows 절차](docs/XGBOOST_WINDOWS.md)를 참고하세요. 추가 설치는 `requirements-xgboost.txt`로 하며 기존 LightGBM 기준 모델을 교체하지 않습니다. 수치 경계 호환성 보완 후 클라우드와 사용자 Windows에서 전체 73개 테스트와 비교 실행을 확인했습니다. [Windows XGBoost 성공 기록](docs/verification/xgboost-windows-2026-11-20.md)에 범위와 결과를 남겼습니다.

**API-023 HTTP 추론 서버**를 추가했습니다. 저장한 LightGBM 기준 모델을 시작 시 한 번 읽고 서비스 Bearer 인증·v0.2 입력 검증·추론·Problem 오류 응답을 제공합니다. [HTTP 계약](docs/api/API-023.md), [Windows 실행·Spring 인계](docs/api/API-023_HANDOFF.md), [클라우드 검증 범위](docs/verification/api023-cloud-2026-11-20.md)를 참고하세요. 추가 설치는 `requirements-api.txt`입니다. 사용자 Windows에서 전체 91개 테스트(19.192초)와 실제 HTTP 점검 8개를 확인했습니다. [Windows 최종 기록](docs/verification/api023-windows-2026-11-21.md)에 근거를 남겼으며 Spring 업무 연결은 별도 작업입니다.

**합성 관측 이벤트 → API-023 입력 변환**은 [오프라인 실행 절차](docs/RISK_EVENT_PROJECTION.md)를 제공합니다. 기존 여섯 특성 계산기를 그대로 사용해 요청·대상 예약 코드·추적 해시를 로컬 파일에 저장합니다. HTTP 계약을 추가하지 않으며, Spring의 자동 DB 이벤트 수집이나 과거 상태 복원까지 구현한 기능은 아닙니다. YB-001 예제는 이력 없는 기본값을 보존하므로 기본 F1 정책에서 선별되지 않습니다.

2026-11-21 병합 후 main `85c391e`에서도 전체 91개 테스트와 실제 HTTP 점검 8개를 확인했습니다. 현재 작업 브랜치는 `1121cy-api023`이며 [main 기준 적용 기록](docs/verification/api023-main-2026-11-21.md)에 검증 범위를 남겼습니다.

사용자 Windows의 첫 전체 실행은 임시 모델을 만드는 테스트의 줄바꿈 변환 문제로 중단됐습니다. 해당 테스트를 바이트 단위 저장으로 수정했고, Linux 전체 91개·Windows 줄바꿈 모의 검사·사용자 Windows 재실행을 통과했습니다. [원인·수정·검증 기록](docs/verification/api023-windows-fixture-fix-2026-11-21.md)을 참고하세요. 기존 모델과 API 실행 코드는 그대로입니다.

**과거 장애율을 주요 신호로 삼는다는 점은 팀 합의입니다. 여섯 특성의 구체적 선택·산식과 시뮬레이터 수치 매개변수는 담당 설계안입니다.** 실제 장애 분포나 팀 전체의 승인 완료 규칙으로 설명하지 않습니다. 상세 기준은 [ML_DATA_RULES_v0.2.md](docs/ML_DATA_RULES_v0.2.md), 선택 이유는 [DECISIONS_v0.2.md](docs/DECISIONS_v0.2.md)를 봅니다.

## 1. 기존 프로젝트에서의 위치와 범위

압축파일의 `ml` 폴더를 다음 위치에 복사합니다.

```text
C:\Users\cy102\Downloads\CSRO\
├─ backend\
├─ frontend\
├─ llm\
├─ mock-server\
├─ tools\
└─ ml\                         # 이번 작업
   ├─ config\simulator_v0.2.json
   ├─ schemas\
   ├─ travel_ml\
   ├─ tests\
   ├─ docs\
   ├─ requirements.txt
   └─ README.md
```

파일을 직접 복사해야 Windows PC에 생깁니다. 기존 `mock-server`, Spring 코드, DB, Flyway, 프로젝트 루트 `.env`를 덮어쓸 필요는 없습니다.

이 폴더는 **오프라인 데이터 생성·모델 학습·CLI 추론과 API-023 Python HTTP 추론**을 포함합니다. Spring의 특징값 집계·호출·위험 평가 저장·후속 업무, Agent 업무 처리와 공통 서버 배포는 별도 작업입니다. 실행에 PostgreSQL이나 Docker는 필요하지 않습니다. `mock-server`의 API-026·027 고정 응답과 2026년 YB-001은 별개로 유지합니다.

기존 `tools/data_generator`의 1,080건 생성기나 CSV는 이번 과정 기반 v0.2 산출물이 아닙니다. 폴더나 버전 이름만 바꾸어 재사용하지 않습니다.

## 2. Windows 환경 설치

VS Code의 PowerShell 터미널을 기존 **`CSRO` 저장소 루트**에서 열고 실행합니다. 현재 PC의 Python 실행은 **`py -3.13`**을 사용합니다. 설치 이후 모든 명령은 이 폴더의 가상환경 Python을 직접 호출합니다. 이미 `.venv`가 있으면 다시 만들 필요가 없습니다.

```powershell
Set-Location .\ml
py -3.13 --version
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

가상환경 활성화나 PowerShell 실행 정책 변경은 필요하지 않습니다. 데이터·학습용 의존성은 `requirements.txt`의 고정 버전을 사용합니다. 같은 seed라도 코드·설정·라이브러리 버전이 달라지면 결과를 같은 실험으로 취급하지 않습니다.

## 3. 먼저 소량 파일럿 실행

```powershell
.\.venv\Scripts\python.exe -m travel_ml generate --config config\simulator_v0.2.json --output generated --hotels 3 --pilot
.\.venv\Scripts\python.exe -m travel_ml verify --input generated
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

파일럿은 안정·저하·취약 프로필을 대표하는 호텔 3곳(`HOTEL-001`, `HOTEL-076`, `HOTEL-096`)으로 코드·관측·대상 집합·특성·정답·저장 구조를 확인하는 실행입니다. 대상 패널은 2025-09-15까지, 시뮬레이션 처리는 2025-09-27까지로 줄입니다. 내부 수요 buffer의 체크인 날짜는 2025-12-14까지 생성할 수 있지만 **11~12월을 평가하는 모델 테스트 표본을 만드는 실행은 아닙니다.**

파일럿에서 정상/장애·관측률·경계 처리를 확인합니다. 분포가 원하는 모양이 아니라는 이유로 정답을 임의 주입하지 않습니다. 생성 조건을 바꿔야 한다면 이유·설정·버전을 함께 기록하고 별도 실험으로 재생성합니다.

파일럿 결과를 100호텔·연간 평가 완료 결과로 보고하거나, 비어 있는 10월 검증·11~12월 평가 구간을 성능 0으로 해석하지 않습니다. 학습 명령도 파일럿 디렉터리를 거부합니다. 본 학습은 다음 전체 생성 결과를 사용합니다.

## 4. 전체 데이터 생성

파일럿 검수 후 본 설정을 사용합니다. 전체 생성 명령은 실행 전에 유효 설정을 `effective_config.json`으로 복사하면서 `config_frozen_for_final_test=true`로 고정하고, 설정 해시와 `experiment_lock.json`을 기록합니다. 사용자가 원본 설정의 플래그를 직접 편집할 필요는 없습니다. 파일럿은 고정 전 상태로 표시합니다.

```powershell
.\.venv\Scripts\python.exe -m travel_ml generate --config config\simulator_v0.2.json --output generated_full
.\.venv\Scripts\python.exe -m travel_ml verify --input generated_full
```

기본 설정은 호텔 100곳, 2025년 1~12월 패널입니다. **100×365=36,500은 달력 패널 행 수**이며, 지도학습에 들어가는 행 수와 다릅니다. 빈 예측 대상·미확인 정답·준비 구간·라벨 성숙 경계의 행은 학습/검증/평가에서 제외될 수 있습니다.

시뮬레이터는 과거 이력을 위해 2024-09-01부터 시작합니다. 연말 숙박·증빙을 위해 2026-01-12까지 처리하며, 향후 예약 유입 경계를 자르지 않기 위한 수요 buffer는 2026-03-31 체크인까지 생성합니다. **2024년과 2026년 자료는 2025년 평가 패널에 섞지 않습니다.**

전체 실행은 파일럿보다 많은 시간과 저장 공간이 필요합니다. 처리 시간·메모리 한계를 측정한 기록이 없으면 일정한 분량 안에 완료된다고 가정하지 않습니다. 기존 출력은 덮어쓰기 대상으로 사용하지 말고 새 출력 폴더로 실험을 구분하세요.

기본은 메모리를 아끼는 순차 실행입니다. 여유 메모리가 있는 환경에서는 `--workers 2` 또는 `--workers 4`로 호텔 단위를 병렬 처리할 수 있습니다(최대 4). 같은 설정의 순차·병렬 산출물 일치 검사를 포함합니다. 클라우드 4개 작업자 측정은 약 180초였으며 Windows PC에서의 시간 보장은 아닙니다.

## 5. 학습 및 추론

### 모델 학습·비교

```powershell
.\.venv\Scripts\python.exe -m travel_ml train --input generated_full --output artifacts\model_v02
```

날짜 구간은 고정합니다.

| 구간 | 체크인 날짜 | 용도 |
| --- | --- | --- |
| 2025-01~03월 | 1월 1일~3월 31일 | 준비 이력·감사, 지도학습 분할 제외 |
| 학습 | 4월 1일~9월 30일 | LightGBM 학습 |
| 검증 | 10월 1일~10월 31일 | 후보·임계값 선택, 특성 중요도 점검 |
| 테스트 | 11월 1일~12월 31일 | 고정한 모델·임계값의 최종 비교 |

학습 정답은 첫 검증 예측 시각인 `2025-09-30T09:00:00+09:00` 이전, 검증 정답은 첫 테스트 예측 시각인 `2025-10-31T09:00:00+09:00` 이전에 관측되어야 합니다. 경계와 같거나 그 뒤에 확인된 행은 해당 구간에서 제거합니다. 테스트는 고정 추출 시각까지 받은 증빙만 사용합니다.

후보·임계값은 검증 구간에서 선택합니다. 최종 인계 모델은 4~9월에 학습한 선택 checkpoint이며 이 버전에서는 학습+검증 재학습을 하지 않습니다. 테스트 성능을 보고 생성 규칙이나 모델 후보를 바꾸면 같은 최종 테스트 결과로 덮어쓰지 않습니다.

### 저장한 모델로 한 건 예측

```powershell
.\.venv\Scripts\python.exe -m travel_ml predict --model artifacts\model_v02 --request examples\risk-request.json
```

추론 점수는 검증된 객실 미제공 사실이 아닙니다. 위험 점수만으로 예약 취소·사건 확정·숙박일 제공 불가를 저장하지 않습니다.

이 명령은 JSON 파일을 읽는 **로컬 CLI 추론 예제**입니다. HTTP 서버는 [별도 실행 절차](docs/api/API-023_HANDOFF.md)로 시작합니다. Spring의 자동 호출은 연결 전입니다. 요청 예시의 숫자는 계약 형태를 확인하기 위한 값이며 실제 호텔의 관측값이 아닙니다.

기존 `risk-features-0.1` 입력과 이번 `risk-features-0.2`를 혼용하지 않습니다. 모델·메타데이터·입력의 특성 이름·순서·자료형·버전이 일치해야 합니다.

## 6. 출력에서 먼저 볼 것

| 산출물 | 의미 |
| --- | --- |
| `effective_config.json`, `experiment_lock.json`, `manifest.json` | 고정된 실행 설정·설정 해시·seed·기간·버전·파일 식별 정보·행 수 |
| `quality_report.json` | 상태·관측률·분할·시뮬레이션 정합성 결과 |
| `source/hotel=HOTEL-.../` | 예약·숙박일·공개 관측 이벤트의 Parquet. `events.parquet`의 `OBSERVED_OUTCOME`에 관측 결과 증빙 포함 |
| `audit/hotel=HOTEL-.../` | 특성·정답과 계산 감사 자료의 Parquet |
| `private_audit/hotel=HOTEL-.../` | 비공개 호텔 성향·과정 이벤트·실제 결과 truth 감사 자료 |
| `dataset.csv` | 2025년 달력 패널의 여섯 특성·정답·감사 정보 |
| `train.csv`, `validation.csv`, `test.csv` | 규칙에 따라 포함된 지도학습 표본 |
| `split_audit.csv` | 준비 구간·미확인·빈 집합·성숙 경계 등 포함/제외 사유 |
| `artifacts/model_v02/model.txt`, `model_metadata.json` | 선택한 checkpoint와 특성 계약·학습 설정 |
| `evaluation_report.json`, `evaluation_report.md`, `search_results.csv`, `test_predictions.csv` | 모델 출력 폴더의 비교·임계값·관측률·상위 K·검증 중요도·탐색·개별 점수 |

파일럿 직후에는 다음처럼 행 수와 분할 요약부터 볼 수 있습니다.

```powershell
Get-Content generated\manifest.json -Raw -Encoding UTF8 | ConvertFrom-Json | Select-Object hotelCount, calendarRows, labelCounts, splitRows
Import-Csv generated\dataset.csv | Select-Object -First 5
```

`private_truth`에 해당하는 `private_audit`의 `truth.parquet`는 시뮬레이터 상태 전이와 실제 객실 수 보존을 검사하는 자료입니다. 학습 정답은 수집된 관측 증빙(`OBSERVED_OUTCOME` 이벤트)에서 계산하며, 증빙이 없다는 이유로 숨은 truth에서 정답을 보충하지 않습니다. Parquet은 대용량 원천 저장용이므로 VS Code에서 일반 텍스트처럼 읽는 파일이 아닙니다.

모델 입력은 아래 여섯 필드만 사용합니다.

1. `pastFailureRate90d`
2. `targetConfirmedBookingCount`
3. `bookingVolumeGrowth7d`
4. `reconfirmationOverdueRate7d`
5. `inventoryUnavailableRate7d`
6. `isPeakDay`

호텔 ID·예약 ID·숨은 호텔 성향·장애 원인·정답·정답 시각·관측 감사 열·미래 정보는 입력하지 않습니다. CSV 전체 열을 자동으로 학습시키지 않습니다. 특히 두 번째 특성은 **대상 확정 예약 건수**이며 가격 변동 특성이 아닙니다.

정답 `1`은 고정 예측 대상 중 원호텔의 필요한 숙박일 미이행이 증빙으로 확인된 예약이 한 건 이상인 경우입니다. `0`은 대상이 비지 않고 전부 원호텔 정상 이행이 확인된 경우입니다. 나머지는 `UNKNOWN`, 빈 대상은 `NOT_ELIGIBLE`이며 정상 0으로 채우지 않습니다.

## 7. Git 공유 범위

Mock 작업의 현재 알려진 커밋은 `1118cy`의 `9cef484`입니다. PR 생성·병합 상태는 현재 GitHub에서 별도로 확인해야 합니다. 이번 ML 작업을 Mock 브랜치에 이어서 푸시하는 절차로 안내하지 않습니다.

먼저 현재 상태를 읽습니다.

```powershell
cd C:\Users\cy102\Downloads\CSRO
git status --short --branch
git branch --show-current
```

ML은 `feature/ml-v02` 같은 별도 작업 브랜치로 구분하는 것을 권장합니다. 새 브랜치는 현재 HEAD를 기반으로 만들어지므로, Mock 변경의 반영 상태와 ML의 기준 브랜치를 먼저 확인합니다. 기존 미커밋 파일을 강제로 지우거나 무조건 stash하지 않습니다.

공유 대상은 `ml`의 코드·설정·스키마·테스트·문서와 **`artifacts/reference_model_v02`의 고정된 모델 묶음 전체 8파일(약 1.1MB)**입니다. 이 묶음을 포함하면 팀원은 전체 데이터를 다시 생성하거나 학습하지 않고 아래 기준 모델 예측 명령을 실행할 수 있습니다. `.venv`, 생성된 원천/CSV, 다른 모델 실험 결과·캐시는 Git에서 제외합니다. 이전 `tools/data_generator` 초안은 이번 ML v0.2 코드와 함께 커밋하지 않습니다.

모델 묶음은 manifest가 정확한 파일 집합과 해시를 확인하므로 전체를 함께 관리합니다. `ml/.gitattributes`가 이 폴더의 줄바꿈 변환을 막습니다. 모델 내부에 설명 파일이나 로그를 추가하지 않습니다. 원래 학습 시점의 커밋 정보가 없었다는 메타데이터는 새 공유 커밋으로 소급 변경하지 않습니다.

## 8. 완료 범위와 결과 해석

실제 실행·테스트·학습 결과는 함께 제공되는 검증 기록과 생성된 평가 보고서가 기준입니다. Windows 실행 예제를 제공했다는 이유로 사용자의 PC에서 실행 완료됐다고 주장하지 않습니다. Mock 서버의 28개 테스트 결과도 ML 테스트·모델 검증 결과와 별개입니다.

가상 데이터에서 높은 성능이 나와도 실제 고객의 장애 예측 성능이나 AI 개입의 개선 효과가 입증되는 것은 아닙니다. 같은 고정 복구 정책 아래, 같은 가상 세계에서 규칙과 모델을 비교한 결과입니다. 과거 장애율의 중요도 순위를 강제하지 않으며 실제 결과를 보고합니다.

자세한 인계 범위·입력 계약·평가 해석은 [HANDOFF.md](docs/HANDOFF.md)를 확인하세요.

## 9. 함께 제공한 실제 결과

[검증 기록](docs/VERIFICATION.md)과 [실험 결과](docs/RESULTS.md)에 실제 실행 범위·수치·한계를 기록했습니다. 원 설계 JSON의 `future_work`와 규칙 문서 끝의 예정 표현은 설계 초안 당시의 기록이며, 현재 구현 상태는 이 실행 기록을 기준으로 확인합니다.

별도 결과 ZIP은 `generated_reference_csv`에 열어볼 CSV와 동일한 기준 모델을 제공합니다. 기준 모델은 Git 공유 범위에도 포함합니다. CSV 묶음은 원천 Parquet 전체를 포함하지 않는 조회·인계용 사본입니다. `generate`의 완전한 출력 폴더처럼 학습 명령에 직접 전달하지 말고, 학습을 재현하려면 전체 데이터를 생성하세요. 기준 모델은 다음처럼 바로 검증·추론할 수 있습니다.

```powershell
.\.venv\Scripts\python.exe -m travel_ml verify --input artifacts\reference_model_v02
.\.venv\Scripts\python.exe -m travel_ml predict --model artifacts\reference_model_v02 --request examples\risk-request.json
```

이 모델은 `artifacts/model_v02`와 경로를 구분하므로 내 PC의 새 학습 출력을 덮어쓰지 않습니다. 모델 디렉터리 내부에는 별도 README나 로그를 추가하지 마세요. 무결성 검사는 선언한 파일 집합을 정확히 확인합니다. `audit_slices.json`은 모델/CSV/원천 manifest가 같은 실험인지 확인한 뒤에만 결합한 사후 진단이며 학습 입력이 아닙니다.

`SOURCE_MANIFEST.json`은 마지막 기록 당시 전달본의 소스·문서 해시입니다. 이번 오프라인 이벤트 변환기는 Git 변경과 전용 테스트로 추적하며, 해당 전달본 manifest를 새로 기록하지 않습니다. 실행 시 생성 산출물·모델을 확인하는 `verify`는 각 출력 폴더의 별도 manifest를 사용합니다. 11월 18일 최초 전달본과 11월 20일 LightGBM 기준 커밋의 manifest는 `docs/provenance/`에 보존합니다. 규칙·특성·기존 생성/LightGBM 학습 코드·기준 모델은 그대로 유지하며, `travel_ml/xgboost_compare.py`에 후속 비교를 추가합니다. 새 XGBoost 결과는 별도 출력으로 관리하고 데이터·기준 모델을 덮어쓰지 않습니다.
