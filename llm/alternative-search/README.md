# 대체 숙소 자동 탐색

상담사 시연 화면의 **대체 숙소 자동 탐색** 버튼은 FastAPI의
`POST /api/v1/alternative-search/auto-search`를 호출합니다. Airbnb 후보, 점수와
확인 상태를 원예약 전체 기간 기준으로 점수순 최대 5개 반환합니다. 후보가 부족하면 기간을 유지하며
반경을 넓힙니다. 화면은 최대 3개의 Airbnb 후보를 검토 대상으로 선택할 수 있습니다.
Spring은 상위 5개 상세 결과를 `jobs.result_payload`에 저장합니다. 사건을 다시 열면 현재
예약과 날짜·인원이 일치하는 가장 최근 완료 결과를 불러옵니다. 이전 버전에서 상위 3개만
저장한 검색은 해당 3개가 복원되며 전체 5개를 확인하려면 새 검색을 실행합니다.

선택한 3개 후보의 이름·총액·링크·요약은 저장된 검색에서 읽어 고객 채팅 메시지로 보냅니다.
Spring은 같은 트랜잭션에서 `case_actions`에 `AIRBNB_GUIDE_SENT` 기록을 저장합니다.
기본 안내는 숙소 링크를 전송합니다. 정식 예약 제안은 기존 공급사 견적 흐름을 사용합니다.

### 로컬 예약 시연

전용 Spring을 시작하기 전에 PowerShell에서 `$env:AIRBNB_DEMO_BOOKING_ENABLED = "true"`를 설정합니다.
상담사 프록시에는 기존 CSRO_AIRBNB_DEMO_PROXY=true 및 CSRO_CHAT_DEMO_PROXY=true를 사용합니다.
같은 사건에서 정식 활성 제안이나 예약 실행 기록이 없는 경우, Airbnb 후보 3곳 안내 후 5초 뒤 첫 후보가 고객 선택으로 저장됩니다.
상담사 채팅의 **예약 진행**은 전용 업무 DB에만 시연 예약 한 건과 숙박일을 저장합니다. 숙소는 안내 시점에 등록하며 실행은 AIRBNB_DEMO로 기록합니다.
이 기능을 사용한 사건은 시연 예약 검증용으로 사용하고 실제 공급사 예약은 기존 공급사 견적 흐름에서 별도 사건으로 확인합니다.

## 실행

Python 3.11 이상과 Node.js의 `npx`가 필요합니다. PowerShell에서
`CSRO-overnight-agent` 루트로 이동한 뒤 실행합니다.

```powershell
python -m pip install -r llm/alternative-search/requirements.txt
python -m uvicorn llm.alternative-search.app:app --host 127.0.0.1 --port 18025 --no-proxy-headers
```

Google Maps 위치 조회에는 서버 환경변수 `GOOGLE_MAPS_API_KEY`가 필요합니다.
OpenAI 후보 검토와 요약에는 `OPENAI_API_KEY`를 사용하며 `OPENAI_MODEL`은 선택입니다.
Airbnb 조회는 `npx`로 MCP 서버를 실행합니다. 키는 FastAPI를 시작하는 터미널에 설정합니다.

프런트 빌드와 상담사 프록시 실행에 사용하는 터미널에서 다음 값을 설정합니다.

```powershell
$env:NEXT_PUBLIC_AI_SEARCH_API_URL = 'http://127.0.0.1:18025'
$env:CSRO_AIRBNB_DEMO_PROXY = 'true'
$env:CSRO_CHAT_DEMO_PROXY = 'true'
```

`NEXT_PUBLIC_AI_SEARCH_API_URL`을 바꾼 경우 프런트를 다시 빌드합니다.
18025는 기존 API-024/025의 기본 8000과 충돌하지 않도록 선택했습니다.
8000이 비어 있으면 FastAPI를 8000으로 실행하고 프런트 기본 주소를 사용할 수 있습니다.

[상태 확인](http://127.0.0.1:18025/health) 후
[상담사 화면](http://127.0.0.1:3100/cases?case=C-001)의 대체 숙소 탭에서 실행합니다.
검색 저장은 기존 상담사 프록시와 Spring을 사용합니다. 화면에 표시된
2026-11-10~13 일정은 이미 지나므로 실제 후보 조회는 체크인 날짜가 지나지 않은
시연 사건에서 확인합니다.
