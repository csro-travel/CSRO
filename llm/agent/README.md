# API-024 호텔 회신 추출기

같은 서버에서 API-025 `POST /internal/v1/alternative-rankings`도 제공합니다. [가상 견적 비교의 입력·규칙·구현 경계](docs/API-025.md)를 참고하세요. API-024 실행 주소·인증·추출 계약은 그대로 유지합니다.

Agent는 호텔 회신에서 숙박일별 객실 제공 여부에 관한 주장과 원문 인용을 추출한다. Spring은 반환된 주장과 근거를 검증하고 업무 DB에 저장한다. [Notion API-024 상세 명세](https://app.notion.com/p/f366adbd23b38282abc181a14575ff95)

## 상담사 안내 초안

같은 서버의 `POST /internal/v1/counselor-assist`는 상담 건의 구조화된 사실로 다음 조치·고객 안내 초안(`GUIDANCE`) 또는 종료 요약(`CLOSURE_SUMMARY`)을 제안한다. 인증과 오류 형식은 위 두 엔드포인트와 같다. 제안일 뿐이며 업무 상태를 바꾸지 않는다. 계약은 [안내 초안 제안서](../../backend/docs/api/ASSIST-DRAFT-PROPOSAL.md)를 따른다.

| 환경변수 | 뜻 |
| --- | --- |
| `OPENAI_API_KEY` | 있으면 언어 모델을 한 번 호출한다. 프로세스 환경에서만 읽으며 파일·로그에 남기지 않는다 |
| `ASSIST_LLM_MODEL` | 모델명. 기본 `gpt-4o-mini` |

- 키가 없으면 규칙 기반으로 동작한다. 호출이나 응답 검증에 실패해도 같은 형식의 규칙 기반 결과를 반환한다(`source: "RULES"`).
- 고객 신고 원문은 보내지 않고 구조화된 사실만 보낸다. 요청의 알 수 없는 필드는 거부한다.

## Windows PowerShell에서 실행

CSRO 저장소 루트에서 아래 명령을 실행한다. 새 가상환경을 만들 때는 PowerShell에서 `python`을 실행할 수 있어야 한다. 검증에는 Python 3.14.7을 사용했다.

```powershell
if (-not (Test-Path -LiteralPath .\.venv\Scripts\python.exe)) {
    python -m venv .venv
}
.\.venv\Scripts\python.exe --version
.\.venv\Scripts\python.exe -m pip install -r .\llm\agent\requirements.txt

Set-Location .\llm\agent
$env:AGENT_SERVICE_TOKEN = [Guid]::NewGuid().ToString('N')
Set-Clipboard -Value $env:AGENT_SERVICE_TOKEN
..\..\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

## Swagger에서 재현

1. [Swagger UI](http://127.0.0.1:8000/docs)를 열고 **Authorize**의 **Value** 칸에 클립보드의 개발용 토큰을 붙여 넣는다. Swagger가 `Authorization: Bearer` 헤더를 만든다.
2. `POST /internal/v1/hotel-reply-extractions`를 열고 **Try it out**을 선택한다.
3. 요청 본문 전체를 아래 JSON으로 교체하고 **Execute**를 선택한다.

```json
{
  "evidenceId": "EV-002",
  "bookingId": "YB-001",
  "hotelId": "HOTEL-001",
  "stayDates": ["2026-11-10", "2026-11-11", "2026-11-12"],
  "originalMessage": "11월 10일은 객실 제공이 어렵고 11월 11일과 12일은 아직 확인 중입니다.",
  "locale": "ko-KR",
  "messageOccurredAt": "2026-11-09T10:05:00+09:00",
  "hotelTimeZone": "Asia/Seoul"
}
```

기대 응답은 HTTP `200`이다.

```json
{
  "evidenceId": "EV-002",
  "analysisStatus": "EXTRACTED",
  "modelVersion": "hotel-reply-extractor-0.1",
  "claims": [
    {
      "stayDate": "2026-11-10",
      "reportedProvisionStatus": "UNAVAILABLE",
      "supportingText": "11월 10일은 객실 제공이 어렵고"
    }
  ],
  "unresolvedStayDates": ["2026-11-11", "2026-11-12"],
  "requiresHumanVerification": true,
  "warnings": ["11월 11일과 12일의 객실 제공 가능 여부는 확인되지 않았습니다."]
}
```

아래 문장으로 `originalMessage`만 바꿔 정정과 조건부 회신을 재현한다. 네 경우 모두 HTTP `200`을 반환한다.

| 회신 문장 | 기대 결과 |
| --- | --- |
| 11월 10일은 객실 제공이 가능합니다. 정정: 11월 10일은 객실 제공이 불가능합니다. | `AMBIGUOUS`. 10~12일 모두 미확인. |
| 11월 10일은 객실 제공이 가능합니다. 단, 기존 고객이 취소하는 경우에 한합니다. | `AMBIGUOUS`. 10~12일 모두 미확인. |
| 11월 10일은 객실 제공이 불가하고, 나머지는 확인 중입니다. | `EXTRACTED`. 10일은 `UNAVAILABLE`, 11·12일은 미확인. |
| 11월 10일은 객실 제공이 불가능합니다. 11월 11일은 객실 제공이 가능합니다. 단, 11일은 기존 고객의 취소가 필요합니다. | `EXTRACTED`. 10일은 `UNAVAILABLE`, 11·12일은 미확인. |

## 자동 테스트

`llm/agent`에서 실행한다. 테스트는 자체 로컬 포트에서 Uvicorn을 실행해 HTTP 응답을 검사한다.

```powershell
..\..\.venv\Scripts\python.exe -m unittest discover -s tests -v
```
