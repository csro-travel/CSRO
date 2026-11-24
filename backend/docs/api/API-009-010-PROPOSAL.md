# API-009/010 최소 계약 — 사용자 승인 범위

사용자가 이 문서의 세 항목대로 구현하도록 승인했다. 팀 전체의 Notion 승인 상태를 변경한 것은 아니다. Notion의 [API-009](https://app.notion.com/p/c022f2597c5783a18ac8818dfd4a4bed), [API-010](https://app.notion.com/p/1142f2597c578336839f8132cf5a96ca)은 URI·목적만 등록돼 있고 상세 요청·응답은 미작성 상태다. 기존 API-001/003/004/011/012, ML 모델, Flyway V1은 변경하지 않는다.

## 1. 상담사 견적 조회와 검색 접수

두 API 모두 기존 Servlet Principal을 활성 COUNSELOR와 대조한다. 고객 역할은 403, 주체 없음은 401, 사건 없음은 404다. 외부 입력의 고객 ID·호텔·기간을 신뢰해 다른 예약을 검색하지 않는다.

`POST /api/v1/cases/{caseId}/quote-searches`는 JSON 객체를 받는다. 선택 필드는 `radiusMeters`(기본 3000, 1~20000), `maxAverageNightlyAmount`(생략 또는 1 이상 정수)다. 그 외 필드는 거부한다. 기간·인원·객실 수·원래 호텔은 Spring이 해당 사건의 예약에서 읽는다.

```json
{"radiusMeters":3000,"maxAverageNightlyAmount":300000}
```

`Authorization: Bearer ...`, `Idempotency-Key: ...`, `Content-Type: application/json`을 사용한다. 키는 영문·숫자와 `._:-` 중 1~100자다. 같은 사건·동일 키·동일 정규화 본문은 최초 작업을 재사용하며, 사건이 이후 종료돼도 권한 검증 후 기존 접수 결과를 재생한다. 다른 본문은 409 `IDEMPOTENCY_KEY_REUSED`, 다른 키로 진행 중 검색과 충돌하면 409 `QUOTE_SEARCH_IN_PROGRESS`, 종료 사건의 새 요청은 409 `CASE_CLOSED`다. 최초 접수 및 같은 요청 재시도는 아래 접수 스냅샷을 202로 반환한다.

```json
{"caseId":"C-029","searchJobId":"QS-example","status":"PENDING"}
```

202는 접수이며 검색 완료가 아니다. 기존 jobs의 QUOTE_SEARCH로 저장한 뒤 별도 실행자가 처리한다. 작업 선점·재시도는 실제 처리 시각을 사용하며 외부 HTTP 중에는 DB 행 잠금을 유지하지 않는다.

`GET /api/v1/cases/{caseId}/quotes`는 조회만 수행한다. 본문·페이지네이션은 없다. 검색 전은 `searchJob=null`, 검색 완료 0건은 `status=COMPLETED`와 빈 견적, 실패는 `status=FAILED`와 오류 코드로 구분한다.

```json
{
  "caseId":"C-029",
  "effectiveAt":"2026-11-09T10:20:00+09:00",
  "isSynthetic":true,
  "clockMode":"SYNTHETIC_DEMO",
  "searchJob":{"searchJobId":"QS-example","status":"COMPLETED","errorCode":null},
  "groups":[{
    "coverageMode":"FULL_STAY",
    "stay":{"checkInDate":"2026-11-10","checkOutDate":"2026-11-13","adults":2,"rooms":1},
    "remainingUnconfirmedStayDates":["2026-11-11","2026-11-12"],
    "rankingAvailable":true,
    "quotes":[{
      "quoteId":"QUOTE-A-search-example",
      "propertyName":"A호텔","roomName":"디럭스 더블",
      "distanceMeters":800,"totalAmount":480000,"currency":"KRW",
      "priceDifference":30000,
      "checkedAt":"2026-11-09T10:15:00+09:00",
      "expiresAt":"2026-11-09T10:25:00+09:00",
      "reason":"기존 호텔에서 가장 가까워 이동 거리가 짧습니다."
    }],
    "warnings":["견적 조회는 예약 확정이 아닙니다."]
  }]
}
```

실제 기준 응답에는 A/B/C 세 후보가 들어간다. `effectiveAt`은 SYNTHETIC_DEMO에서 작업에 저장된 가상 판단 시각, REAL_TIME에서 실제 조회 시각이며 검색 전에는 null이다. `clockMode`는 REAL_TIME 또는 SYNTHETIC_DEMO다. `isSynthetic=true`와 이 시각을 화면에 표시한다. 선택 필드 `expired`는 이 기준 시각에서 만료됐는지를 나타내며 `true`이면 새 검색 필요를 표시한다. `priceDifference`는 같은 원예약 전체 기간의 동결된 총액과 비교할 때만 정수이며 부분 기간에는 null이다. 실제 추가 결제·환급액으로 표현하지 않는다. 원예약의 미확인 날짜는 대체 견적이 있다는 이유로 사실 상태를 바꾸지 않는다.

전체 기간 검색이 정상 완료됐으나 0건일 때만 VERIFIED+UNAVAILABLE 숙박일의 연속 구간별로 다시 검색한다. 각 구간은 별도 groups 항목이며 `coverageMode=VERIFIED_FAILURE_ONLY`다. Agent 장애 시 검증된 원본 견적을 보존하고 `rankingAvailable=false`·주의사항을 표시한다.

공통 오류는 `application/problem+json`의 `type,title,status,detail,code,traceId`를 사용한다. 잘못된 caseId는 400 `INVALID_CASE_ID`, 요청 JSON·타입·범위·키는 400 `INVALID_QUOTE_SEARCH_REQUEST`, 인증 401 `UNAUTHENTICATED`, 고객 역할 403 `FORBIDDEN_ROLE`, 미존재 404 `CASE_NOT_FOUND`, 비 JSON 본문은 415 `UNSUPPORTED_MEDIA_TYPE`다. 조회·접수 단계 내부 오류는 500 `INTERNAL_ERROR`; 접수 이후 공급사·Agent 오류는 작업 결과에서 확인한다.

## 2. 가상 견적 ID의 검색별 구분

현재 Mock은 매번 `QUOTE-A/B/C`를 반환하지만 업무 DB의 quote_code는 전역 UNIQUE이며 한 견적은 하나의 사건·검색에 속한다. 서로 다른 검색에서 같은 ID를 그대로 저장하면 충돌한다.

Notion의 정확한 `SEARCH-001` 예제 조건·가격・조회 시각은 기존 `QUOTE-A/B/C`를 유지하고, 그 외 검색에는 요청 ID·기간·인원·상품·가격·조회 시각을 포함한 결정적 접미사를 붙인다. 같은 요청의 재시도는 같은 견적 ID를 반환하고 새 검색은 새 스냅샷 ID를 반환한다. 새 ID를 API-025·고객 제안·선택·API-029까지 그대로 사용한다. JSON 필드·경로·기준 가격은 유지한다. 추적용 searchRequestId가 예약 멱등 키가 되지는 않는다.

대안은 Spring 내부에서 업무 ID와 공급사 ID를 나누는 어댑터다. 이 경우 명세의 ‘동일 견적 ID’ 의미와 API-029 매핑이 복잡해지므로 이번 시연에서는 공급사에서 검색 스냅샷 ID를 구분하는 안을 제안한다. 적용 시 Mock의 관련 테스트·문서만 함께 수정한다.

## 3. 격리 시연의 조회·검색 허용

일반 프로필의 인증은 그대로 유지한다. 기존 `local-chat-demo`의 전용 DB·loopback·독점 프로필·서버 토큰 검증을 유지하면서 정확한 GET quotes 및 POST quote-searches 경로만 추가한다. 브라우저 프록시는 별도 `CSRO_QUOTE_DEMO_PROXY=true`를 명시한 3100→18082에서만 검색 POST를 허용하고 기존 Host·Origin·Sec-Fetch 검사를 유지한다. 브라우저 Authorization/subject 헤더를 신뢰하지 않는다.

합성 fixture는 과거 날짜이므로 이 격리 프로필에서만 선택적으로 `quote.search.demo-as-of=2026-11-09T10:20:00+09:00`을 명시한다. 이 값은 견적 유효성 판단에만 쓰며 Job lease·재시도는 실제 시각을 사용한다. 일반 환경에서는 실제 시계를 사용하고, 공급사 checkedAt이나 PC 날짜를 조용히 수정하지 않는다. 시연 화면에는 가상 시각과 합성 견적임을 표시한다.

이 프로필의 명시적 검색 Worker 옵션은 단일 실행자·제한된 한 번의 처리량으로 접수된 QUOTE_SEARCH만 처리한다. 기존 위험 점검·채팅 실행의 기본값은 바꾸지 않는다. 고객 선택·가상 예약 실행은 이번 경로에 추가하지 않는다.

## 검증 계획

전용 격리 DB의 이름·사용자를 Spring/Flyway 실행 전에 확인한다. 동일 키 동시 요청 1건, 검색별 견적 ID 충돌 방지, 전체 기간 우선·확인된 장애일만 fallback, Agent 실패 시 원본 보존, 외부 HTTP 중 잠금 해제, 이전 실행권의 늦은 응답 차단, 권한·조회 비변경성, 사건 전환·0건·실패 표시를 검증한다. 적용된 V1·기존 업무 DB·기준 모델을 변경하거나 초기화하지 않는다.
