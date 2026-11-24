# 고객 제안 전송과 선택 저장

API-013으로 상담사가 저장 견적 3곳을 발송하고, API-014로 고객 본인이 실제 수신한 현재 제안에서 선택한다. [사용자 승인 최소 계약](API-013-014-PROPOSAL.md)을 구현한 범위이며, Notion 팀 전체의 설계 승인 상태를 바꾸지 않는다.

## 저장과 재시도

| 요청 | 권한·입력 | 결과 |
| --- | --- | --- |
| `POST /api/v1/cases/{caseId}/offers` | 활성 상담사, `{"quoteIds":["…","…","…"]}` | 201: caseId·offerId·messageId·sentAt |
| `PUT /api/v1/cases/{caseId}/selection` | 예약 소유 고객, `{"offerId":"…","quoteId":"…"}` | 200: caseId·selectionId·offerId·quoteId·selectedAt |

두 요청은 JSON과 필수 `Idempotency-Key`를 사용한다. 키는 영문·숫자·`._:-` 1~100자다. 같은 키·같은 본문은 최초 기록을 반환하고 다른 본문이면 409다. 응답을 받지 못하면 같은 키와 본문으로 다시 확인한다. 옛 요청의 재생 결과는 현재 제안·선택을 되돌리지 않는다. 새 키는 새로운 업무 의도다.

- 전송은 같은 사건의 최신 완료 검색·같은 기간 그룹에서 서로 다른 숙소 3곳을 검사한다. 재고·정원·통화·금액·기한과 현재 검증된 장애 숙박일을 확인한다. Agent 비교 실패 시에도 검증된 저장 견적을 수동 발송할 수 있다.
- 제안·항목 3개·OFFER 메시지·감사 기록을 함께 저장한다. 이전 제안은 SUPERSEDED, 이전 현재 선택은 이력으로 남긴다. 선택·SYSTEM 알림·감사 기록도 함께 저장한다.
- 고객은 자신의 사건의 현재 ACTIVE 제안에 실제 포함된 견적만 선택한다. 상담사의 대리 선택, 타인 사건, 미발송·구제안·만료 견적은 차단한다.
- booking→case 순서로 잠금을 잡는다. DB 제약과 같은 잠금 순서로 현재 제안·선택을 각각 한 건으로 유지한다. 이 처리에는 외부 HTTP가 없다.
- 예약 요청이 PROCESSING/RESULT_UNKNOWN/CONFIRMED이면 제안·선택 교체를 막는다. 확정 FAILED 뒤에는 새 검색·새 제안을 사용하며 옛 요청과 선택 이력을 보존한다.

선택만으로 예약 요청, 입실 또는 사건 종료를 실행하지 않는다. 화면의 **‘선택 완료·예약 진행 전’**은 이 구분을 나타낸다.

## 채팅 조회와 시계

API-011은 기존 메시지·이전 페이지·제안 항목을 유지하면서 `activeOfferId`, `currentSelection`, `effectiveAt`, `clockMode`를 추가한다. 최신 페이지에 제안 카드가 없어도 현재 제안·선택 요약은 반환한다. 선택 결과를 재생한 뒤에는 이 조회로 현재 상태를 확인한다.

새 전체 기간 제안의 `priceDifference`는 발송 때 감사 기록에 함께 저장한 검색 기준 원금으로 계산한다. 현재 예약 금액을 바꿔 과거 차액을 다시 계산하지 않는다. 부분 기간·기준 스냅샷 없는 과거 제안은 null이다.

견적 유효성은 기존 서버 판단 시각을 쓰며 PC 시각으로 추정하지 않는다. 고정 Mock에는 `--quote.search.demo-as-of=2026-11-09T10:20:00+09:00`을 명시한다. `sentAt/selectedAt/supersededAt` 감사 시각은 실제 시각이다. 가상 시각을 사용하는 화면에는 합성 시연 표시가 나온다.

## 격리 시연 준비

먼저 [견적 시연 준비](QUOTES-DEMO.md)를 따른다. 전용 DB는 `127.0.0.1:25434/yeohaengbom_chat_demo`, 사용자 `yb_chat_demo`다. 일반 Compose의 5432 업무 DB로 바꾸지 않는다. 기존 DB 신원 확인, 독점 `local-chat-demo` 프로필과 loopback 제한을 유지한다.

이번 대상은 **YB-001 / 2026-11-10~13**이다. 새 전용 DB에서는 기존 공통 위험 배치로 실제 사건을 만든다. `seed-chat-demo-case.sql`의 YB-002/C-002는 11/22 별도 채팅 fixture이므로 이번 준비에 쓰지 않는다. 실제 사건 번호는 목록에서 확인하며 C-001이라고 하드코딩하지 않는다. Mock·ML·Agent 서버와 Spring의 기존 서버 간 토큰 설정도 준비되어 있어야 한다.

현재 Spring·3100 시연 프로세스를 사용 중이면 해당 터미널에서 정상 종료한 뒤 다시 시작한다. 고객 토큰은 Spring 시작 전에 설정해야 한다. 아래는 저장소 루트의 **같은 PowerShell 세션**에서 실행한다. DB 비밀번호는 기존 전용 DB와 같은 값을 유지한다.

```powershell
$offerRepo = (Get-Location).Path
$env:CSRO_CHAT_DEMO_TOKEN = [guid]::NewGuid().ToString('N')
$env:CSRO_CUSTOMER_DEMO_TOKEN = [guid]::NewGuid().ToString('N')
Remove-Item Env:CSRO_DEMO_TOKEN -ErrorAction SilentlyContinue
Set-Location backend
.\gradlew.bat bootJar
if ($LASTEXITCODE -ne 0) { throw 'Spring 빌드 실패' }
$offerJar = (Get-ChildItem build\libs\*.jar | Where-Object Name -NotLike '*-plain.jar' | Select-Object -First 1).FullName
$offerSpring = Start-Process java -ArgumentList @('-jar', ('"' + $offerJar + '"'), '--spring.profiles.active=local-chat-demo', '--quote.search.worker.enabled=true', '--quote.search.demo-as-of=2026-11-09T10:20:00+09:00') -PassThru
```

Spring의 `http://127.0.0.1:18082/hello` 응답을 확인한 뒤 같은 세션에서 두 프런트 프로세스를 시작한다. 새 터미널에는 환경변수가 자동 공유되지 않는다. 토큰을 소스·로그·브라우저 저장소에 붙여넣지 않는다.

```powershell
Set-Location (Join-Path $offerRepo 'frontend/travel_bom')
pnpm.cmd build
if ($LASTEXITCODE -ne 0) { throw '프런트 빌드 실패' }
$env:CSRO_LOCAL_DEMO = 'true'
$env:CSRO_DEMO_SPRING_PORT = '18082'
$env:CSRO_DEMO_TOKEN = $env:CSRO_CHAT_DEMO_TOKEN
$env:CSRO_CUSTOMER_DEMO_PROXY = 'false'
$env:CSRO_CHAT_DEMO_PROXY = 'true'
$env:CSRO_QUOTE_DEMO_PROXY = 'true'
$env:CSRO_OFFER_DEMO_PROXY = 'true'
$env:PORT = '3100'
$offerCounselor = Start-Process node -ArgumentList 'demo-server.mjs' -PassThru
$env:CSRO_CUSTOMER_DEMO_PROXY = 'true'
$env:PORT = '3101'
$offerCustomer = Start-Process node -ArgumentList 'demo-server.mjs' -PassThru
```

상담사는 `http://127.0.0.1:3100/cases`에서 YB-001 사건 → 대체 숙소 검색 → 같은 그룹의 후보 3곳 → 고객 제안 전송을 사용한다. 고객은 `http://127.0.0.1:3101/customer/cases/{실제 사건 번호}`에서 받은 카드를 조회·선택한다. 상담사는 채팅을 새로 조회해 SYSTEM 선택 알림과 현재 선택을 확인한다. 이전 카드·이전 선택은 이력으로 남는다.

고객 프록시는 3101→18082의 정확한 messages GET·selection PUT만 허용한다. 고객 토큰은 고정 `demo-customer-001` 주체이며 상담사 토큰과 다른 32자 이상 값이다. 고객에게 사건 목록·API-004 상세·견적 검색·일반 메시지 전송을 개방하지 않는다. 상담사 offers POST는 별도 `CSRO_OFFER_DEMO_PROXY=true`에서만 허용한다. 프록시의 Host·Origin·Sec-Fetch·경로·멱등키 검사를 유지하고 서버가 인증 토큰을 붙인다.

종료할 때는 이 세션에서 시작한 `$offerCounselor.Id`, `$offerCustomer.Id`, `$offerSpring.Id`만 `Stop-Process -Id ...`로 중단한다. 전용 DB의 종료·메모리 데이터 수명은 기존 준비 문서를 따른다.

## 검증과 남은 범위

단위 검사는 `backend`에서 `.\gradlew.bat test --tests 'com.csro.backend.offers.*' --tests '*LocalChatDemoAuthenticationTest'`로 실행한다. 쓰기 통합 검사는 `OFFER_SELECTION_TEST=true`와 정확한 `OFFER_SELECTION_TEST_DB_URL=jdbc:postgresql://127.0.0.1:25441/yeohaengbom_offers_test`, 사용자 `yb_offer_test`, `OFFER_SELECTION_TEST_DB_PASSWORD`가 필요하다. 설정과 실제 DB 이름·사용자를 Flyway 전에 검사한다. 미설정 시 DB 통합 검사는 건너뛴다.

Linux에서 이번 변경의 Spring 검사 27개를 통과했다. 이 중 전용 DB 검사는 제안·선택 13개와 채팅 6개이며, 나머지는 인증 경계·컨트롤러·오접속 차단 검사다. 프런트 빌드·TypeScript·프록시 10개와 응답 제어 브라우저 검사 `case-offers-browser.py`·`case-quotes-browser.py`도 통과했다. 재현 명령은 `pnpm.cmd build`, `pnpm.cmd exec tsc --noEmit`, `node --test tests/demo-offers-proxy.cjs tests/demo-quotes-proxy.cjs tests/demo-messages-proxy.cjs`다.

별도의 새 전용 DB에서 실제 ML→Mock→Agent→Spring→DB와 상담사 3100·고객 3101 브라우저를 연결했다. 위험 점검·사건 생성→견적 검색→제안 발송→고객 선택→새로고침과 API-013/014 동일 키 재생 뒤 제안 1건·항목 3건·선택 1건·사건 1건·필수 작업 1건을 확인했다. 예약 요청은 0건으로 유지했다. 고객의 사건 목록·상세·견적 조회는 차단했고, 상담사의 선택 PUT은 403이었다. Windows에서도 동일한 단위 8개·격리 DB 19개·프록시 10개와 실행 JAR·프런트 빌드·별도 TypeScript 검사가 통과했다. Windows 실제 브라우저 시연은 아직 확인하지 않았다.

전 기간 우선 검색은 완료된 자체 `quote-search-v1` Worker의 실행 경로와 저장된 그룹을 따른다. 부분 기간 제안에는 현재 VERIFIED+UNAVAILABLE 연속 구간을 다시 검사한다. 현재 저장 모델은 부분 검색 전의 전체 기간 빈 응답 원문을 별도로 보존하지 않으므로 그 빈 원문까지 재검증했다고 주장하지 않는다.

가상 예약 API-015/029/030, 별도 Mock 예약 저장소·원자 재고 처리, 입실·필수 후속 업무·사건 종료와 운영 로그인은 후속이다. 현재 고객 전용 인증은 격리 시연용이며 실제 고객 서비스 배포를 의미하지 않는다.
