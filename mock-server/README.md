# 여행봄 Mock 공급사·호텔 서버 v0.1

API-028 결정적 대체 숙소 검색을 추가했습니다. [요청 예제·필터·구현 경계](docs/API-028.md)를 참고하세요. 이 검색은 견적만 반환하며 예약·재고 확보를 수행하지 않습니다.

API-029/030의 [선택적 상태 저장 공급사 모드](docs/API-029-030.md)를 추가했습니다. 명시적으로 켠 경우에만 별도 PostgreSQL에서 발급 견적·숙박일 재고·예약 결과를 보존합니다. 아래 기존 기본 실행과 API-026/027 fixture는 그대로이며, Spring 업무 DB에 접속하지 않습니다.

A팀 Spring이 호출할 **API-026 공급사 기존 예약 조회**와 **API-027 호텔 숙박일별 재확인 요청**을 제공하는 개발용 FastAPI 서버입니다. YB-001의 공급사 계약은 `CONFIRMED`이지만 호텔 첫날 회신은 `UNAVAILABLE`인 핵심 사례를 재현합니다.

기본 모드는 고정된 가상 fixture를 응답합니다. PostgreSQL 연결·테이블 생성·더미 데이터 대량 적재·ML 학습·Spring 업무 DB 갱신은 수행하지 않습니다. 기존 `backend`, Flyway, YB-001 DB seed를 바꾸지 않고 독립적으로 실행합니다. 예약 상태 저장은 별도 문서의 opt-in 실행에서만 제공합니다.

## 1. 폴더 위치

압축파일의 **`mock-server` 폴더만** CSRO 프로젝트 최상위에 복사합니다.

```text
C:\Users\cy102\Downloads\CSRO\
├─ backend\
├─ frontend\
├─ llm\
├─ tools\                    # 기존 데이터 생성기 작업이 있다면 그대로 유지
└─ mock-server\              # 이번에 추가할 폴더
   ├─ app\
   ├─ tests\
   ├─ docs\
   ├─ requirements.txt
   └─ README.md
```

전달받은 파일을 직접 복사해야 내 Windows PC에 반영됩니다. 기존 폴더나 프로젝트 루트 `.env`를 덮어쓸 필요는 없습니다.

## 2. 설치 — Windows PowerShell

Python 3.13을 설치한 현재 환경에서는 `python` 대신 **`py -3.13`**을 사용합니다. VS Code 터미널에서 실행하세요.

```powershell
cd C:\Users\cy102\Downloads\CSRO\mock-server
py -3.13 --version
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

가상환경의 Python을 직접 호출하므로 가상환경 활성화 명령이나 PowerShell 실행 정책 변경은 필요하지 않습니다. 이 서버를 실행하기 위해 Docker·PostgreSQL을 추가로 설치하거나 켤 필요도 없습니다.

## 3. 서버 실행 — 첫 번째 터미널

```powershell
cd C:\Users\cy102\Downloads\CSRO\mock-server
$env:MOCK_SERVICE_TOKEN = 'csro-local-mock-token'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

서버가 실행되면 이 터미널은 계속 사용 중인 상태로 남습니다. 정상 동작이며, API 호출은 **VS Code에서 새 터미널을 열어** 실행합니다. 서버를 종료할 때는 첫 번째 터미널에서 `Ctrl+C`를 누릅니다.

`csro-local-mock-token`은 공개된 **로컬 개발용 예시 토큰**입니다. 환경 변수를 지정하지 않았을 때도 같은 개발용 기본값을 사용합니다. 실제 서비스 인증이나 사용자 로그인·권한 검증을 대신하는 설정은 아닙니다. `.env.example`은 설정 이름을 공유하는 참고 파일이며, 기본 실행에 복사할 필요는 없습니다.

## 4. 가장 먼저 확인할 것 — 두 번째 터미널

첫 번째 서버 터미널을 그대로 두고 새 PowerShell 터미널을 엽니다.

### 서버 상태

```powershell
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8001/healthz'
```

`status=ok`, `service=travel-mock`, `contractVersion=0.1`, `isSynthetic=true`가 반환됩니다.

### API-026 — 공급사 기존 예약 조회

아래 헤더 변수는 새 터미널에서 다시 만듭니다.

```powershell
$mockHeaders = @{ Authorization = 'Bearer csro-local-mock-token' }

Invoke-RestMethod -Method Get `
  -Uri 'http://127.0.0.1:8001/mock-supplier/v1/bookings/SUP-BOOK-001' `
  -Headers $mockHeaders | ConvertTo-Json -Depth 10
```

주요 확인 값은 다음과 같습니다.

```json
{
  "supplierBookingId": "SUP-BOOK-001",
  "referenceBookingId": "YB-001",
  "propertyId": "HOTEL-001",
  "contractStatus": "CONFIRMED",
  "stay": {
    "checkInDate": "2026-11-10",
    "checkOutDate": "2026-11-13",
    "adults": 2,
    "rooms": 1
  },
  "updatedAt": "2026-11-09T09:00:00+09:00"
}
```

이는 공급사 계약상 예약이 등록·확정되어 있다는 응답입니다. 호텔이 모든 숙박일에 객실을 제공한다는 응답은 아닙니다.

### API-027 — 호텔의 3박 재확인

```powershell
$mockReconfirmation = @{
    reconfirmationRequestId = 'RECHECK-001'
    supplierBookingId = 'SUP-BOOK-001'
    propertyId = 'HOTEL-001'
    stayDates = @('2026-11-10', '2026-11-11', '2026-11-12')
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri 'http://127.0.0.1:8001/mock-hotel/v1/reconfirmations' `
  -Headers $mockHeaders `
  -ContentType 'application/json' `
  -Body $mockReconfirmation | ConvertTo-Json -Depth 10
```

YB-001은 HTTP `200`, `status=RESPONDED`이며, 각 날짜의 `reportedProvisionStatus`는 다음과 같습니다.

| 숙박일 | 호텔 회신 |
| --- | --- |
| `2026-11-10` | `UNAVAILABLE` |
| `2026-11-11` | `UNKNOWN` |
| `2026-11-12` | `UNKNOWN` |

**HTTP 200은 회신을 정상적으로 받았다는 뜻입니다. 전체 숙박일이 정상 제공된다는 뜻이 아닙니다.** 체크아웃 날짜인 `2026-11-13`은 요청 숙박일에 넣지 않습니다.

## 5. Swagger로 호출하기

서버를 실행한 상태에서 브라우저로 다음 주소를 엽니다.

```text
http://127.0.0.1:8001/docs
```

1. 오른쪽 위 **Authorize**를 누릅니다.
2. 토큰 값에 `csro-local-mock-token`만 입력합니다. `Bearer ` 접두사는 직접 붙이지 않습니다.
3. API를 펼치고 **Try it out**을 누릅니다.
4. 위 예제의 ID와 JSON을 넣고 **Execute**를 누릅니다.

`/docs`는 사용법·실행 화면이고, Spring이 호출할 주소는 `/mock-supplier/...`와 `/mock-hotel/...`입니다. Swagger에서의 수동 호출 성공은 Spring 연동 완료를 뜻하지 않습니다.

## 6. 제공하는 가상 사례

YB-001의 본문은 API 명세의 예제를 기준으로 합니다. YB-002~005는 분기 개발을 돕기 위해 추가한 구현용 fixture이며, 실제 프로젝트의 예약 데이터가 아닙니다. 상세 매핑과 해석은 [팀 인계 문서](docs/HANDOFF.md)를 확인하세요.

| 내부 예약 | API-026 공급사 | API-027 호텔 | 확인할 분기 |
| --- | --- | --- | --- |
| YB-001 | `200`, `CONFIRMED` | `200`, 첫날 불가·나머지 미확인 | 계약 상태와 숙박일 주장 분리 |
| YB-002 | `200`, `CONFIRMED` | `200`, 전체 숙박일 가능 | 정상 회신 |
| YB-003 | `200`, `CONFIRMED` | `404` | 호텔에서 예약 확인 실패 |
| YB-004 | `200`, `CONFIRMED` | `202`, `PENDING` | 요청 접수 후 회신 대기 |
| YB-005 | `503` | `503` | 외부 서비스 장애 |
| YB-101~109 | `200`, `CONFIRMED` | `200`, 전체 숙박일 가능 | YB-002와 같은 HOTEL-002의 정상 회신(시연 1의 예약 10건) |

`404`, `202`, `503`을 객실 제공 불가가 검증되었다는 뜻으로 저장하면 안 됩니다. API-027의 `reportedProvisionStatus`도 호텔의 주장입니다. Spring이 예약·숙박일·출처·상반 근거를 검증한 뒤 업무 상태에 반영해야 합니다.

시간은 `2026-11-09` 기준 시연용 고정 시각을 사용합니다. 오늘 날짜가 예약 기간 이후여도 PC 날짜를 바꿀 필요가 없습니다.

## 7. 다른 팀원이 사용할 때

기본 주소 `http://127.0.0.1:8001`은 **실행한 PC 자기 자신**을 가리킵니다. 내가 실행했다고 다른 팀원 PC에도 서버가 생기지는 않습니다. 팀원은 같은 코드를 받아 각자의 PC에서 위 명령으로 실행할 수 있습니다.

이번 기본 실행은 `127.0.0.1`에만 바인딩합니다. 공통 서버 주소·배포·네트워크 공개·실제 인증은 별도 통합 작업으로 남깁니다. Spring에 연결할 때는 HTTP base URL과 서비스 토큰을 설정으로 전달하고, 코드에 개인 비밀번호를 넣지 않습니다.

## 8. 자동 테스트

서버 터미널과 별개의 터미널에서 실행합니다. 계약 검사는 앱을 메모리 내에서 호출하고, 실제 HTTP 검사는 임시 로컬 포트에 Uvicorn을 자동으로 띄웠다가 종료합니다. 이미 실행 중인 8001 서버나 DB는 필요하지 않습니다.

```powershell
cd C:\Users\cy102\Downloads\CSRO\mock-server
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

이 테스트는 HTTP 상태·JSON 계약을 검사합니다. Spring 원문 저장·사건 생성·사용자 로그인까지 검증하는 E2E 테스트가 아닙니다.

직접 켜 둔 8001 서버의 5개 시나리오를 확인하려면 다음 명령을 사용할 수 있습니다. 토큰을 바꿨다면 이 터미널에도 동일한 `MOCK_SERVICE_TOKEN`을 설정합니다.

```powershell
.\.venv\Scripts\python.exe scripts\check_server.py
```

전달본의 실제 검사 결과는 [검증 기록](docs/VERIFICATION.md)에 있습니다.

## 9. 공유 범위와 구현 출처

이번 공유 대상은 `mock-server` 폴더입니다. 기존 통합 더미 데이터 생성기 작업은 별도 범위로 유지하며, 자동으로 커밋·푸시·병합하지 않습니다. 작업 브랜치는 `git branch --show-current`로 `1118cy`인지 확인할 수 있습니다. `.venv`, Python 캐시, 로컬 설정 파일이 무심코 포함되지 않도록 `git status`를 확인하세요.

- [API-026 가상 공급사 기존 예약 조회 — 계약 v0.1 검토안](https://app.notion.com/p/3542f2597c57826e9b9881d07057b57e)
- [API-027 가상 호텔 숙박일별 재확인 요청 — 계약 v0.1 검토안](https://app.notion.com/p/77e2f2597c578365bd3c015167a69602)
- [팀 인계 문서: 연결 범위·fixture·남은 작업](docs/HANDOFF.md)

두 API 문서는 설계 상태가 ‘진행 중’인 계약 검토안입니다. 구현상 추가한 fixture와 처리 선택을 해당 설계가 모두 확정한 규칙으로 설명하지 않습니다. 실제 검증 결과는 함께 제공되는 실행 기록의 환경·범위를 기준으로 확인합니다.
