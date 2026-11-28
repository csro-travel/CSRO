# 시연 1·3용 예약 시드와 합성 관측

[Notion E2E](https://app.notion.com/p/414e04d22bed81d0b4fffacbfcddc64f)의 시연 1(점검했지만 문제 없는 예약)과 시연 3(현장 신고 뒤 호텔 무응답)을 실행할 예약이 기본 시드에 없어서 추가한다. 모든 예약·관측·회신은 시연용 가상 데이터다. Flyway·테이블·HTTP 계약·모델·임계값은 바꾸지 않는다.

## 추가한 것

| 파일 | 내용 |
| --- | --- |
| [`backend/tools/seed-demo-scenarios.sql`](../../tools/seed-demo-scenarios.sql) | 예약만 넣는 수동 시드. 사건·근거·위험 평가·숙박일 상태는 만들지 않는다 |
| [`ml/examples/risk-events-demo-hotel-002.json`](../../../ml/examples/risk-events-demo-hotel-002.json) | HOTEL-002의 합성 관측 |
| [`ml/config/risk_automation_sources_demo_v1.json`](../../../ml/config/risk_automation_sources_demo_v1.json) | HOTEL-001·HOTEL-002를 읽는 시연용 catalog. 기본 catalog는 그대로다 |
| `mock-server/app/fixtures.py` | `SUP-BOOK-101`~`109`(YB-101~109, HOTEL-002)의 전 숙박일 가능 회신 |

| 예약 | 호텔 | 예약 시각(KST) | Mock 회신 | 쓰임 |
| --- | --- | --- | --- | --- |
| YB-002, YB-101~109 (10건) | HOTEL-002 서귀포 가든 호텔 | 11-06 10:00 | 공급사 확정, 호텔 3박 모두 가능 | 시연 1 |
| YB-004 | HOTEL-004 제주 하버 호텔 | 11-09 15:00 | 공급사 확정, 호텔 202 응답 대기 | 시연 3 |

- **왜 10건인가:** 현재 모델은 같은 호텔·날짜의 확정 예약이 1~3건이면 다른 특징값이 어떻든 약 0.161을 넘지 않아 기본 정책(`F1_BASELINE`, 임계값 0.2234)에서 선별되지 않는다. 10건과 아래 관측에서는 0.3440이다. 입력을 고쳐 Notion 예시의 0.78에 맞추지는 않았다.
- **HOTEL-002 합성 관측의 특징값:** 과거 90일 실패율 0.1429(과거 8건 중 실패 3건), 최근 7일 예약 증가율 2.667, 재확인 기한 초과율 0.5(4건 중 2건), 재고 불가 관측 비율 0.5(4회 중 2회), 성수기 아님.
- **선별되면 그 호텔의 예약 전부를 재확인한다.** 예약 수는 관측과 업무 DB가 같아야 하므로 DB에도 10건을 넣고 Mock에 10건의 회신을 둔다.
- **YB-004는 위험 점검 기준 시각(D-1 09:00 KST) 뒤에 예약된 것으로 넣는다.** 자동 점검 대상에서 빠지고 고객 신고로만 접수된다.
- **기존 채팅 시연 시드와 함께 쓸 수 없다.** [`seed-chat-demo-case.sql`](../../tools/seed-chat-demo-case.sql)이 `YB-002`를 다른 호텔·날짜로 쓴다. Mock 공급사는 `SUP-BOOK-002`를 `YB-002`로 답하고 Spring이 이 번호를 대조하므로 번호를 바꿀 수 없다. 충돌하면 시드가 아무것도 넣지 않고 중단한다.

## 실행

전용 `local-chat-demo` DB와 서버 준비는 [대체 예약 시연](REPLACEMENT-DEMO.md)을 따른다. Spring이 기본 시드를 적용한 뒤 이 시드를 넣는다. 다시 실행해도 같은 행을 중복해 넣지 않는다. Mock 서버는 이 변경을 받은 코드로 다시 시작해야 새 회신을 준다.

```powershell
docker cp backend/tools/seed-demo-scenarios.sql csro-local-chat-demo-db:/tmp/csro-seed-demo-scenarios.sql
docker exec csro-local-chat-demo-db psql -X -U yb_chat_demo -d yeohaengbom_chat_demo -f /tmp/csro-seed-demo-scenarios.sql
```

시연 1은 [자동 점검 배치](ML-RISK-AUTOMATION.md)의 준비를 그대로 쓰고 catalog만 지정한다.

```powershell
Set-Location .\ml
.\.venv\Scripts\python.exe -m travel_ml.risk_automation --sources config\risk_automation_sources_demo_v1.json --target-check-in-date 2026-11-10
```

- 기본 정책: HOTEL-002만 선별되어 10건을 재확인하고 사건은 생기지 않는다. HOTEL-001(0.0202)은 선별되지 않는다.
- `--policy MAX_OBSERVED_RECALL`: HOTEL-001도 선별되어 YB-001의 첫날 제공 불가가 확인되고 C-001이 생긴다. 한 번의 실행으로 "11건 확인, 상담사에게 올라간 사건 1건"이 된다.

시연 3은 고객 화면 `/customer/bookings/YB-004`에서 신고한다. [API-002](API-002.md)의 재확인 작업이 30초 간격으로 3회 시도한 뒤 실패로 끝나고 `VERIFY_HOTEL` 업무가 생긴다.

## 검증

2026-11-26 Windows에서 새 tmpfs PostgreSQL과 이 브랜치의 Mock, 실제 ML 서버, Spring JAR로 실행했다.

| 실행 | 결과 |
| --- | --- |
| 시드 2회 적용 | HOTEL-002 10건, HOTEL-004 1건, 숙박일 36행. 두 번째 실행에서 변화 없음 |
| 기본 정책 배치 | HOTEL-002 `rawRiskScore=0.3440`·선별, 재확인 10건 `COMPLETED`, 근거 20건, 10건 모두 3박 `AVAILABLE/VERIFIED`, 사건 0건·업무 0건. YB-004는 평가 대상에 없음 |
| 이어서 `MAX_OBSERVED_RECALL` | 평가 2건 재사용, 재확인 합계 11건, 사건은 C-001(YB-001) 1건 |
| 기존 검사 | `ml` `test_risk_automation.py` 15개, `mock-server` 55개(건너뜀 10) 통과 |

Spring이 같은 관측에서 계산한 특징값이 Python 기준과 일치해 추론까지 진행된 것도 위 실행에 포함된다.

**2026-11-27 추가 확인:** 새 시연 DB(`local-chat-demo`)에 이 시드를 적용하고 브라우저로 시연 3을 진행했다. YB-004 신고로 사건이 생기고, 재확인이 호텔 단계에서 202 응답을 3회 받은 뒤 `ATTEMPTS_EXHAUSTED`로 끝나 `VERIFY_HOTEL` 업무가 생겼다. 상담사가 호텔 통화 결과를 "확인 안 됨"으로 기록하자 사건이 확인 대기(WAITING)로 바뀌었다. 이 경로에서는 공급사 조회 근거가 저장되지 않는다(근거는 공급사·호텔 두 응답이 모두 검증될 때 함께 저장된다).

**미검증:** 후속 기한 설정과 대체 후보 사전 준비는 구현돼 있지 않아 시연 3 원문의 해당 두 단계는 실행하지 않았다.

## 상담 대응 목록용 예약 (2026-11-28 추가)

상담 대응 목록에 진행 중인 건이 여러 개 보이도록 예약 10건을 더 넣는다. 이 시드도 예약만 넣으며 사건·근거·숙박일 상태는 만들지 않는다. 사건은 위험 점검 배치와 고객 신고를 거쳐야 생긴다. Mock 응답 유형과 서버 로직은 바꾸지 않고 `fixtures.py`의 예약 대응표만 늘렸다.

| 파일 | 내용 |
| --- | --- |
| [`backend/tools/seed-demo-queue.sql`](../../tools/seed-demo-queue.sql) | 예약 10건. `seed-demo-scenarios.sql` 뒤에 같은 방법으로 실행한다 |
| `ml/examples/risk-events-demo-hotel-201.json`~`205.json`, `ml/config/risk_automation_sources_demo_v1.json` | HOTEL-201~205의 관측(HOTEL-001의 콜드 스타트 관측과 같은 형태)과 catalog 항목 |
| `mock-server/app/fixtures.py` | `SUP-BOOK-201`~`205`(첫날 제공 불가), `SUP-BOOK-301`~`303`(호텔 응답 대기) |

| 예약 | 호텔 | Mock 회신 | 사건이 생기는 경로 |
| --- | --- | --- | --- |
| YB-201~205 | HOTEL-201~205 (호텔마다 1건) | 공급사 확정, 호텔 첫날 제공 불가·나머지 미확인 | `--policy MAX_OBSERVED_RECALL` 배치의 재확인 |
| YB-301~303 | HOTEL-301~303 | 공급사 확정, 호텔 202 응답 대기 | 고객 신고 |
| YB-003 | HOTEL-003 | 호텔에서 예약을 찾지 못함(404) | 고객 신고 |
| YB-005 | HOTEL-005 | 공급사·호텔 서비스 사용 불가(503) | 고객 신고 |

- 예약은 모두 `demo-customer-001` 소유다. 고객 시연 프록시가 이 고객으로 접속하기 때문이다.
- YB-201~205는 기본 정책(`F1_BASELINE`)에서는 선별되지 않는다. HOTEL-001과 같은 점수(0.0202)다.
- **대체 숙소 견적은 나오지 않는다.** Mock의 대체 숙소 재고가 HOTEL-001 기준으로만 준비돼 있어 HOTEL-201~205에서 검색하면 "조건에 맞는 견적 없음"으로 끝난다. 제안·선택·대체 예약은 지금처럼 C-001(YB-001)에서만 시연할 수 있다.

2026-11-28 새 시연 DB에서 실행한 결과: 배치 1회(`MAX_OBSERVED_RECALL`)로 7개 호텔이 선별돼 재확인 16건, 사건 6건(C-001과 YB-201~205). 고객 화면 프록시로 5건을 신고해 사건 5건이 더 생겼다. YB-003은 첫 시도에 `NOT_FOUND`로 자동 확인이 끝나 `VERIFY_HOTEL` 업무가 생겼다. Mock 단위 검사 55개(건너뜀 10) 통과.
