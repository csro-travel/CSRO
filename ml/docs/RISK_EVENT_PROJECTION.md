# 합성 관측 이벤트에서 API-023 입력 만들기

`travel_ml.risk_projection`은 호텔 하나의 합성 관측 JSON을 읽어 기존 `features.build_features`로 여섯 특성을 계산하고, Spring에 전달할 로컬 파일을 만듭니다. API-023의 HTTP URI·인증·요청·응답 계약은 그대로입니다. Python HTTP 서버에서는 저장된 모델 추론만 수행합니다.

## 실행

저장소 루트에서 PowerShell로 실행합니다. 기존 `ml/.venv`와 `requirements.txt`의 설치가 필요하며 추가 패키지는 없습니다.

```powershell
Set-Location .\ml
.\.venv\Scripts\python.exe -m travel_ml.risk_projection --source examples\risk-events-cold-start.json --hotel-id HOTEL-001 --target-check-in-date 2026-11-10 --output generated_risk_stage2\projection.json
```

출력 파일이 이미 있으면 중단합니다. 새 파일명을 지정해 실행하며 기존 출력을 덮어쓰지 않습니다. 성공 출력은 상태·형식·합성 자료 구분·대상 수만 포함합니다. JSON 중복 키, 비유한 수, 잘못된 자료형·시각·호텔 식별자는 원문 값을 출력하지 않고 거부합니다. 빈 예측 대상 집합은 `NOT_ELIGIBLE`로 중단합니다.

이 명령은 DB·HTTP·시뮬레이터·모델 파일에 접근하지 않습니다. private truth·최종 예약 테이블·정답 CSV를 특성에 사용하지 않으며, 라벨 추출·학습 코드를 실행하지 않습니다. 과거 이행 관측인 `OBSERVED_OUTCOME`은 기존 과거 장애율 산식에 사용합니다.

## 입력과 시간 기준

입력은 기존 호텔 shard의 JSON 형태인 `{"hotel":{"hotel_id":"HOTEL-001"},"events":[...]}`입니다. 각 공개 이벤트는 `event_id`, `hotel_id`, `event_type`, `occurred_at`, `available_at`, `payload` 및 해당 관측의 `booking_id`를 가집니다. 실제 형식은 [최소 예제](../examples/risk-events-cold-start.json)를 참고합니다. 이 예제는 명시적 시연용 합성 자료이며 실제 호텔 관측으로 주장하지 않습니다.

- 사용하는 공개 이벤트는 `BOOKING_STATUS`, `RECONFIRMATION_REQUEST`, `HOTEL_REPLY`, `INVENTORY_PROBE`, `OBSERVED_OUTCOME`입니다. 다른 이벤트, 루트의 최종 예약·truth, 호텔의 private 속성은 계산기에 전달하지 않습니다.
- 호텔 식별자와 모든 공개 이벤트의 호텔이 요청 대상과 일치해야 합니다. 발생·이용 가능 시각이 모두 D-1 09:00 Asia/Seoul 이하인 관측만 특성·대상 집합에 반영됩니다. 미래 관측과 private truth를 바꾸어도 요청·대상 집합은 바뀌지 않습니다.
- `config/simulator_v0.2.json`을 복사한 뒤 `calendar_panel.start/end`만 대상 체크인 날짜로 지정합니다. 여섯 특성의 산식·사전값·시간 창·성수일·자료형·버전은 바꾸지 않습니다. 이 변환에 맞추어 시뮬레이션 기간이나 2025년 학습 구간을 확장하지 않습니다.
- API 요청은 `hotelId`, `targetCheckInDate`, `asOf`, `featureSetVersion`, `features`의 다섯 필드입니다. `features`에는 [기존 스키마](../schemas/features-v0.2.schema.json)의 여섯 필드만 포함하며 기존 `risk_runtime.validate_request`로 검증합니다.

## 로컬 전달 파일과 한계

파일의 최상위 필드는 `format`, `sourceKind`, `request`, `cohortBookingCodes`, `provenance`입니다. 형식은 `risk-event-projection-1`, 자료 구분은 `SYNTHETIC_OBSERVATIONS`로 고정합니다. `request`만 API-023 요청입니다. 나머지는 로컬 전달용 정보이며 새로운 팀 간 HTTP 계약이 아닙니다.

`cohortBookingCodes`는 D-1에 알려진 대상 플랫폼 확정 예약 코드의 정렬된 목록입니다. Spring은 이 목록을 현재 시연 DB의 대상 예약과 대조한 뒤 평가합니다. 이 검사는 현재 Spring fixture와 계산 대상의 연결을 확인합니다. DB의 과거 예약·취소·변경·수신 지연을 완전히 재구성했다는 보장은 없으며, 운영용 과거 상태 복원·자동 수집은 별도 작업입니다.

`provenance`에는 다음 네 SHA-256 값만 저장합니다.

| 필드 | 계산 대상 |
| --- | --- |
| `sourceSha256` | 입력 전체의 canonical JSON: 키 정렬, 공백 없는 UTF-8. 파일 들여쓰기·줄바꿈은 제외 |
| `featureBuilderSha256` | 실제 사용한 `features.py`의 바이트 |
| `configSha256` | 대상 날짜의 calendar panel을 적용한 유효 설정의 canonical JSON |
| `lineageDigest` | 기존 계산기가 사용한 관측 이벤트 ID를 정렬·중복 제거 후 줄바꿈으로 연결한 값 |

전체 입력 해시는 계산에서 제외한 truth·미래 이벤트가 바뀌어도 달라질 수 있습니다. 해시는 자료·코드·설정의 추적용 참조이며 발행자 서명이나 관측 진위를 보증하는 암호학적 인증이 아닙니다. Spring이 원천 파일을 다시 집계해 특성값을 검증하는 기능도 포함하지 않습니다.

## 최소 예제의 의미와 검증

예제는 `YB-001`, `HOTEL-001`, 체크인 `2026-11-10`, 체크아웃 `2026-11-13`의 플랫폼 확정 관측 한 건입니다. 관측 시각은 기존 시연 예약 생성 시각과 같은 `2026-11-09T09:00:00+09:00`입니다. 별도 과거 장애·기한 초과·판매 조회를 꾸며 넣지 않습니다.

여섯 특성은 기존 순서대로 `(0.05, 1, 0, 0, 0, false)`입니다. `0.05`는 이력이 없을 때의 가상 사전값입니다. 나머지 무관측 비율 0은 정상 이행 확인이 아닙니다. 정확히 cutoff의 확정은 대상 수에 포함되며, 유입 증가의 반개구간 `[t-7일,t)`에는 포함되지 않아 증가율은 0입니다. 동결 모델·기본 F1 정책에서는 이 예제가 선별되지 않습니다. 선별을 강제하려고 특성이나 이력을 변경하지 않습니다.

이 예제는 입력 생성과 Spring 연결 범위를 확인합니다. 실제 호텔 예측 정확도나 Recall 목표 달성을 입증하는 자료가 아니며, 위험 점수로 객실 미제공을 확정하지 않습니다. Spring 실행 절차는 [백엔드 API 문서](../../backend/docs/api/README.md)를 따릅니다.

전용 검증 명령은 `ml` 폴더에서 다음과 같습니다. CLI 실행, 새 출력 보호, 호텔·자료형 검증, 빈 대상 거부, 과거 관측 계산, 미래 정보·truth 배제, 대상 코드·추적 해시를 확인합니다. 모델·HTTP·DB 검증이나 재학습은 수행하지 않습니다.

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -p test_risk_projection.py -v
```
