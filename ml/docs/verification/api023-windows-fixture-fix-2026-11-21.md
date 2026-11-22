# Windows API-023 테스트의 임시 모델 줄바꿈 수정

2026-11-21 사용자 Windows 실행은 전체 테스트 완료 전에 중단됐다. `test_risk_api`의 11개를 포함해 51개가 `ok`로 표시됐지만, `test_missing_corrupt_and_feature_incompatible_models_fail_closed` 중 LightGBM Fatal 메시지가 나오고 PowerShell로 돌아왔다. `Ran 91 tests`·최종 `OK`가 없으므로 성공으로 기록하지 않는다. 초기 실패는 이 문서에 미완료 범위로 기록한다.

## 원인과 변경

테스트가 임시 복사본의 `feature_names` 순서를 바꾸면서 `read_text()`·`write_text()`로 모델 전체를 다시 저장했다. Windows의 기본 텍스트 쓰기는 LF를 CRLF로 바꾼다. LightGBM 텍스트 모델의 `tree_sizes`는 트리 구간의 바이트 크기를 기록하므로 이 변환 뒤에는 실제 바이트 위치와 맞지 않는다. 테스트는 의도한 특성 순서 오류에 도달하기 전에 잘못된 형식의 모델을 native 파서에 전달했다.

원본은 41,865바이트·43개 트리이며 CRLF 변환 시 42,829바이트가 된다. 첫 트리의 선언 크기 869바이트가 실제로는 888바이트가 되는 것을 확인했다. Linux의 격리 subprocess에서도 이 CRLF 모델이 SIGABRT로 종료하고 사용자 로그와 같은 첫 Fatal 메시지를 내는 현상을 재현했다. 사용자 Windows의 실제 종료 코드는 제공되지 않았다.

수정 대상 실행 로직은 `tests/test_risk_runtime.py`의 해당 테스트뿐이다. 모델 복사본을 `read_bytes()`·`write_bytes()`로 처리하고, 특성 이름 외의 바이트·길이·줄바꿈이 그대로인지 native 호출 전에 확인한다. 테스트를 생략하거나 성공 조건을 완화하지 않았다. 모델 특성 불일치가 거부되는 검사는 유지했다.

원본 모델 8개 파일, API·runtime·CLI·시뮬레이터·학습·XGBoost 비교 소스, 데이터 규칙과 패키지 버전은 변경하지 않았다. 모델 재학습이나 업무 DB 작업은 필요하지 않다. 처음 출력된 pip 업데이트 안내와 Starlette deprecation 경고는 이번 중단의 원인이 아니다.

## 확인한 검증 범위

| 검사 | 환경·결과 |
| --- | --- |
| 수정 후 전체 91개 | Linux Python 3.12.14, 실패·건너뜀 없이 통과, 5.241초 |
| 문제 테스트의 Windows 줄바꿈 모의 | 격리 subprocess에서 `Path.write_text`를 CRLF로 강제, 해당 1개 테스트 통과, 0.077초 |
| 모델 수송 중 CRLF 변조 | 기존 checksum 검사가 LightGBM 호출 전에 차단, Booster 호출 0회 |
| 원본 모델 | SHA-256 `84d386281d22f4050cb6a8a02cc03d3bcf09e64194213f613e1069e915d68c88` 유지 |
| 실제 Windows 수정 후 재실행 | 전체 91개 통과, 19.192초, 이후 실제 HTTP 점검 8개 통과 |

위 표에 수정 후 핵심 검증 결과를 요약했다. Linux에서 Windows 줄바꿈을 모의한 검사를 실제 Windows 성공으로 표시하지 않는다. 이후 사용자 PC에서 전체 테스트를 다시 실행해 `Ran 91 tests in 19.192s`와 `OK`를 확인했고, 실제 HTTP 점검 8개도 통과했다. [Windows 최종 확인](api023-windows-2026-11-21.md)에 두 출력의 근거를 별도로 남겼다.

이번 변경은 API 계약·모델 점수·평가 결과를 바꾸지 않는다. Windows의 live HTTP 점검까지 확인했으며, 실제 Spring 연결과 업무 DB E2E는 여전히 별도 확인 대상이다.
