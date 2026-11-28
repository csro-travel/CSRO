# API-013/014 사용자 승인 최소 구현 계약

[API-013](https://app.notion.com/p/6e12f2597c5783e9ac6b81fd03e48ab2), [API-014](https://app.notion.com/p/b7b2f2597c5782c1861381469be88c86) 원본의 상세 요청·응답 공백을 기존 E2E와 V1의 offers/offer_items/alternative_selections/chat_messages/case_actions에 매핑한 최소 계약이다. 사용자가 이 대화에서 구현을 승인했다. Notion의 팀 전체 설계 승인·진행 상태를 변경한 것은 아니다. V1·일반 인증·ML 모델·Mock 저장소는 변경하지 않는다. [실행·범위](OFFERS-DEMO.md)

## 1. 상담사의 후보 전송 — API-013

`POST /api/v1/cases/{caseId}/offers`, 활성 COUNSELOR만 사용한다.

```json
{"quoteIds":["QUOTE-A-search1","QUOTE-C-search1","QUOTE-B-search1"]}
```

저장된 견적 ID 1~3개를 전송 순서대로 받는다([변경 제안](OFFER-PARTIAL-PROPOSAL.md)으로 정확히 3개에서 완화). 입력의 고객 ID·호텔명·가격·기간·판단 시각은 받지 않는다. 세 ID는 서로 다른 숙소 3곳이고, 같은 사건의 최신 완료 검색에서 같은 기간·인원·객실 수 그룹에 속해야 한다. 공급사 원문·KRW·금액·정원·재고와 서버 판단 시각의 유효성을 검사한다. 전체 기간 우선, 부분 기간은 현재 장애가 검증된 숙박일만 허용한다. 비교 Agent 실패 시에도 검증된 공급사 견적으로 수동 제안할 수 있다.

새 제안, 3개 항목, OFFER 채팅 메시지, 감사 기록을 같은 트랜잭션에 저장한다. 기존 ACTIVE 제안은 SUPERSEDED, 기존 현재 선택은 superseded_at만 기록해 이력으로 남긴다. 견적·발송 항목의 상품·가격은 수정하지 않는다.

성공 및 같은 키 재시도는 최초의 불변 결과를 201로 반환한다.

```json
{"caseId":"C-001","offerId":"OFFER-example","messageId":"MSG-example","sentAt":"2026-11-24T12:00:00Z"}
```

## 2. 고객 본인의 선택 — API-014

`PUT /api/v1/cases/{caseId}/selection`, 활성 CUSTOMER이면서 해당 예약 소유자만 사용한다. 상담사가 고객 대신 선택하면 403이다.

```json
{"offerId":"OFFER-example","quoteId":"QUOTE-A-search1"}
```

현재 ACTIVE 제안의 실제 발송 항목만 선택한다. 미발송 견적·이전 제안·다른 사건·만료 견적은 차단한다. 새 선택은 이전 현재 선택을 이력으로 남기며 현재 선택을 한 건으로 유지한다. 선택·SYSTEM 채팅 알림·감사 기록을 같은 트랜잭션으로 저장한다. 이 알림은 선택 이력이며 예약 확정 알림이 아니다.

성공과 같은 키 재생은 최초 선택 기록을 200으로 반환한다.

```json
{"caseId":"C-001","selectionId":"SEL-example","offerId":"OFFER-example","quoteId":"QUOTE-A-search1","selectedAt":"2026-11-24T12:01:00Z"}
```

새 키는 새로운 선택 의도로 기록한다. 같은 키·같은 본문만 중복 생성을 막는다. 재생된 옛 선택을 현재 선택이라고 추정하지 않고 채팅 조회의 현재 요약을 다시 확인한다. 선택만으로 replacement_requests·입실·사건 종료를 실행하지 않는다.

## 3. 멱등성·동시성·오류

두 API는 `Content-Type: application/json`과 필수 `Idempotency-Key`를 사용한다. 키 형식은 기존 API-010과 같은 영문·숫자·`._:-` 1~100자다. 다른 필드·잘못된 타입은 거부한다.

인증·역할·현재 예약 소유권 확인 → booking→case 잠금 → 같은 키의 본문 해시 확인 → 기존 결과 재생 → 신규 요청의 현재 제안·기한·사건 상태 검증 순서다. 같은 키·다른 본문은 409다. 동일 키는 이후 만료·제안 교체·사건 종료 뒤에도 기록만 재생하며 상태를 되돌리지 않는다. GET에서는 선택·제안을 생성하지 않는다.

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | INVALID_OFFER_REQUEST / INVALID_SELECTION_REQUEST / INVALID_CASE_ID | JSON·키·ID·1~3개 조건 오류 |
| 401 | UNAUTHENTICATED | 인증 주체 없음·비활성 사용자 |
| 403 | FORBIDDEN_ROLE | 상담사/고객 역할 불일치 |
| 404 | CASE_NOT_FOUND | 사건 없음·고객의 타인 사건 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키의 다른 본문 |
| 409 | CASE_CLOSED / QUOTE_INVALID / QUOTE_EXPIRED / OFFER_NOT_ACTIVE / QUOTE_NOT_OFFERED | 종료·기간/그룹/상품·기한·현재 제안·발송 항목 위반 |
| 409 | REPLACEMENT_LOCKED / QUOTE_REFRESH_REQUIRED / NEW_OFFER_REQUIRED | 예약 실행 중/확정 또는 실패 후 새 견적·제안 필요 |
| 415 | UNSUPPORTED_MEDIA_TYPE | JSON 이외의 본문 |
| 500 | INTERNAL_ERROR | 내부 오류, 비밀값·DB 원문 미노출 |

예약 요청의 PROCESSING/RESULT_UNKNOWN/CONFIRMED 상태에서는 제안·선택 교체를 차단한다. FAILED는 예약 미생성이 확정된 상태라는 기존 계약에 따라, 옛 요청·선택을 보존하고 새 검색·새 제안으로 회복할 수 있게 한다. 실패한 예약에 사용했던 같은 검색/제안에서 재선택해 새 예약을 우회 생성하지 못하게 한다. 실제 API-015/029/030 실행은 다음 단계다.

## 4. 기존 API-011의 최소 확장

기존 caseId/items/pagination·메시지·제안 항목 필드를 유지한다. 최상위에 `activeOfferId`(string|null), `currentSelection`(null 또는 selectionId/offerId/quoteId/selectedAt), `effectiveAt`, `clockMode`(REAL_TIME|SYNTHETIC_DEMO)를 추가한다. 고객은 새로고침·이전 이력 조회 후에도 서버의 현재 제안·선택을 확인한다. 새 조회 URI는 만들지 않는다.

새 제안의 비교 기준은 전송 트랜잭션에서 기존 case_actions.details의 버전된 불변 스냅샷으로 저장한다. 전체 기간은 검색 작업에 동결된 원예약 총액과의 차이를 기존 `priceDifference` 필드로 표시한다. 부분 기간과 기존 스냅샷이 없는 과거 제안은 null이다. 현재 booking 가격으로 과거 카드의 차액을 재계산하지 않는다.

견적 유효성은 기존 QuoteSearchRuntime의 명시적 서버 판단 시각을 재사용한다. 감사용 sentAt/selectedAt/supersededAt은 실제 시각을 사용한다. 고객 UI는 PC 시각이나 고정 문자열로 유효성을 추정하지 않는다. 가상 시각·합성 데이터와 ‘선택 완료·예약 진행 전’을 표시한다.

## 5. 고객 전용 격리 시연

기존 local-chat-demo의 전용 DB 신원·loopback·독점 프로필 검증을 유지한다. 선택적으로 별도 32자 이상 `CSRO_CUSTOMER_DEMO_TOKEN`을 고정 demo-customer-001 주체에 연결한다. 상담사 토큰과 같은 값은 거부한다. 고객 토큰은 정확한 GET messages와 PUT selection만 허용하며, 고객에게 상담사 사건 목록·견적 검색 권한을 부여하지 않는다.

기존 상담사 3100→18082 프록시는 `CSRO_OFFER_DEMO_PROXY=true`에서만 POST offers를 추가 허용한다. 별도 고객 프록시 3101→18082는 `CSRO_CUSTOMER_DEMO_PROXY=true`를 명시하고 고객 토큰을 서버에만 보관한다. Host·Origin·Sec-Fetch·멱등키와 정확한 경로를 검사하며 브라우저가 Authorization·subject를 지정하지 못하게 한다. 기존 읽기·채팅·견적 옵션은 그대로다.

고객 페이지 `/customer/cases/{caseId}`는 API-011로 실제 수신 카드와 이전 이력을 조회하고, 현재 제안의 버튼으로만 선택한다. API-004 상담사 상세 조회를 고객에게 개방하지 않는다. 고객용 일반 메시지 전송·로그인 체계는 이번 시연 확장에 포함하지 않는다.

## 검증 대상

전용 격리 DB를 Flyway 전에 확인한다. 정확히 서로 다른 숙소 3곳·원자 저장, 고객 본인·역할·타인 사건, 미발송/교체/만료 차단, 동일 키 동시 요청과 다른 본문 충돌, 제안 교체와 선택 경합, 재생 뒤 상태 무변경, 예약 실행 가드, 카드 차액 불변·현재 선택 재조회, 고객/상담사 프록시 분리와 실제 브라우저 흐름을 검증한다. 기존 V1과 업무 DB를 초기화하지 않는다.
