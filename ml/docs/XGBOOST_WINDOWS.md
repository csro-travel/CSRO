# Windows에서 XGBoost 후속 비교 실행

현재 사용 중인 **`C:\Users\cy102\Downloads\CSRO` · `1120cy-ml`**에서 이어서 실행합니다. 새 브랜치나 worktree, 별도 작업 폴더를 만들지 않습니다. 기준 커밋은 `b2c05a8`입니다.

기존 CSV와 LightGBM 기준 모델을 그대로 사용하므로 **데이터를 다시 생성하거나 LightGBM을 다시 학습할 필요가 없습니다.** 이번 명령은 XGBoost만 학습해 별도 결과 폴더에 비교 보고서를 만듭니다. API-023 HTTP 서버·Spring 연결·업무 DB 변경은 포함하지 않습니다.

## 1. 추가 패키지 설치

VS Code PowerShell에서 실행합니다.

```powershell
cd C:\Users\cy102\Downloads\CSRO\ml
.\.venv\Scripts\python.exe -m pip install -r requirements-xgboost.txt
```

기존 19개 의존성의 고정 버전을 유지하고 `xgboost-cpu==3.4.1`을 추가합니다. 가상환경을 활성화할 필요는 없습니다. 만약 `.venv\Scripts\python.exe`가 없다는 오류가 나면 이 폴더에서 `py -3.13 -m venv .venv`를 한 번 실행한 뒤 위 설치 명령을 다시 실행합니다.

## 2. 테스트 후 비교 실행

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m travel_ml.xgboost_compare --input generated_reference_csv --reference-model artifacts\reference_model_v02 --output artifacts\xgboost_comparison_v02
```

기존 Windows의 **62개 테스트 통과·LightGBM 예제 추론 성공은 기준본 검증 기록**입니다. 이후 수치 호환성 보완본으로 사용자 Windows에서 **전체 73개 테스트(23.237초)와 XGBoost 실제 비교**를 완료했습니다. [성공 기록](verification/xgboost-windows-2026-11-20.md)을 참고하세요. 다른 PC의 실행 완료를 보장하는 기록은 아니므로 각 실행 결과를 별도로 확인합니다.

비교 명령은 실행 전에 5개 CSV, 실행 설정, 원래 전체 생성 manifest, 기준 모델 묶음의 해시와 연결 관계를 자동 확인합니다. 4~9월 학습·10월 검증의 표본과 기존 LightGBM 검증 AP도 대조합니다. CSV 사본에 없는 원천 Parquet 전체까지 다시 검증하는 작업은 아닙니다.

출력 폴더가 이미 있으면 덮어쓰지 않고 중단합니다. 다시 실행하려면 `--output artifacts\xgboost_comparison_v02_run2`처럼 새 **결과 폴더 이름**만 지정하세요. `artifacts\reference_model_v02`를 출력 경로로 지정하지 않습니다.

## 3. 결과 확인

```powershell
Get-Content artifacts\xgboost_comparison_v02\evaluation_report.md -Encoding UTF8
.\.venv\Scripts\python.exe -m travel_ml verify --input artifacts\xgboost_comparison_v02
```

| 결과 파일 | 확인할 내용 |
| --- | --- |
| `evaluation_report.md`, `evaluation_report.json` | XGBoost·기존 LightGBM·규칙 비교, 관측률·상위 10개 검토 결과 |
| `search_results.csv` | 10월 검증에서 비교한 6개 XGBoost 후보 |
| `selection_frozen.json` | 테스트 평가 전에 고정한 후보·임계값·검증 기준 비교 권고 |
| `xgboost_model.json`, `model_metadata.json` | 새 모델과 입력 계약·버전·해시 |
| `test_predictions.csv` | 각 평가 행의 네 모델 점수와 선별 결과 |
| `validation_permutation_importance.json` | 검증 구간에서 측정한 특성 중요도 |
| `protocol.json`, `manifest.json` | 실제 비교 설정과 결과 파일 무결성 |

모델 클래스 비교 권고는 **10월 검증 AP**로 정하며, 기존 LightGBM·규칙의 임계값은 원래 값을 유지합니다. 이번 결과로 기준 모델을 자동 교체하거나 배포하지 않습니다. 과거 장애율의 중요도 순위도 강제하지 않습니다.

기존 11~12월 LightGBM 성능을 이미 확인한 뒤 추가한 **후속 탐색 비교**입니다. 같은 평가 구간을 처음 보는 독립 최종 검증으로 설명하지 않습니다. 모든 결과는 합성 데이터 실험이며 실제 호텔 장애 예측 성능이나 AI 개입 효과를 입증하지 않습니다. 상세 비교 기준은 [XGBOOST_COMPARISON.md](XGBOOST_COMPARISON.md)를 봅니다.

## CSV가 없을 때만

`generated_reference_csv\dataset.csv`를 찾지 못한다면 기존 `csro-ml-v02-results.zip`에서 CSV 사본과 번들 manifest만 복원합니다. ZIP이 `Downloads`에 있다는 전제이며, 위와 같이 **`CSRO\ml`에서** 실행합니다. 이미 있는 파일과 기준 모델 8개 파일은 건드리지 않습니다.

```powershell
@'
from pathlib import Path
from zipfile import ZipFile
import sys
root = Path.cwd().parent
with ZipFile(sys.argv[1]) as archive:
    for entry in archive.infolist():
        name = entry.filename
        if entry.is_dir() or not (name.startswith("ml/generated_reference_csv/") or name == "ml/artifacts/reference_bundle_manifest.json"):
            continue
        target = root / name
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
print("Missing CSV bundle files restored; existing files preserved.")
'@ | .\.venv\Scripts\python.exe - "$env:USERPROFILE\Downloads\csro-ml-v02-results.zip"
```

복원 후 2번의 비교 명령을 다시 실행합니다. 해시 불일치가 나오면 임의로 manifest를 수정하지 말고 오류를 확인하세요. 이 CSV 사본은 이번 비교 명령용이며, 기존 `travel_ml train`에 완전한 생성 출력처럼 넣는 파일은 아닙니다.

## Git 공유 범위

`generated_reference_csv`와 새 `artifacts\xgboost_comparison_v02` 결과는 기존 설정으로 Git에서 제외합니다. 기준 모델은 보존하고 새 비교 코드·설정·의존성·테스트·문서만 후속 변경으로 공유합니다. 커밋 전 `git status --short`로 범위를 확인하며, Windows 실행 기록은 실제 확인한 결과만 추가합니다. 이 안내 자체가 커밋·푸시·병합을 실행하지는 않습니다.
